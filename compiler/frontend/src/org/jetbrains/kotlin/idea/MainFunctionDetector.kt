/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.util.collectionUtils.filterIsInstanceMapNotNull
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments

class MainFunctionDetector(
    languageVersionSettings: LanguageVersionSettings,
    private val getFunctionDescriptor: (KtNamedFunction) -> FunctionDescriptor?,
) : MainFunctionDetectorBase(languageVersionSettings) {
    /** Assumes that the function declaration is already resolved and the descriptor can be found in the `bindingContext`. */
    constructor(bindingContext: BindingContext, languageVersionSettings: LanguageVersionSettings) : this(
        languageVersionSettings,
        { function ->
            bindingContext.get(BindingContext.FUNCTION, function)
                ?: throw throw KotlinExceptionWithAttachments("No descriptor resolved for $function")
                    .withAttachment("function.text", function.text)
        }
    )

    override fun FunctionDescriptor.getFunctionsFromTheSameFile(): Collection<FunctionDescriptor> {
        val containingFile = DescriptorToSourceUtils.getContainingFile(this) ?: return emptyList()
        return containingFile.declarations.filterIsInstanceMapNotNull(getFunctionDescriptor)
    }

    fun hasMain(declarations: List<KtDeclaration>): Boolean =
        findMainFunction(declarations) != null

    private fun findMainFunction(declarations: List<KtDeclaration>): KtNamedFunction? =
        declarations.filterIsInstance<KtNamedFunction>().find(::isMain)

    @JvmOverloads
    fun isMain(function: KtNamedFunction, allowParameterless: Boolean = true): Boolean {
        if (function.isLocal) {
            return false
        }

        var parametersCount = function.valueParameters.size
        if (function.receiverTypeReference != null) parametersCount++

        if (!isParameterNumberSuitsForMain(parametersCount, function.isTopLevel, allowParameterless)) {
            return false
        }

        if (function.typeParameters.isNotEmpty()) {
            return false
        }

        // PSI-only check for @JvmName
        if ("main" != function.name && !hasAnnotationWithExactNumberOfArguments(function, 1)) {
            return false
        }

        // PSI-only check for @JvmStatic
        if (!function.isTopLevel && !hasAnnotationWithExactNumberOfArguments(function, 0)) {
            return false
        }

        val functionDescriptor = getFunctionDescriptor(function)
        return functionDescriptor != null && isMain(functionDescriptor, allowParameterless = allowParameterless)
    }

    private fun hasAnnotationWithExactNumberOfArguments(function: KtNamedFunction, number: Int) =
        function.annotationEntries.any { it.valueArguments.size == number }

    interface Factory {
        fun createMainFunctionDetector(trace: BindingTrace, languageVersionSettings: LanguageVersionSettings): MainFunctionDetector

        class Ordinary : Factory {
            override fun createMainFunctionDetector(
                trace: BindingTrace,
                languageVersionSettings: LanguageVersionSettings
            ): MainFunctionDetector = MainFunctionDetector(trace.bindingContext, languageVersionSettings)
        }
    }
}
