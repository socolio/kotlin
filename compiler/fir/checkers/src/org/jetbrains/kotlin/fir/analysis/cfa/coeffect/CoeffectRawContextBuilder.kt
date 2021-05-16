/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.cfa.LeakedSymbolFinder
import org.jetbrains.kotlin.fir.analysis.cfa.SymbolLegalUsageChecker
import org.jetbrains.kotlin.fir.analysis.cfa.SymbolUsageContext
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextActions
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectFamilyContextHandler
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectNodeContextBuilder
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.ConeCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.ConeLambdaCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.effects
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirContractDescriptionOwner
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.isLambda
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.resolve.isInvoke
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

class CoeffectRawContextBuilder(
    rootFunction: FirFunction<*>,
    private val familyAnalyzers: Map<CoeffectFamily, CoeffectFamilyAnalyzer>,
    private val lambdaToOwnerFunction: Map<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>
) : ControlFlowGraphVisitor<Unit, CoeffectRawContextOnNodes>() {

    private val leakTrackedSymbols: MutableMap<AbstractFirBasedSymbol<*>, MutableList<SymbolLegalUsageChecker>> = mutableMapOf()
    private val symbolLeaksFinder = LeakedSymbolFinder(rootFunction)
    private val symbolUsageContext = SymbolUsageContext(leakTrackedSymbols)

    private fun shouldHandleFamily(family: CoeffectFamily): Boolean = family in familyAnalyzers

    override fun visitNode(node: CFGNode<*>, data: CoeffectRawContextOnNodes) {
        val contextBuilder = CoeffectContextBuilderImpl(node, data)
        for (familyAnalyzer in familyAnalyzers.values) {
            val familyActionsCollector = familyAnalyzer.actionsCollector ?: continue
            node.accept(familyActionsCollector, contextBuilder)
        }
        node.accept(symbolLeaksFinder, symbolUsageContext)
    }

    override fun visitFunctionCallNode(node: FunctionCallNode, data: CoeffectRawContextOnNodes) {
        processFunctionCallNode(node, data)
        super.visitFunctionCallNode(node, data)
    }

    override fun visitFunctionEnterNode(node: FunctionEnterNode, data: CoeffectRawContextOnNodes) {
        processFunctionBoundaryNode(node, data) { onOwnerEnter(it) }
        super.visitFunctionEnterNode(node, data)
    }

    override fun visitFunctionExitNode(node: FunctionExitNode, data: CoeffectRawContextOnNodes) {
        processFunctionBoundaryNode(node, data) { onOwnerExit(it) }
        super.visitFunctionExitNode(node, data)
    }

    private fun processFunctionCallNode(node: FunctionCallNode, data: CoeffectRawContextOnNodes) {
        val functionSymbol = node.fir.toResolvedCallableSymbol() ?: return

        if (functionSymbol.callableId.isInvoke()) {
            val receiverSymbol = node.fir.explicitReceiver?.toResolvedCallableSymbol() ?: return
            val function = (node.owner.enterNode as? FunctionEnterNode)?.fir ?: return

            collectLambdaCoeffectActions(node, function, receiverSymbol, data) { onOwnerCall(it) }
        } else collectCoeffectActions(node, data) { onOwnerCall(it) }
    }

    private inline fun processFunctionBoundaryNode(
        node: CFGNode<FirFunction<*>>,
        data: CoeffectRawContextOnNodes,
        extractor: CoeffectFamilyContextHandler.(CoeffectNodeContextBuilder<FirFunction<*>>) -> Unit
    ) {
        val function = node.fir
        if (function.isLambda()) {
            val (calledFunction, lambdaSymbol) = lambdaToOwnerFunction[function] ?: return
            collectLambdaCoeffectActions(node, calledFunction, lambdaSymbol, data, extractor)
        } else collectCoeffectActions(node, data, extractor)
    }

    private inline fun <E : FirElement> collectCoeffectActions(
        node: CFGNode<E>,
        data: CoeffectRawContextOnNodes,
        extractor: CoeffectFamilyContextHandler.(CoeffectNodeContextBuilder<E>) -> Unit
    ) {
        val effects = node.fir.contractDescription?.effects?.filterIsInstance<ConeCoeffectEffectDeclaration>()
        if (effects.isNullOrEmpty()) return

        for (effect in effects) {
            if (!shouldHandleFamily(effect.family)) continue
            val contextHandler = effect.contextHandler
            val contextBuilder = CoeffectContextBuilderImpl(node, data)
            contextHandler.extractor(contextBuilder)
        }
    }

    private inline fun <E : FirElement> collectLambdaCoeffectActions(
        node: CFGNode<E>,
        function: FirFunction<*>,
        lambdaSymbol: AbstractFirBasedSymbol<*>,
        data: CoeffectRawContextOnNodes,
        extractor: CoeffectFamilyContextHandler.(CoeffectNodeContextBuilder<E>) -> Unit
    ) {
        val effects = function.contractDescription?.effects?.filterIsInstance<ConeLambdaCoeffectEffectDeclaration>()
        if (effects.isNullOrEmpty()) return

        for (effect in effects) {
            if (!shouldHandleFamily(effect.family)) continue
            if (function.valueParameters.getOrNull(effect.lambda.parameterIndex)?.symbol != lambdaSymbol) continue
            val contextHandler = effect.contextHandler
            val contextBuilder = CoeffectContextBuilderImpl(node, data)
            contextHandler.extractor(contextBuilder)
        }
    }

    private val FirElement.contractDescription: FirContractDescription?
        get() = when (this) {
            is FirFunction<*> -> (this as? FirContractDescriptionOwner)?.contractDescription
            is FirFunctionCall -> (this.toResolvedCallableSymbol()?.fir as? FirContractDescriptionOwner)?.contractDescription
            else -> null
        }

    private inner class CoeffectContextBuilderImpl<E : FirElement>(
        private val node: CFGNode<E>,
        private val rawContext: CoeffectRawContextOnNodes
    ) : CoeffectNodeContextBuilder<E> {

        override val firElement: E get() = node.fir

        override fun addActions(actions: CoeffectContextActions) {
            rawContext[node] = actions
        }

        override fun trackSymbolForLeaking(
            family: CoeffectFamily,
            symbol: AbstractFirBasedSymbol<*>,
            onLeakActions: () -> CoeffectContextActions
        ) {
            val familyAnalyzer = familyAnalyzers[family] ?: return
            val usageChecker = familyAnalyzer.trackSymbolForIllegalUsage(rawContext, onLeakActions) ?: return
            leakTrackedSymbols.getOrPut(symbol, ::mutableListOf) += usageChecker
        }

        override fun markSymbolAsNotLeaked(functionCall: FirFunctionCall, symbol: AbstractFirBasedSymbol<*>, family: CoeffectFamily) {
            if (symbol !in leakTrackedSymbols) return
            rawContext.markSymbolPassAsControlled(functionCall, symbol, family)
        }
    }
}

