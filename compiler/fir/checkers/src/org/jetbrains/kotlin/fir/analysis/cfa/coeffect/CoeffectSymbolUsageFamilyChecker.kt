/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.SymbolLegalUsageChecker
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextActions
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectNodeContextBuilder
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.FunctionCallNode
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol

open class CoeffectSymbolUsageFamilyChecker(
    val rawContextBuilder: CoeffectRawContextBuilder,
    val family: CoeffectFamily,
    val rawContext: CoeffectRawContextOnNodes,
    val onLeakHandler: CoeffectNodeContextBuilder<*>.() -> Unit
) : SymbolLegalUsageChecker {

    override fun recordSymbolLeak(node: CFGNode<*>, symbol: AbstractFirBasedSymbol<*>, source: FirSourceElement?) {
        val nodeContextBuilder = rawContextBuilder.nodeContextBuilder(node, rawContext)
        onLeakHandler(nodeContextBuilder)
    }

    override fun isLegalFunctionReceiver(
        functionCall: FunctionCallNode,
        functionSymbol: FirFunctionSymbol<*>?,
        symbol: AbstractFirBasedSymbol<*>,
        isExtension: Boolean
    ): Boolean = rawContext.isSymbolPassControlled(functionCall.fir, symbol, family)

    override fun isLegalArgument(
        functionCall: FunctionCallNode,
        functionSymbol: FirFunctionSymbol<*>?,
        arg: FirExpression,
        symbol: AbstractFirBasedSymbol<*>
    ): Boolean = rawContext.isSymbolPassControlled(functionCall.fir, symbol, family)
}