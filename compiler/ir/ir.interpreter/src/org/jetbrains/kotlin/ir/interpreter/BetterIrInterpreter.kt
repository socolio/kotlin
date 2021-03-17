/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretBinaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretTernaryFunction
import org.jetbrains.kotlin.ir.interpreter.builtins.interpretUnaryFunction
import org.jetbrains.kotlin.ir.interpreter.exceptions.InterpreterError
import org.jetbrains.kotlin.ir.interpreter.stack.Variable
import org.jetbrains.kotlin.ir.interpreter.state.Complex
import org.jetbrains.kotlin.ir.interpreter.state.Primitive
import org.jetbrains.kotlin.ir.interpreter.state.State
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.originalKotlinType
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

internal class DataStack {
    private val stack = mutableListOf<State>()

    fun push(state: State) {
        stack.add(state)
    }

    fun pop(): State = stack.removeLast()
    fun popFirst(): State = stack.removeFirst()
    fun popLast(): State = stack.removeLast()
}

internal class CallStack {
    private val frames = mutableListOf<FrameContainer>()
    private fun getCurrentFrame() = frames.last()

    fun newFrame(instructions: List<Instruction>, asSubFrame: Boolean = false) {
        val newFrame = Frame(instructions.toMutableList())
        when {
            asSubFrame -> getCurrentFrame().addSubFrame(newFrame)
            else -> frames.add(FrameContainer(newFrame))
        }
    }

    fun dropFrame() {
        frames.removeLast()
    }

    fun isEmpty() = frames.isEmpty() || (frames.size == 1 && frames.first().isEmpty())

    fun addInstruction(instruction: Instruction) {
        getCurrentFrame().addInstruction(instruction)
    }

    fun popInstruction(): Instruction {
        if (getCurrentFrame().isEmpty()) dropFrame()
        return getCurrentFrame().popInstruction()
    }

    fun pushState(state: State) {
        getCurrentFrame().pushState(state)
    }

    fun popFirstState(): State = getCurrentFrame().popFirstState()
    fun popLastState(): State = getCurrentFrame().popLastState()

    fun addVariable(variable: Variable) {
        getCurrentFrame().addVariable(variable)
    }

    fun getVariable(symbol: IrSymbol): Variable = getCurrentFrame().getVariable(symbol)
}

private class FrameContainer(frame: Frame) {
    private val innerStack = mutableListOf(frame)
    private fun getCurrentFrame() = innerStack.last()

    fun addSubFrame(frame: Frame) {
        innerStack.add(frame)
    }

    fun removeSubFrame() {
        innerStack.removeLast()
    }

    fun isEmpty() = innerStack.isEmpty() || (innerStack.size == 1 && innerStack.first().isEmpty())

    fun addInstruction(instruction: Instruction) {
        getCurrentFrame().pushInstruction(instruction)
    }

    fun popInstruction(): Instruction {
        return getCurrentFrame().popInstruction()
    }

    fun pushState(state: State) {
        getCurrentFrame().pushState(state)
    }

    fun popFirstState(): State = getCurrentFrame().popFirstState()
    fun popLastState(): State = getCurrentFrame().popLastState()

    fun addVariable(variable: Variable) {
        getCurrentFrame().addVariable(variable)
    }

    fun getVariable(symbol: IrSymbol): Variable {
        return innerStack.firstNotNullResult { it.getVariable(symbol) }
            ?: throw InterpreterError("$symbol not found") // TODO better message
    }

   // fun getAll() = innerStack.flatMap { it.getAll() }
}

internal class Frame(private val instructions: MutableList<Instruction>) {
    private val memory = mutableListOf<Variable>()
    private val dataStack = DataStack()

    fun isEmpty() = instructions.isEmpty()

    fun pushInstruction(instruction: Instruction) {
        instructions.add(0, instruction)
    }

    fun popInstruction(): Instruction {
        return instructions.removeFirst()
    }

    fun pushState(state: State) {
        dataStack.push(state)
    }

    fun popFirstState(): State = dataStack.popFirst()
    fun popLastState(): State = dataStack.popLast()

    fun addVariable(variable: Variable) {
        memory += variable
    }

    fun getVariable(symbol: IrSymbol): Variable = memory.first { it.symbol == symbol }
}

internal interface Instruction {
    val element: IrElement?
}
internal inline class CompoundInstruction(override val element: IrElement?) : Instruction // must unwind first
internal inline class SimpleInstruction(override val element: IrElement) : Instruction   // must interpret as is

