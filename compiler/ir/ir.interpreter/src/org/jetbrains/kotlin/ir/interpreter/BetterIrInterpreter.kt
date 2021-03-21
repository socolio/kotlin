/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.interpreter.builtins.evaluateIntrinsicAnnotation
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretBinaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretTernaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretUnaryFunction
import org.jetbrains.kotlin.ir.interpreter.exceptions.InterpreterError
import org.jetbrains.kotlin.ir.interpreter.exceptions.InterpreterTimeOutError
import org.jetbrains.kotlin.ir.interpreter.exceptions.throwAsUserException
import org.jetbrains.kotlin.ir.interpreter.stack.Variable
import org.jetbrains.kotlin.ir.interpreter.state.*
import org.jetbrains.kotlin.ir.interpreter.state.Complex
import org.jetbrains.kotlin.ir.interpreter.state.ExceptionState
import org.jetbrains.kotlin.ir.interpreter.state.Primitive
import org.jetbrains.kotlin.ir.interpreter.state.State
import org.jetbrains.kotlin.ir.interpreter.state.asBoolean
import org.jetbrains.kotlin.ir.interpreter.state.isSubtypeOf
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

private const val MAX_COMMANDS = 1_000_000

internal class DataStack {
    private val stack = mutableListOf<State>()

    fun isEmpty() = stack.isEmpty()

    fun push(state: State) {
        stack.add(state)
    }

    fun pop(): State = stack.removeLast()
    fun peek(): State = stack.last()
}

internal class CallStack {
    private val frames = mutableListOf<FrameContainer>()
    internal fun getCurrentFrame() = frames.last()

    fun newFrame(frameOwner: IrElement, instructions: List<Instruction>, irFile: IrFile? = null) {
        val newFrame = Frame(instructions.toMutableList(), frameOwner)
        frames.add(FrameContainer(newFrame, irFile))
    }

    fun newFrame(frameOwner: IrFunction, instructions: List<Instruction>) {
        val newFrame = Frame(instructions.toMutableList(), frameOwner)
        frames.add(FrameContainer(newFrame, frameOwner.fileOrNull))
    }

    fun newSubFrame(frameOwner: IrElement, instructions: List<Instruction>) {
        val newFrame = Frame(instructions.toMutableList(), frameOwner)
        getCurrentFrame().addSubFrame(newFrame)
    }

    fun dropFrame() {
        frames.removeLast()
    }

    fun dropFrameGracefully(irReturn: IrReturn, result: State) {
        val frame = getCurrentFrame()
        while (!frame.hasNoFrames()) {
            val frameOwner = frame.currentSubFrameOwner
            dropSubFrame()
            if (frameOwner is IrTry) {
                pushState(result)
                addInstruction(SimpleInstruction(irReturn))
                addInstruction(CompoundInstruction(frameOwner.finallyExpression))
                return
            }
        }
        dropFrame()
        pushState(result)
    }

    fun dropSubFrame() {
        getCurrentFrame().removeSubFrame()
    }

    fun dropFrameUntil(owner: IrElement, includeOwnerFrame: Boolean = false) {
        while (getCurrentFrame().currentSubFrameOwner != owner) {
            dropSubFrame()
        }
        if (includeOwnerFrame) dropSubFrame()
    }

    fun dropFrameUntilTryCatch() {
        val exception = popState()
        while (frames.isNotEmpty()) {
            val frame = getCurrentFrame()
            while (!frame.hasNoFrames()) {
                if (frames.size == 1 && frame.hasOneFrameLeft()) {
                    pushState(exception)
                    return frame.dropInstructions()
                }
                val frameOwner = frame.currentSubFrameOwner
                if (frameOwner is IrTry) {
                    dropSubFrame()
                    newSubFrame(frameOwner, listOf())
                    pushState(exception)
                    addInstruction(SimpleInstruction(frameOwner))
//                    frameOwner.finallyExpression?.let { addInstruction(CompoundInstruction(it)) }
                    frameOwner.catches.reversed().forEach { addInstruction(CompoundInstruction(it)) }
                    return
                }
                dropSubFrame() // TODO drop with info loosing
            }
            dropFrame()
        }
    }

