/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.annotations.hasJvmStaticAnnotation
import org.jetbrains.kotlin.types.Variance

abstract class MainFunctionDetectorBase(protected val languageVersionSettings: LanguageVersionSettings) {
    @JvmOverloads
    fun isMain(
        descriptor: DeclarationDescriptor,
        checkJvmStaticAnnotation: Boolean = true,
        checkReturnType: Boolean = true,
        allowParameterless: Boolean = true
    ): Boolean {
        if (descriptor !is FunctionDescriptor) return false

        if (getJVMFunctionName(descriptor) != "main") {
            return false
        }

        val parameters = descriptor.valueParameters.mapTo(mutableListOf()) { it.type }
        descriptor.extensionReceiverParameter?.type?.let { parameters += it }

        if (!isParameterNumberSuitsForMain(parameters.size, DescriptorUtils.isTopLevelDeclaration(descriptor), allowParameterless)) {
            return false
        }

        if (descriptor.typeParameters.isNotEmpty()) return false

        if (parameters.size == 1) {
            val parameterType = parameters[0]
            if (!KotlinBuiltIns.isArray(parameterType)) return false

            val typeArguments = parameterType.arguments
            if (typeArguments.size != 1) return false

            val typeArgument = typeArguments[0].type
            if (!KotlinBuiltIns.isString(typeArgument)) {
                return false
            }
            if (typeArguments[0].projectionKind === Variance.IN_VARIANCE) {
                return false
            }
        } else {
            assert(parameters.size == 0) { "Parameter list is expected to be empty" }
            assert(DescriptorUtils.isTopLevelDeclaration(descriptor)) { "main without parameters works only for top-level" }
            // We do not support parameterless entry points having JvmName("name") but different real names
            // See more at https://github.com/Kotlin/KEEP/blob/master/proposals/enhancing-main-convention.md#parameterless-main
            if (descriptor.name.asString() != "main") return false

            if (descriptor.getFunctionsFromTheSameFile().any { declaration ->
                    isMain(declaration, checkJvmStaticAnnotation, allowParameterless = false)
                }) return false
        }

        if (descriptor.isSuspend && !languageVersionSettings.supportsFeature(LanguageFeature.ExtendedMainConvention)) return false

        if (checkReturnType && !isMainReturnType(descriptor)) return false

        if (DescriptorUtils.isTopLevelDeclaration(descriptor)) return true

        val containingDeclaration = descriptor.containingDeclaration
        return containingDeclaration is ClassDescriptor
                && containingDeclaration.kind.isSingleton
                && (descriptor.hasJvmStaticAnnotation() || !checkJvmStaticAnnotation)
    }

    protected abstract fun FunctionDescriptor.getFunctionsFromTheSameFile(): Collection<FunctionDescriptor>

    protected fun isParameterNumberSuitsForMain(
        parametersCount: Int,
        isTopLevel: Boolean,
        allowParameterless: Boolean
    ): Boolean = when (parametersCount) {
        1 -> true
        0 -> isTopLevel && allowParameterless && languageVersionSettings.supportsFeature(LanguageFeature.ExtendedMainConvention)
        else -> false
    }

    companion object {
        private fun isMainReturnType(descriptor: FunctionDescriptor): Boolean {
            val returnType = descriptor.returnType
            return returnType != null && KotlinBuiltIns.isUnit(returnType)
        }

        private fun getJVMFunctionName(functionDescriptor: FunctionDescriptor): String {
            return DescriptorUtils.getJvmName(functionDescriptor) ?: functionDescriptor.name.asString()
        }
    }
}
