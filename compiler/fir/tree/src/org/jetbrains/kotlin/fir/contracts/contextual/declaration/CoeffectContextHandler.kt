/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contracts.contextual.declaration

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextActions
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextActionsBuilder
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.coeffectActions
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

interface CoeffectNodeContextBuilder<E : FirElement> {
    val firElement: E

    fun addActions(actions: CoeffectContextActions)
    fun trackSymbolForLeaking(family: CoeffectFamily, symbol: AbstractFirBasedSymbol<*>, onLeakActions: () -> CoeffectContextActions)
    fun markSymbolAsNotLeaked(functionCall: FirFunctionCall, symbol: AbstractFirBasedSymbol<*>, family: CoeffectFamily)
}

typealias OwnerCallCoeffectContextHandler = CoeffectNodeContextBuilder<FirFunctionCall>.() -> Unit
typealias OwnerEnterCoeffectContextHandler = CoeffectNodeContextBuilder<FirFunction<*>>.() -> Unit
typealias OwnerExitCoeffectContextHandler = CoeffectNodeContextBuilder<FirFunction<*>>.() -> Unit

class CoeffectFamilyContextHandler(
    val family: CoeffectFamily,
    val onOwnerCall: OwnerCallCoeffectContextHandler,
    val onOwnerEnter: OwnerEnterCoeffectContextHandler,
    val onOwnerExit: OwnerExitCoeffectContextHandler
)

class CoeffectFamilyContextBuilder {

    var family: CoeffectFamily? = null

    private var onOwnerCall: OwnerCallCoeffectContextHandler? = null
    private var onOwnerEnter: OwnerEnterCoeffectContextHandler? = null
    private var onOwnerExit: OwnerExitCoeffectContextHandler? = null

    inline fun CoeffectNodeContextBuilder<*>.actions(block: CoeffectContextActionsBuilder.() -> Unit) {
        val actionsBuilder = CoeffectContextActionsBuilder()
        block(actionsBuilder)
        addActions(actionsBuilder.build())
    }

    fun CoeffectNodeContextBuilder<*>.onSymbolLeak(symbol: AbstractFirBasedSymbol<*>, block: CoeffectContextActionsBuilder.() -> Unit) {
        val family = family ?: throw AssertionError("Undefined coeffect family")
        trackSymbolForLeaking(family, symbol) { coeffectActions(block) }
    }

    fun CoeffectNodeContextBuilder<FirFunctionCall>.symbolNotLeaked(symbol: AbstractFirBasedSymbol<*>) {
        val family = family ?: throw AssertionError("Undefined coeffect family")
        markSymbolAsNotLeaked(firElement, symbol, family)
    }

    fun onOwnerCall(extractor: OwnerCallCoeffectContextHandler) {
        onOwnerCall = extractor
    }

    fun onOwnerEnter(extractor: OwnerEnterCoeffectContextHandler) {
        onOwnerEnter = extractor
    }

    fun onOwnerExit(extractor: OwnerExitCoeffectContextHandler) {
        onOwnerExit = extractor
    }

    fun build(): CoeffectFamilyContextHandler {
        val family = family ?: throw AssertionError("Undefined coeffect family for extractors")
        return CoeffectFamilyContextHandler(family, onOwnerCall ?: {}, onOwnerEnter ?: {}, onOwnerExit ?: {})
    }
}

fun handleCoeffectContext(block: CoeffectFamilyContextBuilder.() -> Unit): CoeffectFamilyContextHandler {
    val builder = CoeffectFamilyContextBuilder()
    builder.block()
    return builder.build()
}

operator fun CoeffectNodeContextBuilder<*>.plusAssign(actions: CoeffectContextActions) = addActions(actions)