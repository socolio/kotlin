/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect.checkedexception

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.CoeffectFamilyActionsCollector
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.CoeffectFamilyAnalyzer
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.contract.contextual.checkedexception.CatchesExceptionCoeffectContextProvider
import org.jetbrains.kotlin.fir.contract.contextual.checkedexception.CheckedExceptionCoeffectContextCleaner
import org.jetbrains.kotlin.fir.contract.contextual.checkedexception.CheckedExceptionCoeffectFamily
import org.jetbrains.kotlin.fir.contract.contextual.checkedexception.CheckedExceptionContextError
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.coeffectActions
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectNodeContextBuilder
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.plusAssign
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.TryMainBlockEnterNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.TryMainBlockExitNode
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneTypeSafe

object FirCheckedExceptionAnalyzer : CoeffectFamilyAnalyzer() {

    override val family: CoeffectFamily get() = CheckedExceptionCoeffectFamily
    override val actionsCollector: CoeffectFamilyActionsCollector get() = CheckedExceptionActionsCollector

    private object CheckedExceptionActionsCollector : CoeffectFamilyActionsCollector() {

        override fun visitTryMainBlockEnterNode(node: TryMainBlockEnterNode, data: CoeffectNodeContextBuilder<*>) {
            for (catch in node.fir.catches) {
                val exceptionType = catch.parameter.returnTypeRef.coneTypeSafe<ConeKotlinType>() ?: continue
                data += coeffectActions {
                    modifiers += CatchesExceptionCoeffectContextProvider(exceptionType, catch)
                }
            }
        }

        override fun visitTryMainBlockExitNode(node: TryMainBlockExitNode, data: CoeffectNodeContextBuilder<*>) {
            for (catch in node.fir.catches) {
                data += coeffectActions {
                    modifiers += CheckedExceptionCoeffectContextCleaner(catch)
                }
            }
        }
    }

    override fun getFirError(error: CoeffectContextVerificationError, source: FirSourceElement): FirDiagnostic<*>? = when (error) {
        is CheckedExceptionContextError -> FirErrors.UNCHECKED_EXCEPTION.on(source, error.exceptionType)
        else -> null
    }
}