class BetterIrInterpreter(val irBuiltIns: IrBuiltIns, private val bodyMap: Map<IdSignature, IrBody> = emptyMap()) {
    private val callStack = CallStack()
    //private val dataStack = DataStack()

    fun interpret(expression: IrExpression, file: IrFile? = null): IrExpression {
        callStack.newFrame(listOf(CompoundInstruction(expression)))

        while (!callStack.isEmpty()) {
            when (val instruction = callStack.popInstruction()) {
                is CompoundInstruction -> unwindInstruction(instruction)
                is SimpleInstruction -> interpret(instruction.element)
            }
        }

        return callStack.popFirstState().toIrExpression(expression).apply { callStack.dropFrame() }
    }

    private fun unwindInstruction(instruction: CompoundInstruction) {
        when (val element = instruction.element) {
            null -> return
            is IrCall -> {
                val instructions = mutableListOf<Instruction>()
                instructions += CompoundInstruction(element.dispatchReceiver)
                instructions += CompoundInstruction(element.extensionReceiver)
                instructions.addAll((0 until element.valueArgumentsCount).map { CompoundInstruction(element.getValueArgument(it)) })
                instructions += SimpleInstruction(element)
                callStack.newFrame(instructions)
            }
            is IrReturn -> {
                callStack.addInstruction(SimpleInstruction(element))
                callStack.addInstruction(CompoundInstruction(element.value))
                //element.value
                //element // return + drop frame
            }
            is IrConst<*> -> callStack.addInstruction(SimpleInstruction(element))
            is IrWhen -> {
                // new sub frame to drop it after

                element.branches
            }
            is IrBranch -> {
                element.condition
                element // check if true
                // if true -> drop everything else and add result to stack
                element.result
                // else go next
            }
            is IrBlock -> {
                // new sub frame
                element.statements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) }
            }
            is IrBody -> {
                // new sub frame
                element.statements.reversed().forEach { callStack.addInstruction(CompoundInstruction(it)) }
            }
            is IrTry -> {
                element.tryResult
                element.catches.forEach { }
                element.finallyExpression
            }
            is IrCatch -> {
                // if exception
                element.catchParameter // check here
                // if true -> pop and
                element.result
            }
            else -> TODO("${element.javaClass} not supported")
        }
    }

    private fun interpret(element: IrElement) {
        when (element) {
            is IrCall -> interpretCall(element)
            is IrConst<*> -> {
//                dataStack.push(element.toPrimitive())
                callStack.pushState(element.toPrimitive())
            }
            is IrReturn -> {
                val result = callStack.popLastState()
                callStack.dropFrame()
                callStack.pushState(result)
            }
        }
    }

    private fun interpretCall(call: IrCall) {
        var dispatchReceiver = call.dispatchReceiver?.let { callStack.popFirstState() }//?.checkNullability(call.dispatchReceiver?.type)
        val extensionReceiver = call.extensionReceiver?.let { callStack.popFirstState() }//?.checkNullability(call.extensionReceiver?.type)

        val irFunction = dispatchReceiver?.getIrFunctionByIrCall(call) ?: call.symbol.owner
        dispatchReceiver = when (irFunction.parent) {
            (dispatchReceiver as? Complex)?.superWrapperClass?.irClass -> dispatchReceiver.superWrapperClass
            else -> dispatchReceiver
        }

        irFunction.getDispatchReceiver()?.let { dispatchReceiver?.let { receiver -> callStack.addVariable(Variable(it, receiver)) } }
        irFunction.getExtensionReceiver()?.let { extensionReceiver?.let { receiver -> callStack.addVariable(Variable(it, receiver)) } }
        irFunction.valueParameters.forEach { callStack.addVariable(Variable(it.symbol, callStack.popFirstState())) }

        when {
            irFunction.body == null -> calculateBuiltIns(irFunction)
            else -> unwindInstruction(CompoundInstruction(irFunction.body))
        }
    }

    private fun calculateBuiltIns(irFunction: IrFunction) {
        val methodName = when (val property = (irFunction as? IrSimpleFunction)?.correspondingPropertySymbol) {
            null -> irFunction.name.asString()
            else -> property.owner.name.asString()
        }
        val args = listOf(callStack.getVariable(irFunction.dispatchReceiverParameter!!.symbol).state, callStack.getVariable(irFunction.valueParameters.single().symbol).state)

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
        val result = withExceptionHandler {
            when (argsType.size) {
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
        }
        // TODO check "result is Unit"
        callStack.dropFrame()
        callStack.pushState(result.toState(irFunction.returnType))
    }
}
