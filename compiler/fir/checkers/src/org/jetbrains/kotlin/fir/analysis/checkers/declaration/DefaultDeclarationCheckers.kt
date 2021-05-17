/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.fir.analysis.cfa.*
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.FirCoeffectAnalyzer
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.checkedexception.FirCheckedExceptionAnalyzer
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.ownership.OwnershipAnalyzer
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.resourcemanagement.ResourceManagementAnalyzer
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.safebuilder.FirSafeBuilderAnalyzer
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker

object CommonDeclarationCheckers : DeclarationCheckers() {
    override val fileCheckers: List<FirFileChecker> = listOf(

    )

    override val declarationCheckers: List<FirBasicDeclarationChecker> = listOf(
        FirAnnotationClassDeclarationChecker,
        FirModifierChecker,
        FirManyCompanionObjectsChecker,
        FirLocalEntityNotAllowedChecker,
        FirTypeParametersInObjectChecker,
        FirConflictsChecker,
        FirConstructorInInterfaceChecker,
    )

    override val memberDeclarationCheckers: List<FirMemberDeclarationChecker> = listOf(
        FirInfixFunctionDeclarationChecker,
        FirExposedVisibilityDeclarationChecker,
        FirCommonConstructorDelegationIssuesChecker,
        FirSupertypeInitializedWithoutPrimaryConstructor,
        FirDelegationSuperCallInEnumConstructorChecker,
        FirPrimaryConstructorRequiredForDataClassChecker,
        FirMethodOfAnyImplementedInInterfaceChecker,
        FirSupertypeInitializedInInterfaceChecker,
        FirDelegationInInterfaceChecker,
        FirInterfaceWithSuperclassChecker,
        FirEnumClassSimpleChecker,
        FirSealedSupertypeChecker,
    )

    override val regularClassCheckers: List<FirRegularClassChecker> = listOf(

    )

    override val constructorCheckers: List<FirConstructorChecker> = listOf(
        FirConstructorAllowedChecker,
    )

    override val controlFlowAnalyserCheckers: List<FirControlFlowChecker> = listOf(
        FirPropertyInitializationAnalyzer,
        FirCallsEffectAnalyzer,
        FirReturnsImpliesAnalyzer,
        FirCoeffectAnalyzer(
            FirCheckedExceptionAnalyzer,
            FirSafeBuilderAnalyzer,
            ResourceManagementAnalyzer,
            OwnershipAnalyzer
        ),
    )
}