    fun hasNoInstructions() = frames.isEmpty() || (frames.size == 1 && frames.first().hasNoInstructions())

    fun addInstruction(instruction: Instruction) {
        getCurrentFrame().addInstruction(instruction)
    }

    fun popInstruction(): Instruction {
        //while (getCurrentFrame().isEmpty()) dropFrame()
        return getCurrentFrame().popInstruction()
    }

    fun pushState(state: State) {
        getCurrentFrame().pushState(state)
    }

    fun popState(): State = getCurrentFrame().popState()
    fun peekState(): State? = getCurrentFrame().peekState()

    fun addVariable(variable: Variable) {
        getCurrentFrame().addVariable(variable)
    }

    fun getVariable(symbol: IrSymbol): Variable = getCurrentFrame().getVariable(symbol)

    fun getStackTrace(): List<String> {
        return frames.map { it.toString() }
    }
}

internal class FrameContainer(frame: Frame, val irFile: IrFile? = null) {
    var lineNumber: Int = -1
    private val innerStack = mutableListOf(frame)
    val currentSubFrameOwner: IrElement
        get() = getCurrentFrame().owner

    private fun getCurrentFrame() = innerStack.last()

    fun addSubFrame(frame: Frame) {
        innerStack.add(frame)
    }

    fun removeSubFrame() {
        getCurrentFrame().peekState()?.let { if (innerStack.size > 1) innerStack[innerStack.size - 2].pushState(it) }
        innerStack.removeLast()
    }

    fun hasNoFrames() = innerStack.isEmpty()
    fun hasOneFrameLeft() = innerStack.size == 1
    fun hasNoInstructions() = hasNoFrames() || (innerStack.size == 1 && innerStack.first().isEmpty())

    fun addInstruction(instruction: Instruction) {
        getCurrentFrame().pushInstruction(instruction)
    }

    fun popInstruction(): Instruction {
        return getCurrentFrame().popInstruction()
    }

    fun dropInstructions() = getCurrentFrame().dropInstructions()

    fun pushState(state: State) {
        getCurrentFrame().pushState(state)
    }

    fun popState(): State = getCurrentFrame().popState()
    fun peekState(): State? = getCurrentFrame().peekState()

    fun addVariable(variable: Variable) {
        getCurrentFrame().addVariable(variable)
    }

    fun getVariable(symbol: IrSymbol): Variable {
        return innerStack.firstNotNullResult { it.getVariable(symbol) }
            ?: throw InterpreterError("$symbol not found") // TODO better message
    }

   // fun getAll() = innerStack.flatMap { it.getAll() }

    override fun toString(): String {
        irFile ?: return "Not defined"
        val fileNameCapitalized = irFile.name.replace(".kt", "Kt").capitalize()
        val lineNum = getCurrentFrame().getLineNumberForCurrentInstruction(irFile)
        val entryPoint = innerStack.map { it.owner }.firstOrNull { it is IrFunction } as? IrFunction
        return "at $fileNameCapitalized.${entryPoint?.fqNameWhenAvailable ?: "<clinit>"}(${irFile.name}:$lineNum)"
    }
}

internal class Frame(private val instructions: MutableList<Instruction>, val owner: IrElement) {
    private val memory = mutableListOf<Variable>()
    private val dataStack = DataStack()

    fun isEmpty() = instructions.isEmpty()

    fun getLineNumberForCurrentInstruction(irFile: IrFile): Int {
        val element = instructions.firstOrNull()?.element ?: owner
        return irFile.fileEntry.getLineNumber(element.startOffset) + 1
    }

    fun pushInstruction(instruction: Instruction) {
        instructions.add(0, instruction)
    }

    fun popInstruction(): Instruction {
        return instructions.removeFirst()
    }

    fun dropInstructions() = instructions.clear()

    fun pushState(state: State) {
        dataStack.push(state)
    }

    fun popState(): State = dataStack.pop()
    fun peekState(): State? = if (!dataStack.isEmpty()) dataStack.peek() else null

    fun addVariable(variable: Variable) {
        memory += variable
    }

