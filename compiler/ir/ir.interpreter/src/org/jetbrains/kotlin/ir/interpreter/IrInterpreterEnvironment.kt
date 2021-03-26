/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.interpreter

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.interpreter.stack.CallStack
import org.jetbrains.kotlin.ir.interpreter.state.Complex
import org.jetbrains.kotlin.ir.symbols.IrSymbol

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
