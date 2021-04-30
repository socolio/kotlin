/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity

internal open class DefaultLlvmDiagnosticHandler(val context: Context) : LlvmDiagnosticHandler {
    override fun handle(diagnostics: List<LlvmDiagnostic>) {
        diagnostics.filterNot(::isIgnored).forEach {
            when (it.severity) {
                LlvmDiagnostic.Severity.ERROR -> throw Error(it.message)
                LlvmDiagnostic.Severity.WARNING -> context.messageCollector.report(CompilerMessageSeverity.WARNING, it.message)
                LlvmDiagnostic.Severity.REMARK,
                LlvmDiagnostic.Severity.NOTE -> {
                    context.log { "${it.severity}: ${it.message}" }
                }
            }.also {} // Make exhaustive.
        }
    }

    open fun isIgnored(diagnostic: LlvmDiagnostic) = false
}