    fun getVariable(symbol: IrSymbol): Variable? = memory.firstOrNull { it.symbol == symbol }
}

internal interface Instruction {
    val element: IrElement?
}
internal inline class CompoundInstruction(override val element: IrElement?) : Instruction // must unwind first
internal inline class SimpleInstruction(override val element: IrElement) : Instruction   // must interpret as is

internal class IrInterpreterEnvironment(val irBuiltIns: IrBuiltIns, val callStack: CallStack) {
    val irExceptions = mutableListOf<IrClass>()
    val mapOfEnums = mutableMapOf<IrSymbol, Complex>()
    val mapOfObjects = mutableMapOf<IrSymbol, Complex>()
}

class BetterIrInterpreter(val irBuiltIns: IrBuiltIns, private val bodyMap: Map<IdSignature, IrBody> = emptyMap()) {
    private val environment = IrInterpreterEnvironment(irBuiltIns, CallStack())
    private val callStack: CallStack
        get() = environment.callStack
    private var commandCount = 0

    private fun incrementAndCheckCommands() {
        commandCount++
        if (commandCount >= MAX_COMMANDS) handleUserException(InterpreterTimeOutError(), environment)
    }

    fun interpret(expression: IrExpression, file: IrFile? = null): IrExpression {
        callStack.newFrame(expression, listOf(CompoundInstruction(expression)), file)

        while (!callStack.hasNoInstructions()) {
            when (val instruction = callStack.popInstruction()) {
                is CompoundInstruction -> unwindInstruction(instruction)
                is SimpleInstruction -> interpret(instruction.element)
            }
            incrementAndCheckCommands()
        }

        return callStack.popState().toIrExpression(expression).apply { callStack.dropFrame() }
    }

