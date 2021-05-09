/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual.resourcemanagement

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.contract.contextual.safeBuilder.SafeBuilderCoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextVerifier
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol

class ResourceManagementUndefinedStateError(val target: AbstractFirBasedSymbol<*>) :
    CoeffectContextVerificationError(SafeBuilderCoeffectFamily)

class ResourceManagementStateError(val target: AbstractFirBasedSymbol<*>, val shouldBeOpen: Boolean) :
    CoeffectContextVerificationError(SafeBuilderCoeffectFamily)

class ResourceManagementContextVerifier(val target: AbstractFirBasedSymbol<*>, val shouldBeOpen: Boolean) : CoeffectContextVerifier {
    override val family: CoeffectFamily get() = ResourceManagementCoeffectFamily

    override fun verifyContext(context: CoeffectContext, session: FirSession): List<CoeffectContextVerificationError> {
        if (context !is ResourceManagementContext) throw AssertionError()
        val isOpen = context.states[target] ?: return listOf(ResourceManagementUndefinedStateError(target))

        if (isOpen != shouldBeOpen) {
            return listOf(ResourceManagementStateError(target, shouldBeOpen))
        }
        return emptyList()
    }
}