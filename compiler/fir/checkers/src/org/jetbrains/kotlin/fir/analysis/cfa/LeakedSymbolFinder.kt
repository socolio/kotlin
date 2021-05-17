/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.isInPlaceLambda
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

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

    override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: SymbolUsageContext) {
        data.checkExpressionForLeakedSymbols(node, node.fir.initializer) { isLegalVariableInitialization(node, it) }
    }

    override fun visitVariableAssignmentNode(node: VariableAssignmentNode, data: SymbolUsageContext) {
        data.checkExpressionForLeakedSymbols(node, node.fir.rValue) { isLegalAssignment(node, it) }

        val accessSymbol = (node.fir.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirPropertySymbol ?: return
        checkQualifiedAccess(node, node.fir, data) { symbol, isExtension ->
            isLegalPropertyAccess(node.fir, symbol, accessSymbol, isExtension)
        }
    }

    override fun visitFunctionCallNode(node: FunctionCallNode, data: SymbolUsageContext) {
        val functionSymbol = node.fir.toResolvedCallableSymbol() as? FirFunctionSymbol<*>?

        checkQualifiedAccess(node, node.fir, data) { symbol, isExtension ->
            isLegalFunctionReceiver(node, functionSymbol, symbol, isExtension)
        }

        for (arg in node.fir.argumentList.arguments) {
            data.checkExpressionForLeakedSymbols(node, arg) { symbol ->
                isLegalArgument(node, functionSymbol, arg, symbol)
            }
        }
    }

    override fun visitQualifiedAccessNode(node: QualifiedAccessNode, data: SymbolUsageContext) {
        val accessSymbol = node.fir.toResolvedCallableSymbol() as? FirPropertySymbol ?: return

        checkQualifiedAccess(node, node.fir, data) { symbol, isExtension ->
            isLegalPropertyAccess(node.fir, symbol, accessSymbol, isExtension)
        }
    }

    private inline fun checkQualifiedAccess(
        node: CFGNode<*>,
        qualifiedAccess: FirQualifiedAccess,
        data: SymbolUsageContext,
        isLegalUsage: SymbolLegalUsageChecker.(AbstractFirBasedSymbol<*>, Boolean) -> Boolean
    ) {
        if (qualifiedAccess.dispatchReceiver !is FirNoReceiverExpression) {
            val dispatchCallSource = qualifiedAccess.dispatchReceiver.source ?: qualifiedAccess.source
            data.checkExpressionForLeakedSymbols(node, qualifiedAccess.dispatchReceiver, dispatchCallSource) { symbol ->
                isLegalUsage(symbol, false)
            }
        }

        if (qualifiedAccess.extensionReceiver !is FirNoReceiverExpression) {
            val extensionCallSource = qualifiedAccess.extensionReceiver.source ?: qualifiedAccess.source
            data.checkExpressionForLeakedSymbols(node, qualifiedAccess.extensionReceiver, extensionCallSource) { symbol ->
                isLegalUsage(symbol, true)
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
        val reference = (fir as? FirResolvable)?.calleeReference ?: return
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

    fun isLegalPropertyAccess(
        access: FirQualifiedAccess,
        symbol: AbstractFirBasedSymbol<*>,
        propertySymbol: FirPropertySymbol,
        isExtension: Boolean
    ): Boolean = true

    fun isLegalFunctionReceiver(
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