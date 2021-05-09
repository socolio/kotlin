/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual.resourcemanagement

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentHashMap
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextCombiner
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.handleCoeffectContext
import org.jetbrains.kotlin.fir.contracts.description.ConeValueParameterReference
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.contracts.createArgumentsMapping
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

object ResourceManagementCoeffectFamily : CoeffectFamily {
    override val id = "Resource Management"
    override val emptyContext = ResourceManagementContext(persistentMapOf())
    override val combiner = ResourceManagementCoeffectContextCombiner
}

data class ResourceManagementContext(val states: PersistentMap<AbstractFirBasedSymbol<*>, Boolean>) : CoeffectContext

object ResourceManagementCoeffectContextCombiner : CoeffectContextCombiner {
    override fun merge(left: CoeffectContext, right: CoeffectContext): CoeffectContext {
        if (left !is ResourceManagementContext || right !is ResourceManagementContext) throw AssertionError()
        val states = mutableMapOf<AbstractFirBasedSymbol<*>, Boolean>()
        for (key in left.states.keys + right.states.keys) {
            val state1 = left.states[key]
            val state2 = right.states[key]
            if (state1 != null && state1 == state2) states[key] = state1
        }
        return ResourceManagementContext(states.toPersistentHashMap())
    }
}

private fun FirFunction<*>.getParameterSymbol(index: Int): AbstractFirBasedSymbol<*> {
    return if (index == -1) this.symbol else this.valueParameters[index].symbol
}

fun requireOpenEffectContextHandler(resource: ConeValueParameterReference) = handleCoeffectContext {
    family = ResourceManagementCoeffectFamily

    onOwnerCall {
        val mapping = createArgumentsMapping(firElement) ?: return@onOwnerCall
        val resourceSymbol = mapping[resource.parameterIndex + 1]?.toResolvedCallableSymbol() ?: return@onOwnerCall
        actions {
            verifiers += ResourceManagementContextVerifier(resourceSymbol, shouldBeOpen = true)
        }
    }

    onOwnerEnter {
        val resourceSymbol = firElement.getParameterSymbol(resource.parameterIndex)
        actions {
            modifiers += ResourceManagementContextProvider(resourceSymbol, isOpen = true)
        }
    }

    onOwnerExit {
        val resourceSymbol = firElement.getParameterSymbol(resource.parameterIndex)
        actions {
            verifiers += ResourceManagementContextVerifier(resourceSymbol, shouldBeOpen = true)
        }
    }
}

fun closesResourceEffectContextHandler(resource: ConeValueParameterReference) = handleCoeffectContext {
    family = ResourceManagementCoeffectFamily

    onOwnerCall {
        val mapping = createArgumentsMapping(firElement) ?: return@onOwnerCall
        val resourceSymbol = mapping[resource.parameterIndex + 1]?.toResolvedCallableSymbol() ?: return@onOwnerCall
        actions {
            verifiers += ResourceManagementContextVerifier(resourceSymbol, shouldBeOpen = true)
            modifiers += ResourceManagementContextProvider(resourceSymbol, isOpen = false)
        }
    }

    onOwnerEnter {
        val resourceSymbol = firElement.getParameterSymbol(resource.parameterIndex)
        actions {
            modifiers += ResourceManagementContextProvider(resourceSymbol, isOpen = true)
        }
    }

    onOwnerExit {
        val resourceSymbol = firElement.getParameterSymbol(resource.parameterIndex)
        actions {
            verifiers += ResourceManagementContextVerifier(resourceSymbol, shouldBeOpen = false)
        }
    }
}