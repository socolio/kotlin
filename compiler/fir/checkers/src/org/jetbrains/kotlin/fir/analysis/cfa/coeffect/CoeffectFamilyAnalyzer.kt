/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectNodeContextBuilder
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph

abstract class CoeffectFamilyAnalyzer {

    abstract val family: CoeffectFamily
    abstract val actionsCollector: CoeffectFamilyActionsCollector?

    abstract fun getFirError(error: CoeffectContextVerificationError, source: FirSourceElement): FirDiagnostic<*>?

    open fun analyze(function: FirFunction<*>, graph: ControlFlowGraph, reporter: DiagnosticReporter) {

    }

    open fun trackSymbolForIllegalUsage(
        rawContextBuilder: CoeffectRawContextBuilder,
        rawContext: CoeffectRawContextOnNodes,
        onLeakHandler: CoeffectNodeContextBuilder<*>.() -> Unit
    ): CoeffectSymbolUsageFamilyChecker? {
        return CoeffectSymbolUsageFamilyChecker(rawContextBuilder, family, rawContext, onLeakHandler)
    }
}

