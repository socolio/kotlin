/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual.ownership

import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextProvider
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

class OwnershipContextProvider(val link: AbstractFirBasedSymbol<*>, val state: OwnershipLinkState) :
    CoeffectContextProvider {
    override val family = OwnershipCoeffectFamily

    override fun provideContext(context: CoeffectContext): CoeffectContext {
        if (context !is OwnershipContext) throw AssertionError()
        return OwnershipContext(context.states.put(link, state))
    }
}