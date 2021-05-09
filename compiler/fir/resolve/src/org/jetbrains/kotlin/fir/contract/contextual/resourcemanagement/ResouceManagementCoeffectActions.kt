/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contract.contextual.resourcemanagement

import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContext
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextCleaner
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectContextProvider
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol

class ResourceManagementContextProvider(val target: AbstractFirBasedSymbol<*>, val isOpen: Boolean) : CoeffectContextProvider {
    override val family = ResourceManagementCoeffectFamily

    override fun provideContext(context: CoeffectContext): CoeffectContext {
        if (context !is ResourceManagementContext) throw AssertionError()
        return ResourceManagementContext(context.states.put(target, isOpen))
    }
}

class ResourceManagementContextCleaner(val target: AbstractFirBasedSymbol<*>) : CoeffectContextCleaner {
    override val family = ResourceManagementCoeffectFamily

    override fun cleanupContext(context: CoeffectContext): CoeffectContext {
        if (context !is ResourceManagementContext) throw AssertionError()
        return ResourceManagementContext(context.states.remove(target))
    }
}