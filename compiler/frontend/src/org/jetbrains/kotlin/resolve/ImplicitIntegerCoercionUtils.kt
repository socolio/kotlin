/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.name.FqName

fun ImplicitIntegerCoercion.isEnabledForParameter(descriptor: ParameterDescriptor): Boolean =
    isImplicitIntegerCoercionEnabledFor(descriptor)

fun ImplicitIntegerCoercion.isEnabledForConstVal(descriptor: VariableDescriptor): Boolean =
    isImplicitIntegerCoercionEnabledFor(descriptor)

private fun isImplicitIntegerCoercionEnabledFor(descriptor: DeclarationDescriptor): Boolean =
    descriptor.hasImplicitIntegerCoercionAnnotation() ||
            DescriptorUtils.getContainingModuleOrNull(descriptor)?.getCapability(ImplicitIntegerCoercion.MODULE_CAPABILITY) == true

private val IMPLICIT_INTEGER_COERCION_ANNOTATION_FQ_NAME = FqName("kotlin.internal.ImplicitIntegerCoercion")

private fun DeclarationDescriptor.hasImplicitIntegerCoercionAnnotation(): Boolean =
    annotations.findAnnotation(IMPLICIT_INTEGER_COERCION_ANNOTATION_FQ_NAME) != null