class CoeffectRawContextOnNodes {

    private val data: MutableMap<CFGNode<*>, MutableMap<CoeffectFamily, CoeffectContextActions>> = mutableMapOf()
    private val controlledSymbolPasses: MutableSet<ControlledSymbolPass> = mutableSetOf()

    var hasVerifiers: Boolean = false
        private set

    operator fun get(node: CFGNode<*>): MutableMap<CoeffectFamily, CoeffectContextActions>? = data[node]

    operator fun set(node: CFGNode<*>, actions: CoeffectContextActions) {
        val family = actions.family ?: return
        val nodeActions = data.getOrPut(node, ::mutableMapOf)

        when (val familyActions = nodeActions[family]) {
            null -> nodeActions[family] = actions
            else -> nodeActions[family] = familyActions.add(actions)
        }

        hasVerifiers = hasVerifiers || actions.verifiers.isNotEmpty()
    }

    fun markSymbolPassAsControlled(functionCall: FirFunctionCall, symbol: AbstractFirBasedSymbol<*>, family: CoeffectFamily) {
        controlledSymbolPasses += ControlledSymbolPass(functionCall, symbol, family)
    }

    fun isSymbolPassControlled(functionCall: FirFunctionCall, symbol: AbstractFirBasedSymbol<*>, family: CoeffectFamily): Boolean =
        ControlledSymbolPass(functionCall, symbol, family) in controlledSymbolPasses

    private data class ControlledSymbolPass(
        val functionCall: FirFunctionCall,
        val symbol: AbstractFirBasedSymbol<*>,
        val family: CoeffectFamily
    )

    operator fun iterator() = data.iterator()
}