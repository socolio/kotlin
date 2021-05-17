/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual.ownership

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextVerifier
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

class OwnershipImmutableLinkError(val link: AbstractFirBasedSymbol<*>) :
    CoeffectContextVerificationError(OwnershipCoeffectFamily)

class OwnershipContextVerifier(val link: AbstractFirBasedSymbol<*>) : CoeffectContextVerifier {
    override val family: CoeffectFamily get() = OwnershipCoeffectFamily

    override fun verifyContext(context: CoeffectContext, session: FirSession): List<CoeffectContextVerificationError> {
        if (context !is OwnershipContext) throw AssertionError()
        val linkState = context.states[link] ?: OwnershipLinkState.IMMUTABLE
        if (linkState != OwnershipLinkState.MUTABLE) {
            return listOf (OwnershipImmutableLinkError(link))
        }
        return emptyList()
    }
}