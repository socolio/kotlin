/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.kotlin.fir.analysis.cfa.ControlFlowInfo
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextModifier
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraphVisitor

class CoeffectContextResolver(
    val actionsOnNodes: CoeffectActionsOnNodes,
) : ControlFlowGraphVisitor<CoeffectContextOnNodes, Pair<Collection<CoeffectContextOnNodes>, CoeffectContextOnNodes?>>() {

    override fun visitNode(
        node: CFGNode<*>,
        data: Pair<Collection<CoeffectContextOnNodes>, CoeffectContextOnNodes?>
    ): CoeffectContextOnNodes {
        val (incomingData, prevData) = data
        var dataForNode = if (incomingData.isEmpty()) CoeffectContextOnNodes.EMPTY else incomingData.reduce(CoeffectContextOnNodes::merge)
        val nodeActions = actionsOnNodes[node] ?: return dataForNode

        nodeActions.forEach { actions ->
            actions.providers.forEach { dataForNode = dataForNode.tryModifyContext(it, prevData) }
            actions.cleaners.forEach { dataForNode = dataForNode.tryModifyContext(it, prevData) }
        }

        return dataForNode
    }
}

class CoeffectContextOnNodes(
    map: PersistentMap<CoeffectFamily, Pair<CoeffectContext, Boolean>> = persistentMapOf(),
) : ControlFlowInfo<CoeffectContextOnNodes, CoeffectFamily, Pair<CoeffectContext, Boolean>>(map) {

    companion object {
        val EMPTY = CoeffectContextOnNodes()
    }

    override val constructor: (PersistentMap<CoeffectFamily, Pair<CoeffectContext, Boolean>>) -> CoeffectContextOnNodes =
        ::CoeffectContextOnNodes

    override fun get(key: CoeffectFamily): Pair<CoeffectContext, Boolean> = super.get(key) ?: (key.emptyContext to false)

    fun merge(other: CoeffectContextOnNodes): CoeffectContextOnNodes {
        var result = this
        for (family in keys.union(other.keys)) {
            val (left, leftFixed) = this[family]
            val (right, rightFixed) = other[family]
            val context = family.combiner.merge(left, right)
            val contextFixed = leftFixed && rightFixed
            result = result.put(family, context to contextFixed)
        }
        return result
    }

    fun isFixed(family: CoeffectFamily): Boolean = super.get(family)?.second ?: false

    fun tryModifyContext(modifier: CoeffectContextModifier?, prevData: CoeffectContextOnNodes?): CoeffectContextOnNodes {
        if (modifier == null) return this
        val (context, _) = this[modifier.family]

        val prevContext = prevData?.get(modifier.family)
        if (prevContext != null && prevContext.second) {
            return when {
                prevContext.first === context -> this
                else -> put(modifier.family, prevContext)
            }
        }

        val newContext = modifier.modifyContext(context)
        val newContextFixed = prevContext != null && newContext == prevContext.first
        return put(modifier.family, newContext to newContextFixed)
    }
}