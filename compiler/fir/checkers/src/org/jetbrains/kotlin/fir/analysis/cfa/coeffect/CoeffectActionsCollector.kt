/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextActions
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectActionExtractors
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

class CoeffectActionsCollector(
    private val familyAnalyzers: Map<CoeffectFamily, CoeffectFamilyAnalyzer>,
    private val lambdaToOwnerFunction: Map<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>
) : ControlFlowGraphVisitor<Unit, CoeffectActionsOnNodes>() {

    fun shouldHandleFamily(family: CoeffectFamily): Boolean = family in familyAnalyzers

    override fun visitNode(node: CFGNode<*>, data: CoeffectActionsOnNodes) {
        for (familyAnalyzer in familyAnalyzers.values) {
            val familyActionsCollector = familyAnalyzer.actionsCollector ?: continue
            node.accept(familyActionsCollector, data)
        }
    }

    override fun visitFunctionCallNode(node: FunctionCallNode, data: CoeffectActionsOnNodes) {
        processFunctionCallNode(node, data)
        super.visitFunctionCallNode(node, data)
    }

    override fun visitFunctionEnterNode(node: FunctionEnterNode, data: CoeffectActionsOnNodes) {
        processFunctionBoundaryNode(node, data) { onOwnerEnter?.extractActions(node.fir) }
        super.visitFunctionEnterNode(node, data)
    }

    override fun visitFunctionExitNode(node: FunctionExitNode, data: CoeffectActionsOnNodes) {
        processFunctionBoundaryNode(node, data) { onOwnerExit?.extractActions(node.fir) }
        super.visitFunctionExitNode(node, data)
    }

    private fun processFunctionCallNode(node: FunctionCallNode, data: CoeffectActionsOnNodes) {
        val functionSymbol = node.fir.toResolvedCallableSymbol() ?: return

        if (functionSymbol.callableId.isInvoke()) {
            val receiverSymbol = node.fir.explicitReceiver?.toResolvedCallableSymbol() ?: return
            val function = (node.owner.enterNode as? FunctionEnterNode)?.fir ?: return

            collectLambdaCoeffectActions(node, function, receiverSymbol, data) { onOwnerCall?.extractActions(node.fir) }
        } else collectCoeffectActions(node, data) { onOwnerCall?.extractActions(node.fir) }
    }

    private inline fun processFunctionBoundaryNode(
        node: CFGNode<FirFunction<*>>,
        data: CoeffectActionsOnNodes,
        extractor: CoeffectActionExtractors.() -> CoeffectContextActions?
    ) {
        val function = node.fir
        if (function.isLambda()) {
            val (calledFunction, lambdaSymbol) = lambdaToOwnerFunction[function] ?: return
            collectLambdaCoeffectActions(node, calledFunction, lambdaSymbol, data, extractor)
        } else collectCoeffectActions(node, data, extractor)
    }

    private inline fun collectCoeffectActions(
        node: CFGNode<*>,
        data: CoeffectActionsOnNodes,
        extractor: CoeffectActionExtractors.() -> CoeffectContextActions?
    ) {
        val effects = node.fir.contractDescription?.effects?.filterIsInstance<ConeCoeffectEffectDeclaration>()
        if (effects.isNullOrEmpty()) return

        for (effect in effects) {
            if (!shouldHandleFamily(effect.family)) continue
            val actions = extractor(effect.actionExtractors) ?: continue
            data[node] = actions
        }
    }

    private inline fun collectLambdaCoeffectActions(
        node: CFGNode<*>,
        function: FirFunction<*>,
        lambdaSymbol: AbstractFirBasedSymbol<*>,
        data: CoeffectActionsOnNodes,
        extractor: CoeffectActionExtractors.() -> CoeffectContextActions?
    ) {
        val effects = function.contractDescription?.effects?.filterIsInstance<ConeLambdaCoeffectEffectDeclaration>()
        if (effects.isNullOrEmpty()) return

        for (effect in effects) {
            if (!shouldHandleFamily(effect.family)) continue
            if (function.valueParameters.getOrNull(effect.lambda.parameterIndex)?.symbol != lambdaSymbol) continue
            val actions = extractor(effect.actionExtractors) ?: continue
            data[node] = actions
        }
    }

    private val FirElement.contractDescription: FirContractDescription?
        get() = when (this) {
            is FirFunction<*> -> (this as? FirContractDescriptionOwner)?.contractDescription
            is FirFunctionCall -> (this.toResolvedCallableSymbol()?.fir as? FirContractDescriptionOwner)?.contractDescription
            else -> null
        }
}

class CoeffectActionsOnNodes(private val data: MutableMap<CFGNode<*>, MutableMap<CoeffectFamily, CoeffectContextActions>>) {

    var hasVerifiers: Boolean = false
        private set

    constructor() : this(mutableMapOf())

    operator fun get(node: CFGNode<*>): MutableMap<CoeffectFamily, CoeffectContextActions>? = data[node]

    operator fun set(node: CFGNode<*>, actions: CoeffectContextActions) {
        val family = actions.family ?: return
        val nodeActions = data.getOrPut(node, ::mutableMapOf)

        when (val familyActions = nodeActions[family]) {
            null -> nodeActions[family] = actions
            else -> familyActions.add(actions)
        }

        hasVerifiers = hasVerifiers || actions.verifiers.isNotEmpty()
    }

    operator fun iterator() = data.iterator()
}