/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.jvm.serialization

import org.jetbrains.kotlin.backend.common.serialization.mangle.KotlinExportChecker
import org.jetbrains.kotlin.backend.common.serialization.mangle.KotlinMangleComputer
import org.jetbrains.kotlin.backend.common.serialization.mangle.MangleConstant
import org.jetbrains.kotlin.backend.common.serialization.mangle.MangleMode
import org.jetbrains.kotlin.backend.common.serialization.mangle.descriptor.DescriptorBasedKotlinManglerImpl
import org.jetbrains.kotlin.backend.common.serialization.mangle.descriptor.DescriptorExportCheckerVisitor
import org.jetbrains.kotlin.backend.common.serialization.mangle.descriptor.DescriptorMangleComputer
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrBasedKotlinManglerImpl
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrExportCheckerVisitor
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrMangleComputer
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.MainFunctionDetectorBase
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.util.isFromJava
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.lazy.descriptors.isJavaField

object JvmIrMangler : IrBasedKotlinManglerImpl() {
    private object ExportChecker : IrExportCheckerVisitor() {
        override fun IrDeclaration.isPlatformSpecificExported() = false
    }

    private class JvmIrManglerComputer(builder: StringBuilder, mode: MangleMode) : IrMangleComputer(builder, mode) {
        override fun copy(newMode: MangleMode): IrMangleComputer =
            JvmIrManglerComputer(builder, newMode)

        override fun addReturnTypeSpecialCase(irFunction: IrFunction): Boolean =
            irFunction.isFromJava()
    }

    override fun getExportChecker(): KotlinExportChecker<IrDeclaration> = ExportChecker

    override fun getMangleComputer(mode: MangleMode): KotlinMangleComputer<IrDeclaration> =
        JvmIrManglerComputer(StringBuilder(256), mode)
}

class JvmDescriptorMangler(languageVersionSettings: LanguageVersionSettings) : DescriptorBasedKotlinManglerImpl() {
    private val mainDetector = MainDetector(languageVersionSettings)

    companion object {
        private val exportChecker = JvmDescriptorExportChecker()
    }

    private class JvmDescriptorExportChecker : DescriptorExportCheckerVisitor() {
        override fun DeclarationDescriptor.isPlatformSpecificExported() = false
    }

    private class JvmDescriptorManglerComputer(
        builder: StringBuilder,
        private val mainDetector: MainFunctionDetectorBase?,
        mode: MangleMode
    ) : DescriptorMangleComputer(builder, mode) {
        override fun addReturnTypeSpecialCase(functionDescriptor: FunctionDescriptor): Boolean =
            functionDescriptor is JavaMethodDescriptor

        override fun copy(newMode: MangleMode): DescriptorMangleComputer =
            JvmDescriptorManglerComputer(builder, mainDetector, newMode)

        private fun isMainFunction(descriptor: FunctionDescriptor): Boolean =
            mainDetector != null && mainDetector.isMain(descriptor)

        override fun FunctionDescriptor.platformSpecificSuffix(): String? =
            if (isMainFunction(this)) source.containingFile.name else null

        override fun PropertyDescriptor.platformSpecificSuffix(): String? {
            // Since LV 1.4 there is a feature PreferJavaFieldOverload which allows to have java and kotlin
            // properties with the same signature on the same level.
            // For more details see JvmPlatformOverloadsSpecificityComparator.kt
            return if (isJavaField) MangleConstant.JAVA_FIELD_SUFFIX else null
        }
    }

    override fun getExportChecker(): KotlinExportChecker<DeclarationDescriptor> = exportChecker

    override fun getMangleComputer(mode: MangleMode): KotlinMangleComputer<DeclarationDescriptor> =
        JvmDescriptorManglerComputer(StringBuilder(256), mainDetector, mode)

    private class MainDetector(languageVersionSettings: LanguageVersionSettings) : MainFunctionDetectorBase(languageVersionSettings) {
        // Returning empty list here can result in that more functions than necessary will be considered as entrypoints. Namely, a no-arg
        // function `main` is _not_ considered as entrypoint in frontend if there's an overload with Array<String>, but here it will be
        // considered as entrypoint and the signature will have a file name suffix.
        // It is not a problem so far, since these signatures do not have any compatibility guarantees on JVM IR.
        override fun FunctionDescriptor.getFunctionsFromTheSameFile(): Collection<FunctionDescriptor> = emptyList()
    }
}