    private fun unwindInstruction(instruction: CompoundInstruction) {
        when (val element = instruction.element) {
            null -> return
            is IrSimpleFunction -> {
//                if (stack.getStackCount() >= MAX_STACK) StackOverflowError().throwAsUserException()
//                if (irFunction.body is IrSyntheticBody) return handleIntrinsicMethods(irFunction)
//                return irFunction.body?.interpret() ?: throw InterpreterError("Ir function must be with body")
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.body!!))
            }
            is IrConstructor -> {
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.body!!))
            }
            is IrCall -> {
                callStack.addInstruction(SimpleInstruction(element))
                (0 until element.valueArgumentsCount).map { CompoundInstruction(element.getValueArgument(it)) }.reversed().forEach { callStack.addInstruction(it) }
                callStack.addInstruction(CompoundInstruction(element.extensionReceiver))
                callStack.addInstruction(CompoundInstruction(element.dispatchReceiver))
            }
            is IrConstructorCall -> {
                callStack.addInstruction(SimpleInstruction(element))
                (0 until element.valueArgumentsCount).map { CompoundInstruction(element.getValueArgument(it)) }.reversed().forEach { callStack.addInstruction(it) }
            }
            is IrDelegatingConstructorCall -> {
                callStack.addInstruction(SimpleInstruction(element))
                (0 until element.valueArgumentsCount).map { CompoundInstruction(element.getValueArgument(it)) }.reversed().forEach { callStack.addInstruction(it) }
            }
            is IrInstanceInitializerCall -> {
                //callStack.addInstruction(SimpleInstruction(element))
                val irClass = element.classSymbol.owner

                // init blocks processing
                val anonymousInitializer = irClass.declarations.filterIsInstance<IrAnonymousInitializer>().filter { !it.isStatic }
                anonymousInitializer.reversed().forEach { callStack.addInstruction(CompoundInstruction(it.body)) }

                // properties processing
                val classProperties = irClass.declarations.filterIsInstance<IrProperty>()
                classProperties.filter { it.backingField?.initializer?.expression != null }.reversed().forEach { callStack.addInstruction(CompoundInstruction(it.backingField)) }
            }
            is IrField -> {
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.initializer?.expression))
            }
            is IrReturn -> {
                callStack.addInstruction(SimpleInstruction(element)) //2
                callStack.addInstruction(CompoundInstruction(element.value)) //1
            }
            is IrConst<*> -> callStack.addInstruction(SimpleInstruction(element))
            is IrWhen -> {
                // new sub frame to drop it after
                callStack.newSubFrame(element, element.branches.map { CompoundInstruction(it) } + listOf(SimpleInstruction(element)))
            }
            is IrBranch -> {
                callStack.addInstruction(SimpleInstruction(element)) //2
                callStack.addInstruction(CompoundInstruction(element.condition)) //1
            }
            is IrBlock -> {
                // new sub frame
                callStack.newSubFrame(element, listOf())
                callStack.addInstruction(SimpleInstruction(element))
                element.statements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) }
            }
            is IrBody -> {
                // TODO new sub frame???
                element.statements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) }
            }
            is IrVariable -> {
                if (element.initializer == null) {
                    callStack.addVariable(Variable(element.symbol))
                } else {
                    callStack.addInstruction(SimpleInstruction(element))
                    callStack.addInstruction(CompoundInstruction(element.initializer!!))
                }
            }
            is IrGetValue -> {
                callStack.pushState(callStack.getVariable(element.symbol).state)
            }
            is IrSetValue -> {
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.value))
            }
            is IrWhileLoop -> {
                callStack.newSubFrame(element, listOf())
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.condition))
            }
            is IrDoWhileLoop -> {
                callStack.newSubFrame(element, listOf())
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.condition))
                callStack.addInstruction(CompoundInstruction(element.body!!))
            }
            is IrContinue -> {
                // TODO drop frames without stack values propagation
                callStack.dropFrameUntil(element.loop, includeOwnerFrame = true)
                callStack.addInstruction(CompoundInstruction(element.loop))
            }
            is IrTypeOperatorCall -> {
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.argument))
            }
            is IrTry -> {
                callStack.newSubFrame(element, listOf())
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.tryResult))
            }
            is IrCatch -> {
                // if exception
                val exceptionState = callStack.peekState() as? ExceptionState ?: return
                if (exceptionState.isSubtypeOf(element.catchParameter.type)) {
                    callStack.popState()
                    val frameOwner = callStack.getCurrentFrame().currentSubFrameOwner as IrTry
                    callStack.dropSubFrame() // drop other catch blocks
                    callStack.newSubFrame(frameOwner, listOf()) // new frame with IrTry as owner to interpret finally block at the end
                    callStack.addVariable(Variable(element.catchParameter.symbol, exceptionState))
                    callStack.addInstruction(SimpleInstruction(frameOwner))
//                    frameOwner.finallyExpression?.let { callStack.addInstruction(CompoundInstruction(it)) }
                    callStack.addInstruction(CompoundInstruction(element.result))
                }

//                element.catchParameter // check here
//                // if true -> pop and
//                element.result
            }
            is IrGetField -> callStack.addInstruction(SimpleInstruction(element))
            is IrSetField -> {
                // receiver is null, for example, for top level fields; cannot interpret set on top level var
                if (element.receiver.let { it == null || (it.type.classifierOrNull?.owner as? IrClass)?.isObject == true }) {
                    error("Cannot interpret set method on top level properties")
                }

                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.value))
            }
            is IrGetObjectValue -> {
                val objectClass = element.symbol.owner
                environment.mapOfObjects[objectClass.symbol]?.let { return callStack.pushState(it) }

                when {
                    objectClass.hasAnnotation(evaluateIntrinsicAnnotation) ->
                        environment.mapOfObjects[objectClass.symbol] = Wrapper.getCompanionObject(objectClass)
                    else -> {
                        val constructor = objectClass.constructors.first()
                        val constructorCall = IrConstructorCallImpl.fromSymbolOwner(constructor.returnType, constructor.symbol)
                        callStack.addInstruction(SimpleInstruction(element))
                        callStack.addInstruction(CompoundInstruction(constructorCall))
                    }
                }
            }
            is IrComposite -> {
                when (element.origin) {
                    IrStatementOrigin.DESTRUCTURING_DECLARATION -> element.statements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) }
                    null -> element.statements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) } // is null for body of do while loop
                    else -> TODO("${element.origin} not implemented")
                }
            }

            else -> TODO("${element.javaClass} not supported")
        }
    }

    private fun interpret(element: IrElement) {
        when (element) {
            is IrSimpleFunction -> callStack.dropFrame()
            is IrConstructor -> {
                val objectState = callStack.getVariable((element.parent as IrClass).thisReceiver!!.symbol).state as Common
                val returnedState = callStack.popState() as Complex
                objectState.superWrapperClass = returnedState?.superWrapperClass ?: returnedState as? Wrapper
                callStack.dropFrame()

                callStack.pushState(objectState)
            }
            is IrCall -> interpretCall(element)
            is IrConstructorCall -> interpretConstructor(element)
            is IrDelegatingConstructorCall -> {
                if (element.symbol.owner.parent == irBuiltIns.anyClass.owner) {
                    callStack.pushState(Common(irBuiltIns.anyClass.owner))
                    return
                }
                interpretConstructor(element)
            }
            is IrInstanceInitializerCall -> interpretInstanceInitializerCall(element)
            is IrField -> {
                val irClass = element.parentAsClass
                val receiver = irClass.thisReceiver!!.symbol
                val receiverState = callStack.getVariable(receiver).state
                receiverState.setField(Variable(element.correspondingPropertySymbol!!, callStack.popState()))
            }
            is IrConst<*> -> {
//                dataStack.push(element.toPrimitive())
                callStack.pushState(element.toPrimitive())
            }
            is IrReturn -> {
                val result = callStack.popState()
                callStack.dropFrameGracefully(element, result)
//                callStack.pushState(result)
            }
            is IrBranch -> {
                val result = callStack.popState().asBoolean()
                if (result) {
                    callStack.dropSubFrame()
                    //callStack.newFrame(element.result, listOf(CompoundInstruction(element.result)), asSubFrame = true)
                    callStack.addInstruction(CompoundInstruction(element.result))
                }
            }
            is IrWhen -> {
                callStack.dropSubFrame()
            }
            is IrVariable -> {
                callStack.addVariable(Variable(element.symbol, callStack.popState()))
            }
            is IrSetValue -> {
                callStack.getVariable(element.symbol).state = callStack.popState()
            }
            is IrWhileLoop -> {
                val result = callStack.popState().asBoolean()
                callStack.dropSubFrame()
                if (result) {
                    callStack.newSubFrame(
                        element,
                        listOf(CompoundInstruction(element.body), CompoundInstruction(element.condition), SimpleInstruction(element))
                    )
                }
            }
            is IrDoWhileLoop -> {
                val result = callStack.popState().asBoolean()
                callStack.dropSubFrame()
                if (result) {
                    callStack.newSubFrame(
                        element,
                        listOf(CompoundInstruction(element.body), CompoundInstruction(element.condition), SimpleInstruction(element))
                    )
                }
            }
            is IrTypeOperatorCall -> {
                when (element.operator) {
                    IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> callStack.popState()
                    else -> TODO("TypeOperator ${element.operator} not implemented")
                }
            }
            is IrBlock -> {
                callStack.dropSubFrame()
            }
            is IrTry -> {
                val frameOwner = callStack.getCurrentFrame().currentSubFrameOwner
                // 1. after first evaluation of try, must process finally expression
                if (frameOwner is IrTry) {
                    callStack.dropSubFrame()
                    if (element.finallyExpression != null) {
                        callStack.addInstruction(SimpleInstruction(element))
                        callStack.addInstruction(CompoundInstruction(element.finallyExpression))
                        return
                    }
                }

                // 2. after evaluation of finally, check that there are not unhandled exceptions left
                if (callStack.peekState() is ExceptionState) {
                    environment.callStack.dropFrameUntilTryCatch()
                }
            }
            is IrGetField -> interpretGetField(element)
            is IrSetField -> interpretSetField(element)
            is IrGetObjectValue -> interpretGetObjectValue(element)
            else -> TODO("${element.javaClass} not supported for interpretation")
        }
    }

    private fun interpretCall(call: IrCall) {
        val valueArguments = call.symbol.owner.valueParameters.map { callStack.popState() }.reversed()
        val extensionReceiver = call.extensionReceiver?.let { callStack.popState() }//?.checkNullability(call.extensionReceiver?.type)
        var dispatchReceiver = call.dispatchReceiver?.let { callStack.popState() }//?.checkNullability(call.dispatchReceiver?.type)

        val irFunction = dispatchReceiver?.getIrFunctionByIrCall(call) ?: call.symbol.owner
        dispatchReceiver = when (irFunction.parent) {
            (dispatchReceiver as? Complex)?.superWrapperClass?.irClass -> dispatchReceiver.superWrapperClass
            else -> dispatchReceiver
        }

        // callStack.fixCallEntryPoint(call)
        callStack.newFrame(irFunction, listOf())
        irFunction.getDispatchReceiver()?.let { dispatchReceiver?.let { receiver -> callStack.addVariable(Variable(it, receiver)) } }
        irFunction.getExtensionReceiver()?.let { extensionReceiver?.let { receiver -> callStack.addVariable(Variable(it, receiver)) } }
        irFunction.valueParameters.forEachIndexed { i, param -> callStack.addVariable(Variable(param.symbol, valueArguments[i])) }

        when {
            irFunction.body == null -> calculateBuiltIns(irFunction)
            else -> callStack.addInstruction(CompoundInstruction(irFunction))
        }
    }

    private fun getArgs(irFunction: IrFunction): List<State> {
        val args = mutableListOf<State>()

        irFunction.getDispatchReceiver()?.let { args += callStack.getVariable(it).state }
        irFunction.getExtensionReceiver()?.let { args += callStack.getVariable(it).state }
        irFunction.valueParameters.forEach { args += callStack.getVariable(it.symbol).state }

        return args
    }

    private fun calculateBuiltIns(irFunction: IrFunction) {
        val methodName = when (val property = (irFunction as? IrSimpleFunction)?.correspondingPropertySymbol) {
            null -> irFunction.name.asString()
            else -> property.owner.name.asString()
        }
        val args = getArgs(irFunction)
        callStack.dropFrame()

        val receiverType = irFunction.dispatchReceiverParameter?.type
        val argsType = listOfNotNull(receiverType) + irFunction.valueParameters.map { it.type }
        val argsValues = args.map { if (it is Primitive<*>) it.value else it }

        fun IrType.getOnlyName(): String {
            return when {
                this.originalKotlinType != null -> this.originalKotlinType.toString()
                this is IrSimpleType -> (this.classifierOrFail.owner as IrDeclarationWithName).name.asString() + (if (this.hasQuestionMark) "?" else "")
                else -> this.render()
            }
        }

        // TODO replace unary, binary, ternary functions with vararg
        withExceptionHandler(environment) {
            val result = when (argsType.size) {
                1 -> interpretUnaryFunction(methodName, argsType[0].getOnlyName(), argsValues[0])
                2 -> when (methodName) {
                    "rangeTo" -> return calculateRangeTo(irFunction.returnType, args)
                    else -> interpretBinaryFunction(
                        methodName, argsType[0].getOnlyName(), argsType[1].getOnlyName(), argsValues[0], argsValues[1]
                    )
                }
                3 -> interpretTernaryFunction(
                    methodName, argsType[0].getOnlyName(), argsType[1].getOnlyName(), argsType[2].getOnlyName(),
                    argsValues[0], argsValues[1], argsValues[2]
                )
                else -> throw InterpreterError("Unsupported number of arguments for invocation as builtin functions")
            }
            // TODO check "result is Unit"
            callStack.pushState(result.toState(irFunction.returnType))
        }
    }

    private fun calculateRangeTo(type: IrType, args: List<State>) {
        val constructor = type.classOrNull!!.owner.constructors.first()
        val constructorCall = IrConstructorCallImpl.fromSymbolOwner(constructor.returnType, constructor.symbol)
        val constructorValueParameters = constructor.valueParameters.map { it.symbol }

        val primitiveValueParameters = args.map { it as Primitive<*> }
        primitiveValueParameters.forEachIndexed { index, primitive ->
            constructorCall.putValueArgument(index, primitive.value.toIrConst(constructorValueParameters[index].owner.type))
        }

//        constructorValueParameters.zip(primitiveValueParameters).map { Variable(it.first, it.second) }
        callStack.addInstruction(CompoundInstruction(constructorCall))
//        stack.newFrame(initPool = ) {
//            constructorCall.interpret()
//        }
    }

    private fun interpretInstanceInitializerCall(call: IrInstanceInitializerCall) {
        val irClass = call.classSymbol.owner

        val classProperties = irClass.declarations.filterIsInstance<IrProperty>()
        classProperties.forEach { property ->
            property.backingField?.initializer?.expression ?: return@forEach
            val receiver = irClass.thisReceiver!!.symbol
            if (property.backingField?.initializer != null) {
                val receiverState = callStack.getVariable(receiver).state
                val propertyVar = Variable(property.symbol, callStack.popState())
                receiverState.setField(propertyVar)
            }
        }
    }

    private fun interpretConstructor(constructorCall: IrFunctionAccessExpression) {
        val valueArguments = constructorCall.symbol.owner.valueParameters.map { callStack.popState() }.reversed()

        val constructor = constructorCall.symbol.owner
        val irClass = constructor.parentAsClass
        val classState = when (constructorCall) {
            is IrConstructorCall -> Variable(constructorCall.getThisReceiver(), Common(irClass))
            else -> callStack.getVariable(constructorCall.getThisReceiver())
        }

        callStack.newFrame(constructor, listOf())
        callStack.addVariable(classState)
        constructor.valueParameters.forEachIndexed { i, param -> callStack.addVariable(Variable(param.symbol, valueArguments[i])) }
        val superReceiver = when (val irStatement = constructor.body!!.statements[0]) {
            is IrTypeOperatorCall -> (irStatement.argument as IrFunctionAccessExpression).getThisReceiver() // for enums
            is IrFunctionAccessExpression -> irStatement.getThisReceiver()
            is IrBlock -> (irStatement.statements.last() as IrFunctionAccessExpression).getThisReceiver()
            else -> TODO("${irStatement::class.java} is not supported as first statement in constructor call")
        }
        callStack.addVariable(Variable(superReceiver, classState.state))

        callStack.addInstruction(CompoundInstruction(constructor))
    }

    private fun interpretSetField(expression: IrSetField) {
        val receiver = (expression.receiver as IrDeclarationReference).symbol
        val propertySymbol = expression.symbol.owner.correspondingPropertySymbol!!
        callStack.getVariable(receiver).apply { this.state.setField(Variable(propertySymbol, callStack.popState())) }
    }

    private fun interpretGetField(expression: IrGetField) {
        val receiver = (expression.receiver as? IrDeclarationReference)?.symbol
        val field = expression.symbol.owner
        // for java static variables
        when {
            field.origin == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB && field.isStatic -> {
                when (val initializerExpression = field.initializer?.expression) {
                    is IrConst<*> -> callStack.addInstruction(SimpleInstruction(initializerExpression))
                    else -> callStack.pushState(Wrapper.getStaticGetter(field)!!.invokeWithArguments().toState(field.type))
                }
            }
            field.origin == IrDeclarationOrigin.PROPERTY_BACKING_FIELD && field.correspondingPropertySymbol?.owner?.isConst == true -> {
                callStack.addInstruction(CompoundInstruction(field.initializer?.expression))
            }
            // receiver is null, for example, for top level fields
            expression.receiver.let { it == null || (it.type.classifierOrNull?.owner as? IrClass)?.isObject == true } -> {
                val propertyOwner = field.correspondingPropertySymbol?.owner
                val isConst = propertyOwner?.isConst == true || propertyOwner?.backingField?.initializer?.expression is IrConst<*>
                assert(isConst) { "Cannot interpret get method on top level non const properties" }
                callStack.addInstruction(CompoundInstruction(expression.symbol.owner.initializer?.expression))
            }
            else -> {
                val result = callStack.getVariable(receiver!!).state.getState(field.correspondingPropertySymbol!!)
                callStack.pushState(result!!)
            }
        }
    }

    private fun interpretGetObjectValue(expression: IrGetObjectValue) {
        val objectClass = expression.symbol.owner
        environment.mapOfObjects[objectClass.symbol] = callStack.peekState() as Complex
    }
}
