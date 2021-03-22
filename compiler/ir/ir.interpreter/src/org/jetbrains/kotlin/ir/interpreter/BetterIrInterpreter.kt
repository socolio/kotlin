/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.interpreter.builtins.evaluateIntrinsicAnnotation
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretBinaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretTernaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretUnaryFunction
import org.jetbrains.kotlin.ir.interpreter.exceptions.InterpreterError
import org.jetbrains.kotlin.ir.interpreter.exceptions.InterpreterTimeOutError
import org.jetbrains.kotlin.ir.interpreter.exceptions.throwAsUserException
import org.jetbrains.kotlin.ir.interpreter.intrinsics.BetterIntrinsicEvaluator
import org.jetbrains.kotlin.ir.interpreter.intrinsics.IntrinsicEvaluator
import org.jetbrains.kotlin.ir.interpreter.proxy.CommonProxy.Companion.asProxy
import org.jetbrains.kotlin.ir.interpreter.proxy.Proxy
import org.jetbrains.kotlin.ir.interpreter.proxy.wrap
import org.jetbrains.kotlin.ir.interpreter.stack.Variable
import org.jetbrains.kotlin.ir.interpreter.state.*
import org.jetbrains.kotlin.ir.interpreter.state.Complex
import org.jetbrains.kotlin.ir.interpreter.state.ExceptionState
import org.jetbrains.kotlin.ir.interpreter.state.Primitive
import org.jetbrains.kotlin.ir.interpreter.state.State
import org.jetbrains.kotlin.ir.interpreter.state.asBoolean
import org.jetbrains.kotlin.ir.interpreter.state.isSubtypeOf
import org.jetbrains.kotlin.ir.interpreter.state.reflection.KFunctionState
import org.jetbrains.kotlin.ir.interpreter.state.reflection.KTypeState
import org.jetbrains.kotlin.ir.interpreter.state.reflection.ReflectionState
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import java.lang.invoke.MethodHandle

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
internal inline class IntrinsicInstruction(override val element: IrFunction) : Instruction

internal class IrInterpreterEnvironment(val irBuiltIns: IrBuiltIns, val callStack: CallStack) {
    val irExceptions = mutableListOf<IrClass>()
    var mapOfEnums = mutableMapOf<IrSymbol, Complex>()
    var mapOfObjects = mutableMapOf<IrSymbol, Complex>()

    private constructor(environment: IrInterpreterEnvironment) : this(environment.irBuiltIns, CallStack()) {
        irExceptions.addAll(environment.irExceptions)
        mapOfEnums = environment.mapOfEnums
        mapOfObjects = environment.mapOfObjects
    }

    fun copyWithNewCallStack(): IrInterpreterEnvironment {
        return IrInterpreterEnvironment(this)
    }
}

