/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.uast.kotlin

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toPsiParameters
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.uast.*

internal object FirKotlinConverter {
    internal fun convertDeclarationOrElement(
        element: PsiElement,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): UElement? {
        if (element is UElement) return element

        // TODO: cache inside PsiElement?

        return convertDeclaration(element, givenParent, requiredTypes)
            ?: convertPsiElement(element, givenParent, requiredTypes)
    }

    internal fun convertDeclaration(
        element: PsiElement,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): UElement? {
        val original = element.originalElement

        fun <P : PsiElement> build(ctor: (P, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(original as P, givenParent)
        }

        fun <P : PsiElement, K : KtElement> buildKt(ktElement: K, ctor: (P, K, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(original as P, ktElement, givenParent)
        }

        fun <P : PsiElement, K : KtElement> buildKtOpt(ktElement: K?, ctor: (P, K?, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(original as P, ktElement, givenParent)
        }

        return with(requiredTypes) {
            when (original) {
                is KtFile -> {
                    convertKtFile(original, givenParent, requiredTypes).firstOrNull()
                }

                is KtLightClass -> {
                    // TODO: differentiate enum entry
                    el<UClass> { AbstractFirKotlinUClass.create(original, givenParent) }
                }
                is KtClassOrObject -> {
                    convertClassOrObject(original, givenParent, requiredTypes).firstOrNull()
                }
                // TODO: KtEnumEntry

                is KtLightField -> {
                    // TODO: differentiate enum constant
                    el<UField>(buildKtOpt(original.kotlinOrigin, ::FirKotlinUField))
                }
                is KtLightParameter -> {
                    el<UParameter>(buildKtOpt(original.kotlinOrigin, ::FirKotlinUParameter))
                }
                is KtParameter -> {
                    convertParameter(original, givenParent, requiredTypes).firstOrNull()
                }
                is KtProperty -> {
                    // TODO: differentiate local
                    convertNonLocalProperty(original, givenParent, requiredTypes).firstOrNull()
                }
                is KtPropertyAccessor -> {
                    el<UMethod> { FirKotlinUMethod.create(original, givenParent) }
                }

                is KtLightMethod -> {
                    // .Companion is needed because of KT-13934
                    el<UMethod>(build(FirKotlinUMethod.Companion::create))
                }
                is KtFunction -> {
                    // TODO: differentiate local
                    el<UMethod> { FirKotlinUMethod.create(original, givenParent) }
                }

                // TODO: KtAnnotationEntry
                // TODO: KtCallExpression (for nested annotation)

                else -> null
            }
        }
    }

    internal fun convertKtFile(
        element: KtFile,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> {
        return requiredTypes.accommodate(
            // File
            alternative { FirKotlinUFile(element) },
            // TODO: Facade
            // alternative { element.findFacadeClass()?.let { AbstractFirKotlinUClass.create(it, givenParent) } }
        )
    }

    internal fun convertClassOrObject(
        element: KtClassOrObject,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> {
        val ktLightClass = element.toLightClass() ?: return emptySequence()
        val uClass = AbstractFirKotlinUClass.create(ktLightClass, givenParent)
        return requiredTypes.accommodate(
            // Class
            alternative { uClass },
            // Object
            alternative primaryConstructor@{
                val primaryConstructor = element.primaryConstructor ?: return@primaryConstructor null
                uClass.methods.asSequence()
                    .filter { it.sourcePsi == primaryConstructor }
                    .firstOrNull()
            }
        )
    }

    private fun convertToPropertyAlternatives(
        methods: LightClassUtil.PropertyAccessorsPsiMethods?,
        givenParent: UElement?
    ): Array<UElementAlternative<*>> =
        if (methods != null)
            arrayOf(
                alternative { methods.backingField?.let { FirKotlinUField(it, getKotlinMemberOrigin(it), givenParent) } },
                alternative { methods.getter?.let { convertDeclaration(it, givenParent, arrayOf(UMethod::class.java)) as? UMethod } },
                alternative { methods.setter?.let { convertDeclaration(it, givenParent, arrayOf(UMethod::class.java)) as? UMethod } }
            )
        else emptyArray()

    internal fun convertNonLocalProperty(
        property: KtProperty,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> =
        requiredTypes.accommodate(*convertToPropertyAlternatives(LightClassUtil.getLightClassPropertyMethods(property), givenParent))

    internal fun convertParameter(
        element: KtParameter,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): Sequence<UElement> =
        requiredTypes.accommodate(
            alternative uParam@{
                val lightParameter = element.toPsiParameters().find { it.name == element.name } ?: return@uParam null
                FirKotlinUParameter(lightParameter, element, givenParent)
            },
            alternative catch@{
                val uCatchClause = element.parent?.parent?.safeAs<KtCatchClause>()?.toUElementOfType<UCatchClause>() ?: return@catch null
                uCatchClause.parameters.firstOrNull { it.sourcePsi == element}
            },
            *convertToPropertyAlternatives(LightClassUtil.getLightClassPropertyMethods(element), givenParent)
        )

    internal fun convertPsiElement(
        element: PsiElement,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): UElement? {
        fun <P : PsiElement> build(ctor: (P, UElement?) -> UElement): () -> UElement? = {
            @Suppress("UNCHECKED_CAST")
            ctor(element as P, givenParent)
        }

        return with(requiredTypes) {
            when (element) {
                is KtExpression -> {
                    convertExpression(element, givenParent, requiredTypes)
                }
                is KtImportDirective -> {
                    el<UImportStatement>(build(::FirKotlinUImportStatement))
                }
                else -> null
            }
        }
    }

    internal fun convertExpression(
        expression: KtExpression,
        givenParent: UElement?,
        requiredTypes: Array<out Class<out UElement>>
    ): UExpression? {

        fun <P : PsiElement> build(ctor: (P, UElement?) -> UExpression): () -> UExpression? {
            return {
                @Suppress("UNCHECKED_CAST")
                ctor(expression as P, givenParent)
            }
        }

        return with(requiredTypes) {
            when (expression) {
                else -> expr<UExpression>(build(::FirUnknownKotlinExpression))
            }
        }
    }
}
