/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretBinaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretTernaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretUnaryFunction
import org.jetbrains.kotlin.ir.interpreter.exceptions.InterpreterError
import org.jetbrains.kotlin.ir.interpreter.stack.Variable
import org.jetbrains.kotlin.ir.interpreter.state.*
import org.jetbrains.kotlin.ir.interpreter.state.Complex
import org.jetbrains.kotlin.ir.interpreter.state.ExceptionState
import org.jetbrains.kotlin.ir.interpreter.state.Primitive
import org.jetbrains.kotlin.ir.interpreter.state.State
import org.jetbrains.kotlin.ir.interpreter.state.asBoolean
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

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
    private val mapOfEnums = mutableMapOf<IrSymbol, Complex>()
    private val mapOfObjects = mutableMapOf<IrSymbol, Complex>()
}

class BetterIrInterpreter(val irBuiltIns: IrBuiltIns, private val bodyMap: Map<IdSignature, IrBody> = emptyMap()) {
    private val environment = IrInterpreterEnvironment(irBuiltIns, CallStack())
    private val callStack: CallStack
        get() = environment.callStack

    fun interpret(expression: IrExpression, file: IrFile? = null): IrExpression {
        callStack.newFrame(expression, listOf(CompoundInstruction(expression)), file)

        while (!callStack.hasNoInstructions()) {
            when (val instruction = callStack.popInstruction()) {
                is CompoundInstruction -> unwindInstruction(instruction)
                is SimpleInstruction -> interpret(instruction.element)
            }
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
            is IrCall -> {
                callStack.addInstruction(SimpleInstruction(element))
                (0 until element.valueArgumentsCount).map { CompoundInstruction(element.getValueArgument(it)) }.reversed().forEach { callStack.addInstruction(it) }
                callStack.addInstruction(CompoundInstruction(element.extensionReceiver))
                callStack.addInstruction(CompoundInstruction(element.dispatchReceiver))
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

            else -> TODO("${element.javaClass} not supported")
        }
    }

    private fun interpret(element: IrElement) {
        when (element) {
            is IrSimpleFunction -> callStack.dropFrame()
            is IrCall -> interpretCall(element)
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
                    callStack.newSubFrame(element, listOf(CompoundInstruction(element.body), CompoundInstruction(element)))
                }
            }
            is IrDoWhileLoop -> {
                val result = callStack.popState().asBoolean()
                callStack.dropSubFrame()
                if (result) {
                    callStack.newSubFrame(element, listOf(CompoundInstruction(element)))
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
            else -> unwindInstruction(CompoundInstruction(irFunction))
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

        val receiverType = irFunction.dispatchReceiverParameter?.type
        val argsType = listOfNotNull(receiverType) + irFunction.valueParameters.map { it.type }
        val argsValues = args.map { (it as Primitive<*>).value }

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
                    //"rangeTo" -> return calculateRangeTo(irFunction.returnType)
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
            callStack.dropFrame()
            callStack.pushState(result.toState(irFunction.returnType))
        }
    }
}