class IrInterpreter private constructor(
    val irBuiltIns: IrBuiltIns,
    private val bodyMap: Map<IdSignature, IrBody>,
    private val environment: IrInterpreterEnvironment
) {
    private val callStack: CallStack
        get() = environment.callStack
    private var commandCount = 0

    constructor(irBuiltIns: IrBuiltIns, bodyMap: Map<IdSignature, IrBody> = emptyMap()) :
            this(irBuiltIns, bodyMap, IrInterpreterEnvironment(irBuiltIns, CallStack()))

    private constructor(environment: IrInterpreterEnvironment, bodyMap: Map<IdSignature, IrBody> = emptyMap()) :
            this(environment.irBuiltIns, bodyMap, environment)

    private fun incrementAndCheckCommands() {
        commandCount++
        if (commandCount >= MAX_COMMANDS) InterpreterTimeOutError().handleUserException(environment)
    }

    private fun Any?.getType(defaultType: IrType): IrType {
        return when (this) {
            is Boolean -> irBuiltIns.booleanType
            is Char -> irBuiltIns.charType
            is Byte -> irBuiltIns.byteType
            is Short -> irBuiltIns.shortType
            is Int -> irBuiltIns.intType
            is Long -> irBuiltIns.longType
            is String -> irBuiltIns.stringType
            is Float -> irBuiltIns.floatType
            is Double -> irBuiltIns.doubleType
            null -> irBuiltIns.nothingNType
            else -> defaultType
        }
    }

    private fun Instruction.handle() {
        when (this) {
            is CompoundInstruction -> unwindInstruction(this)
            is SimpleInstruction -> interpret(this.element)
            is IntrinsicInstruction -> BetterIntrinsicEvaluator.evaluate(this.element, environment)
        }
    }

    fun interpret(expression: IrExpression, file: IrFile? = null): IrExpression {
        commandCount = 0
        callStack.newFrame(expression, listOf(CompoundInstruction(expression)), file)

        while (!callStack.hasNoInstructions()) {
            callStack.popInstruction().handle()
            incrementAndCheckCommands()
        }

        return callStack.popState().toIrExpression(expression).apply { callStack.dropFrame() }
    }

    private fun withNewCallStack(block: IrInterpreter.() -> Any?): Any? {
        return with(IrInterpreter(environment.copyWithNewCallStack(), bodyMap)) {
            block()
        }
    }

    internal fun IrFunction.proxyInterpret(valueArguments: List<Variable>, expectedResultClass: Class<*> = Any::class.java): Any? {
        return withNewCallStack {
            callStack.newFrame(this@proxyInterpret, listOf(CompoundInstruction(this@proxyInterpret)))
            valueArguments.forEach { callStack.addVariable(it) }

            while (!callStack.hasNoInstructions()) { // TODO execute only instructions of this function
                callStack.popInstruction().handle()
                incrementAndCheckCommands()
            }

            callStack.popState().wrap(this@IrInterpreter, expectedResultClass).apply { callStack.dropFrame() }
        }
    }

    private fun unwindInstruction(instruction: CompoundInstruction) {
        when (val element = instruction.element) {
            null -> return
            is IrSimpleFunction -> {
//                if (stack.getStackCount() >= MAX_STACK) StackOverflowError().throwAsUserException()
                if (element.body is IrSyntheticBody) return handleIntrinsicMethods(element)
                callStack.addInstruction(SimpleInstruction(element))
                element.body?.let { callStack.addInstruction(CompoundInstruction(it)) }
                    ?: throw InterpreterError("Ir function must be with body")
            }
            is IrConstructor -> {
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.body!!))
            }
            is IrCall -> {
                val function = element.symbol.owner
                // new sub frame is used to store value arguments, in case then they are used in default args evaluation
                callStack.newSubFrame(element, listOf())
                callStack.addInstruction(SimpleInstruction(element))
                unwindValueParameters(element)

                // must save receivers in memory in case then they are used in default args evaluation
                element.extensionReceiver?.let {
                    callStack.addInstruction(SimpleInstruction(function.extensionReceiverParameter!!))
                    callStack.addInstruction(CompoundInstruction(it))
                }
                element.dispatchReceiver?.let {
                    callStack.addInstruction(SimpleInstruction(function.dispatchReceiverParameter!!))
                    callStack.addInstruction(CompoundInstruction(it))
                }
            }
            is IrConstructorCall -> {
                callStack.newSubFrame(element, listOf()) // used to store value arguments, in case then they are use as default args
                callStack.addInstruction(SimpleInstruction(element))
                unwindValueParameters(element)
            }
            is IrDelegatingConstructorCall -> {
                callStack.newSubFrame(element, listOf()) // used to store value arguments, in case then they are use as default args
                callStack.addInstruction(SimpleInstruction(element))
                unwindValueParameters(element)
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
            is IrSpreadElement -> callStack.addInstruction(CompoundInstruction(element.expression))
            is IrVararg -> {
                callStack.addInstruction(SimpleInstruction(element))
                element.elements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) }
            }
            is IrFunctionExpression -> callStack.addInstruction(SimpleInstruction(element))

            else -> TODO("${element.javaClass} not supported")
        }
    }

    private fun unwindValueParameters(expression: IrFunctionAccessExpression) {
        val irFunction = expression.symbol.owner
        // if irFunction is lambda and it has receiver, then first descriptor must be taken from extension receiver
        val receiverAsFirstArgument = when (expression.valueArgumentsCount != irFunction.valueParameters.size) {
            true -> listOfNotNull(irFunction.getExtensionReceiver())
            else -> listOf()
        }
        val valueParametersSymbols = receiverAsFirstArgument + irFunction.valueParameters.map { it.symbol }

        fun IrValueParameter.getDefault(): IrExpressionBody? {
            return defaultValue
                ?: (this.parent as? IrSimpleFunction)?.overriddenSymbols
                    ?.map { it.owner.valueParameters[this.index].getDefault() }
                    ?.firstNotNullResult { it }
        }

        val valueArguments = (0 until expression.valueArgumentsCount).map { expression.getValueArgument(it) }
        val defaultValues = (if (receiverAsFirstArgument.isNotEmpty()) listOf(null) else listOf()) + // TODO fix this
                irFunction.valueParameters.map { expression.symbol.owner.valueParameters[it.index].getDefault()?.expression }

        for (i in valueArguments.indices.reversed()) {
            callStack.addInstruction(SimpleInstruction(valueParametersSymbols[i].owner))
            val arg = valueArguments[i] ?: defaultValues[i]
            when {
                arg != null -> callStack.addInstruction(CompoundInstruction(arg))
                else ->
                    // case when value parameter is vararg and it is missing
                    callStack.addInstruction(SimpleInstruction(IrConstImpl.constNull(UNDEFINED_OFFSET, UNDEFINED_OFFSET, expression.getVarargType(i)!!)))
            }

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
                    callStack.dropSubFrame()
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
            is IrTypeOperatorCall -> interpretTypeOperatorCall(element)
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
            is IrVararg -> interpretVararg(element)
            is IrValueParameter -> interpretValueParameter(element)
            is IrFunctionExpression -> interpretFunctionExpression(element)
            else -> TODO("${element.javaClass} not supported for interpretation")
        }
    }

    private fun MethodHandle?.invokeMethod(irFunction: IrFunction, args: List<State>) {
        this ?: return handleIntrinsicMethods(irFunction)
        val argsForMethodInvocation = irFunction.getArgsForMethodInvocation(this@IrInterpreter, this.type(), args)
        withExceptionHandler(environment) {
            val result = this.invokeWithArguments(argsForMethodInvocation)
            callStack.dropFrame()
            callStack.pushState(result.toState(result.getType(irFunction.returnType)))
        }
    }

    private fun handleIntrinsicMethods(irFunction: IrFunction) {
        BetterIntrinsicEvaluator.unwindInstructions(irFunction, environment).forEach { callStack.addInstruction(it) }
    }

    private fun interpretCall(call: IrCall) {
        val valueArguments = call.symbol.owner.valueParameters.map { callStack.popState() }.reversed()
        val extensionReceiver = call.extensionReceiver?.let { callStack.popState() }?.checkNullability(call.extensionReceiver?.type)
        var dispatchReceiver = call.dispatchReceiver?.let { callStack.popState() }?.checkNullability(call.dispatchReceiver?.type)
        val args = listOfNotNull(dispatchReceiver, extensionReceiver) + valueArguments

        val irFunction = dispatchReceiver?.getIrFunctionByIrCall(call) ?: call.symbol.owner
        dispatchReceiver = when (irFunction.parent) {
            (dispatchReceiver as? Complex)?.superWrapperClass?.irClass -> dispatchReceiver.superWrapperClass
            else -> dispatchReceiver
        }

        // TODO push expression.type and irFunction.returnType to stack to do check cast later
        callStack.dropSubFrame() // TODO check that data stack is empty
        callStack.newFrame(irFunction, listOf())
        irFunction.getDispatchReceiver()?.let { dispatchReceiver?.let { receiver -> callStack.addVariable(Variable(it, receiver)) } }
        irFunction.getExtensionReceiver()?.let { extensionReceiver?.let { receiver -> callStack.addVariable(Variable(it, receiver)) } }
        irFunction.valueParameters.forEachIndexed { i, param -> callStack.addVariable(Variable(param.symbol, valueArguments[i])) }

        irFunction.typeParameters
            .filter {
                it.isReified || irFunction.fqNameWhenAvailable.toString().let { it == "kotlin.emptyArray" || it == "kotlin.ArrayIntrinsicsKt.emptyArray" }
            }
            .forEach {
                // TODO: emptyArray check is a hack for js, because in js-ir its type parameter isn't marked as reified
                // TODO: if using KTypeState then it's class must be corresponding
                callStack.addVariable(Variable(it.symbol, KTypeState(call.getTypeArgument(it.index)!!, irBuiltIns.anyClass.owner)))
            }

//        if (dispatchReceiver?.irClass?.isLocal == true || irFunction.isLocal) {
//            valueArguments.addAll(dispatchReceiver.extractNonLocalDeclarations())
//        }
//
//        if (dispatchReceiver is Complex && irFunction.parentClassOrNull?.isInner == true) {
//            generateSequence(dispatchReceiver.outerClass) { (it.state as? Complex)?.outerClass }.forEach { valueArguments.add(it) }
//        }

        // inline only methods are not presented in lookup table, so must be interpreted instead of execution
        val isInlineOnly = irFunction.hasAnnotation(FqName("kotlin.internal.InlineOnly"))
        when {
            dispatchReceiver is Wrapper && !isInlineOnly -> dispatchReceiver.getMethod(irFunction).invokeMethod(irFunction, args)
            irFunction.hasAnnotation(evaluateIntrinsicAnnotation) -> Wrapper.getStaticMethod(irFunction).invokeMethod(irFunction, args)
            dispatchReceiver is KFunctionState && call.symbol.owner.name.asString() == "invoke" -> callStack.addInstruction(CompoundInstruction(irFunction))
            dispatchReceiver is ReflectionState -> Wrapper.getReflectionMethod(irFunction).invokeMethod(irFunction, args)
            dispatchReceiver is Primitive<*> -> calculateBuiltIns(irFunction, args) // 'is Primitive' check for js char, js long and get field for primitives
            irFunction.body == null ->
                /*irFunction.trySubstituteFunctionBody() ?: irFunction.tryCalculateLazyConst() ?:*/ calculateBuiltIns(irFunction, args)
            else -> callStack.addInstruction(CompoundInstruction(irFunction))
        }
    }

    private fun calculateBuiltIns(irFunction: IrFunction, args: List<State>) {
        val methodName = when (val property = (irFunction as? IrSimpleFunction)?.correspondingPropertySymbol) {
            null -> irFunction.name.asString()
            else -> property.owner.name.asString()
        }
        callStack.dropFrame()

        val receiverType = irFunction.dispatchReceiverParameter?.type
        val argsType = listOfNotNull(receiverType) + irFunction.valueParameters.map { it.type }
        val argsValues = args.map { it.wrap(this, calledFromBuiltIns = methodName !in setOf("plus", IrBuiltIns.OperatorNames.EQEQ)) }

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
            callStack.pushState(result.toState(result.getType(irFunction.returnType)))
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

        callStack.dropSubFrame() // TODO check that data stack is empty
        callStack.newFrame(constructor, listOf())
        callStack.addVariable(classState)
        constructor.valueParameters.forEachIndexed { i, param -> callStack.addVariable(Variable(param.symbol, valueArguments[i])) }

        if (irClass.hasAnnotation(evaluateIntrinsicAnnotation) || irClass.fqNameWhenAvailable!!.startsWith(Name.identifier("java"))) {
            return Wrapper.getConstructorMethod(constructor).invokeMethod(constructor, valueArguments)
        }

        if (irClass.defaultType.isArray() || irClass.defaultType.isPrimitiveArray()) {
            // array constructor doesn't have body so must be treated separately
            callStack.addVariable(Variable(constructor.symbol, KTypeState(constructorCall.type, irBuiltIns.anyClass.owner)))
            return handleIntrinsicMethods(constructor)
        }

        val superReceiver = when (val irStatement = constructor.body!!.statements[0]) {
            is IrTypeOperatorCall -> (irStatement.argument as IrFunctionAccessExpression).getThisReceiver() // for enums
            is IrFunctionAccessExpression -> irStatement.getThisReceiver()
            is IrBlock -> (irStatement.statements.last() as IrFunctionAccessExpression).getThisReceiver()
            else -> TODO("${irStatement::class.java} is not supported as first statement in constructor call")
        }
        callStack.addVariable(Variable(superReceiver, classState.state))

        callStack.addInstruction(CompoundInstruction(constructor))
    }

    private fun interpretValueParameter(valueParameter: IrValueParameter) {
//        val irFunction = valueParameter.parent as IrFunction
//
//        callStack.peekState()?.checkNullability(valueParameter.type, environment) {
//            val method = irFunction.getCapitalizedFileName() + "." + irFunction.fqNameWhenAvailable
//            val parameter = valueParameter.name
//            IllegalArgumentException("Parameter specified as non-null is null: method $method, parameter $parameter").handleUserException(environment)
//        }

        val result = callStack.peekState() ?: TODO("error: value argument missing")
        val state = when {
            // if vararg is empty
            result.isNull() -> listOf<Any?>().toPrimitiveStateArray((result as Primitive<*>).type)
            else -> result
        }

        //must add value argument in current stack because it can be used later as default argument
        callStack.addVariable(Variable(valueParameter.symbol, state))
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

    private fun interpretVararg(expression: IrVararg) {
        fun arrayToList(value: Any?): List<Any?> {
            return when (value) {
                is ByteArray -> value.toList()
                is CharArray -> value.toList()
                is ShortArray -> value.toList()
                is IntArray -> value.toList()
                is LongArray -> value.toList()
                is FloatArray -> value.toList()
                is DoubleArray -> value.toList()
                is BooleanArray -> value.toList()
                is Array<*> -> value.toList()
                else -> listOf(value)
            }
        }

        val args = expression.elements.flatMap {
            return@flatMap when (val result = callStack.popState()) {
                is Wrapper -> listOf(result.value)
                is Primitive<*> -> when {
                    expression.varargElementType.isArray() || expression.varargElementType.isPrimitiveArray() -> listOf(result)
                    else -> arrayToList(result.value)
                }
                is Common -> when {
                    result.irClass.defaultType.isUnsignedArray() -> arrayToList((result.fields.single().state as Primitive<*>).value)
                    else -> listOf(result.asProxy(this))
                }
                else -> listOf(result)
            }
        }.reversed()

        val array = when {
            expression.type.isUnsignedArray() -> {
                val owner = expression.type.classOrNull!!.owner
                val storageProperty = owner.declarations.filterIsInstance<IrProperty>().first { it.name.asString() == "storage" }
                val primitiveArray = args.map {
                    when (it) {
                        is Proxy -> (it.state.fields.single().state as Primitive<*>).value  // is unsigned number
                        else -> it                                                          // is primitive number
                    }
                }
                val unsignedArray = primitiveArray.toPrimitiveStateArray(storageProperty.backingField!!.type)
                Common(owner).apply { fields.add(Variable(storageProperty.symbol, unsignedArray)) }
            }
            else -> args.toPrimitiveStateArray(expression.type)
        }
        callStack.pushState(array)
    }

    private fun interpretFunctionExpression(expression: IrFunctionExpression) {
        val function = KFunctionState(expression.function, expression.type.classOrNull!!.owner)
        //TODO //if (expression.function.isLocal) function.fields.addAll(stack.getAll()) // TODO save only necessary declarations
        callStack.pushState(function)
    }

    private fun interpretTypeOperatorCall(expression: IrTypeOperatorCall) {
        val typeClassifier = expression.typeOperand.classifierOrFail
        val isReified = (typeClassifier.owner as? IrTypeParameter)?.isReified == true
        val isErased = typeClassifier.owner is IrTypeParameter && !isReified
        val typeOperand = if (isReified) (callStack.getVariable(typeClassifier).state as KTypeState).irType else expression.typeOperand

        when (expression.operator) {
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                callStack.popState()
                //getOrCreateObjectValue(irBuiltIns.unitClass.owner).check { return it } // TODO
            }
            IrTypeOperator.CAST, IrTypeOperator.IMPLICIT_CAST -> {
                if (!isErased && !callStack.peekState()!!.isSubtypeOf(typeOperand)) {
                    val convertibleClassName = callStack.popState().irClass.fqNameWhenAvailable
                    ClassCastException("$convertibleClassName cannot be cast to ${typeOperand.render()}").throwAsUserException()
                }
            }
            IrTypeOperator.SAFE_CAST -> {
                if (!isErased && !callStack.peekState()!!.isSubtypeOf(typeOperand)) {
                    callStack.popState()
                    callStack.pushState(null.toState(irBuiltIns.nothingNType))
                }
            }
            IrTypeOperator.INSTANCEOF -> {
                val isInstance = callStack.popState().isSubtypeOf(typeOperand) || isErased
                callStack.pushState(isInstance.toState(irBuiltIns.booleanType))
            }
            IrTypeOperator.NOT_INSTANCEOF -> {
                val isInstance = callStack.popState().isSubtypeOf(typeOperand) || isErased
                callStack.pushState((!isInstance).toState(irBuiltIns.booleanType))
            }
            IrTypeOperator.IMPLICIT_NOTNULL -> {

            }
            else -> TODO("${expression.operator} not implemented")
        }
    }
}
