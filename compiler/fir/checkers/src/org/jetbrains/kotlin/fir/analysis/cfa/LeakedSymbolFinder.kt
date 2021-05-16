/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccess
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.isInPlaceLambda
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol

class LeakedSymbolFinder(val rootFunction: FirFunction<*>) : ControlFlowGraphVisitor<Unit, SymbolUsageContext>() {

    override fun visitNode(node: CFGNode<*>, data: SymbolUsageContext) {}

    override fun visitFunctionEnterNode(node: FunctionEnterNode, data: SymbolUsageContext) {
        data.enterScope(node.fir === rootFunction || node.fir.isInPlaceLambda())
    }

    override fun visitFunctionExitNode(node: FunctionExitNode, data: SymbolUsageContext) {
        data.exitScope()
    }

    override fun visitPropertyInitializerEnterNode(node: PropertyInitializerEnterNode, data: SymbolUsageContext) {
        data.enterScope(false)
        data.checkExpressionForLeakedSymbols(node, node.fir.initializer) { true }
    }

    override fun visitPropertyInitializerExitNode(node: PropertyInitializerExitNode, data: SymbolUsageContext) {
        data.exitScope()
    }

    override fun visitInitBlockEnterNode(node: InitBlockEnterNode, data: SymbolUsageContext) {
        data.enterScope(false)
    }

    override fun visitInitBlockExitNode(node: InitBlockExitNode, data: SymbolUsageContext) {
        data.exitScope()
    }

    override fun visitVariableAssignmentNode(node: VariableAssignmentNode, data: SymbolUsageContext) {
        data.checkExpressionForLeakedSymbols(node, node.fir.rValue) { isLegalAssignment(node, it) }
    }

    override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: SymbolUsageContext) {
        data.checkExpressionForLeakedSymbols(node, node.fir.initializer) { isLegalVariableInitialization(node, it) }
    }

    override fun visitFunctionCallNode(node: FunctionCallNode, data: SymbolUsageContext) {
        val functionSymbol = node.fir.toResolvedCallableSymbol() as? FirFunctionSymbol<*>?

        if (node.fir.dispatchReceiver !is FirNoReceiverExpression) {
            val dispatchCallSource = node.fir.dispatchReceiver.source ?: node.fir.source
            data.checkExpressionForLeakedSymbols(node, node.fir.dispatchReceiver, dispatchCallSource) { symbol ->
                isLegalReceiver(node, functionSymbol, symbol, isExtension = false)
            }
        }

        if (node.fir.extensionReceiver !is FirNoReceiverExpression) {
            val extensionCallSource = node.fir.extensionReceiver.source ?: node.fir.source
            data.checkExpressionForLeakedSymbols(node, node.fir.extensionReceiver, extensionCallSource) { symbol ->
                isLegalReceiver(node, functionSymbol, symbol, isExtension = false)
            }
        }

        for (arg in node.fir.argumentList.arguments) {
            data.checkExpressionForLeakedSymbols(node, arg) { symbol ->
                isLegalArgument(node, functionSymbol, arg, symbol)
            }
        }
    }
}

class SymbolUsageContext(val targetSymbols: Map<AbstractFirBasedSymbol<*>, List<SymbolLegalUsageChecker>>) {
    private var scopeDepth: Int = 0
    private var illegalScopeStartDepth: Int = -1

    val inIllegalScope: Boolean get() = illegalScopeStartDepth != -1

    fun enterScope(legal: Boolean) {
        scopeDepth++
        if (illegalScopeStartDepth == -1 && !legal) illegalScopeStartDepth = scopeDepth
    }

    fun exitScope() {
        if (scopeDepth == illegalScopeStartDepth) illegalScopeStartDepth = -1
        scopeDepth--
    }

    inline fun checkExpressionForLeakedSymbols(
        node: CFGNode<*>,
        fir: FirExpression?,
        source: FirSourceElement? = fir?.source,
        isLegalUsage: SymbolLegalUsageChecker.(AbstractFirBasedSymbol<*>) -> Boolean
    ) {
        if (fir == null || targetSymbols.isEmpty()) return
        val reference = (fir as? FirQualifiedAccess)?.calleeReference ?: return
        val symbol = referenceToSymbol(reference) ?: return
        val usageCheckers = targetSymbols[symbol] ?: return

        for (usageChecker in usageCheckers) {
            if (inIllegalScope || !usageChecker.isLegalUsage(symbol)) {
                usageChecker.recordSymbolLeak(node, symbol, source)
            }
        }
    }

    fun referenceToSymbol(reference: FirReference): AbstractFirBasedSymbol<*>? = when (reference) {
        is FirResolvedNamedReference -> reference.resolvedSymbol
        is FirThisReference -> reference.boundSymbol
        else -> null
    }
}

interface SymbolLegalUsageChecker {

    fun isLegalAssignment(
        assignment: VariableAssignmentNode,
        symbol: AbstractFirBasedSymbol<*>
    ): Boolean = false

    fun isLegalVariableInitialization(
        declaration: VariableDeclarationNode,
        symbol: AbstractFirBasedSymbol<*>
    ): Boolean = false

    fun isLegalReceiver(
        functionCall: FunctionCallNode,
        functionSymbol: FirFunctionSymbol<*>?,
        symbol: AbstractFirBasedSymbol<*>,
        isExtension: Boolean
    ): Boolean

    fun isLegalArgument(
        functionCall: FunctionCallNode,
        functionSymbol: FirFunctionSymbol<*>?,
        arg: FirExpression,
        symbol: AbstractFirBasedSymbol<*>
    ): Boolean

    fun recordSymbolLeak(
        node: CFGNode<*>,
        symbol: AbstractFirBasedSymbol<*>,
        source: FirSourceElement?
    )
}