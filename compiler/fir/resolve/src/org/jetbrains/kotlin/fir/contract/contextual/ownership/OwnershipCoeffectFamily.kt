/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual.ownership

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentHashMap

import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextCombiner
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.handleCoeffectContext
import org.jetbrains.kotlin.fir.contracts.description.ConeValueParameterReference
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.contracts.createArgumentsMapping
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

object OwnershipCoeffectFamily : CoeffectFamily {
    override val id = "Resource Management"
    override val emptyContext = OwnershipContext(persistentMapOf())
    override val combiner = OwnershipCoeffectContextCombiner
}

data class OwnershipContext(val states: PersistentMap<AbstractFirBasedSymbol<*>, OwnershipLinkState>) : CoeffectContext

enum class OwnershipLinkState {
    IMMUTABLE,
    MUTABLE
}

object OwnershipCoeffectContextCombiner : CoeffectContextCombiner {
    override fun merge(left: CoeffectContext, right: CoeffectContext): CoeffectContext {
        if (left !is OwnershipContext || right !is OwnershipContext) throw AssertionError()
        val states = mutableMapOf<AbstractFirBasedSymbol<*>, OwnershipLinkState>()

        for (link in left.states.keys + right.states.keys) {
            val state1 = left.states[link]
            val state2 = right.states[link]

            if (state1 != null && state2 != null) {
                states[link] = if (state1 < state2) state1 else state2
            }
        }

        return OwnershipContext(states.toPersistentHashMap())
    }
}

fun borrowsLinkEffectContextHandler(link: ConeValueParameterReference) = handleCoeffectContext {
    family = OwnershipCoeffectFamily

    onOwnerCall {
        val mapping = createArgumentsMapping(firElement) ?: return@onOwnerCall
        val linkSymbol = mapping[link.parameterIndex]?.toResolvedCallableSymbol() ?: return@onOwnerCall
        actions {
            verifiers += OwnershipContextVerifier(linkSymbol)
        }
        symbolNotLeaked(linkSymbol)
    }

    onOwnerEnter {
        val linkSymbol = firElement.getParameterSymbol(link.parameterIndex)
        actions {
            modifiers += OwnershipContextProvider(linkSymbol, OwnershipLinkState.MUTABLE)
        }
    }

    onOwnerExit {
        val linkSymbol = firElement.getParameterSymbol(link.parameterIndex)
        actions {
            verifiers += OwnershipContextVerifier(linkSymbol)
        }
    }
}

fun consumesLinkEffectContextHandler(link: ConeValueParameterReference) = handleCoeffectContext {
    family = OwnershipCoeffectFamily

    onOwnerCall {
        val mapping = createArgumentsMapping(firElement) ?: return@onOwnerCall
        val linkSymbol = mapping[link.parameterIndex]?.toResolvedCallableSymbol() ?: return@onOwnerCall
        actions {
            verifiers += OwnershipContextVerifier(linkSymbol)
            modifiers += OwnershipContextProvider(linkSymbol, OwnershipLinkState.IMMUTABLE)
        }
        symbolNotLeaked(linkSymbol)
    }

    onOwnerEnter {
        val linkSymbol = firElement.getParameterSymbol(link.parameterIndex)
        actions {
            modifiers += OwnershipContextProvider(linkSymbol, OwnershipLinkState.MUTABLE)
        }
    }
}
