/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contracts.contextual

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError

interface CoeffectContextAction {
    val family: CoeffectFamily
}

interface CoeffectContextModifier : CoeffectContextAction {
    fun modifyContext(context: CoeffectContext): CoeffectContext
}

interface CoeffectContextCleaner : CoeffectContextModifier {
    fun cleanupContext(context: CoeffectContext): CoeffectContext
    override fun modifyContext(context: CoeffectContext): CoeffectContext = cleanupContext(context)
}

interface CoeffectContextProvider : CoeffectContextModifier {
    fun provideContext(context: CoeffectContext): CoeffectContext
    override fun modifyContext(context: CoeffectContext): CoeffectContext = provideContext(context)
}

interface CoeffectContextVerifier : CoeffectContextAction {
    val needVerifyOnCurrentNode: Boolean get() = false
    fun verifyContext(context: CoeffectContext, session: FirSession): List<CoeffectContextVerificationError>
}

class CoeffectContextActions(
    val modifiers: MutableList<CoeffectContextModifier> = mutableListOf(),
    val verifiers: MutableList<CoeffectContextVerifier> = mutableListOf()
) {
    fun add(actions: CoeffectContextActions) {
        modifiers += actions.modifiers
        verifiers += actions.verifiers
    }

    companion object {
        val EMPTY = CoeffectContextActions()
    }

    val family: CoeffectFamily? get() = modifiers.firstOrNull()?.family ?: verifiers.firstOrNull()?.family
    val isEmpty: Boolean get() = modifiers.isEmpty() && verifiers.isEmpty()
}

class CoeffectContextActionsBuilder {
    val modifiers = mutableListOf<CoeffectContextModifier>()
    val verifiers = mutableListOf<CoeffectContextVerifier>()

    fun build(): CoeffectContextActions = CoeffectContextActions(modifiers, verifiers)
}

inline fun coeffectActions(block: CoeffectContextActionsBuilder.() -> Unit): CoeffectContextActions {
    val builder = CoeffectContextActionsBuilder()
    block(builder)
    return builder.build()
}