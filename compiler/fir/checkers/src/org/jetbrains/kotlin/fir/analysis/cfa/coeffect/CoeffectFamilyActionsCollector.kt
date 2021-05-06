/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraphVisitor

abstract class CoeffectFamilyActionsCollector : ControlFlowGraphVisitor<Unit, CoeffectActionsOnNodes>() {

    override fun visitNode(node: CFGNode<*>, data: CoeffectActionsOnNodes) {}
}