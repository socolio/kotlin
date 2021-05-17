/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.contracts.description

import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectFamilyContextHandler
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.ConeCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.ConeLambdaCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.types.ConeKotlinType

/**
 * Effect with condition attached to it.
 *
 * [condition] is some expression, which result-type is Boolean, and clause should
 * be interpreted as: "if [effect] took place then [condition]-expression is
 * guaranteed to be true"
 *
 * NB. [effect] and [condition] connected with implication in math logic sense:
 * [effect] => [condition]. In particular this means that:
 *  - there can be multiple ways how [effect] can be produced, but for any of them
 *    [condition] holds.
 *  - if [effect] wasn't observed, we *can't* reason that [condition] is false
 *  - if [condition] is true, we *can't* reason that [effect] will be observed.
 */
class ConeConditionalEffectDeclaration(val effect: ConeEffectDeclaration, val condition: ConeBooleanExpression) : ConeEffectDeclaration() {
    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitConditionalEffectDeclaration(this, data)
}


/**
 * Effect which specifies that subroutine returns some particular value
 */
class ConeReturnsEffectDeclaration(val value: ConeConstantReference) : ConeEffectDeclaration() {
    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitReturnsEffectDeclaration(this, data)

}


/**
 * Effect which specifies, that during execution of subroutine, callable [valueParameterReference] will be invoked
 * [kind] amount of times, and will never be invoked after subroutine call is finished.
 */
class ConeCallsEffectDeclaration(val valueParameterReference: ConeValueParameterReference, val kind: EventOccurrencesRange) :
    ConeEffectDeclaration() {
    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitCallsEffectDeclaration(this, data)
}


/**
 * Effect which specifies that owner function can be called only in context where [exceptionType] is checked
 */
class ConeThrowsEffectDeclaration(val exceptionType: ConeKotlinType, contextHandler: CoeffectFamilyContextHandler) :
    ConeCoeffectEffectDeclaration(contextHandler) {
    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitThrowsEffectDeclaration(this, data)
}


/**
 * Effect which specifies that [lambda] called only in context where [exceptionType] is checked
 */
class ConeCalledInTryCatchEffectDeclaration(
    lambda: ConeValueParameterReference,
    val exceptionType: ConeKotlinType,
    contextHandler: CoeffectFamilyContextHandler
) : ConeLambdaCoeffectEffectDeclaration(lambda, contextHandler) {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitCalledInTryCatchEffectDeclaration(this, data)
}

interface ConeSafeBuilderEffectDeclaration {
    val action: ConeActionDeclaration
}

/**
 * Effect which specifies that [lambda] must do some [action] inside
 */
class ConeMustDoEffectDeclaration(
    lambda: ConeValueParameterReference,
    override val action: ConeActionDeclaration,
    contextHandler: CoeffectFamilyContextHandler
) : ConeLambdaCoeffectEffectDeclaration(lambda, contextHandler), ConeSafeBuilderEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitMustDoEffectDeclaration(this, data)
}

/**
 * Effect which specifies that function is doing [action]
 */
class ConeProvidesActionEffectDeclaration(
    override val action: ConeActionDeclaration,
    contextHandler: CoeffectFamilyContextHandler
) : ConeCoeffectEffectDeclaration(contextHandler), ConeSafeBuilderEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitProvidesActionEffectDeclaration(this, data)
}

/**
 * Effect which specifies that function requires made [action]
 */
class ConeRequiresActionEffectDeclaration(
    override val action: ConeActionDeclaration,
    contextHandler: CoeffectFamilyContextHandler
) : ConeCoeffectEffectDeclaration(contextHandler), ConeSafeBuilderEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitRequiresActionEffectDeclaration(this, data)
}

interface ConeResourceManagementEffectDeclaration {
    val resource: ConeValueParameterReference
}

interface ConeOwnershipEffectDeclaration {
    val link: ConeValueParameterReference
}

/**
 * Effect which specifies that [resource] is required to be open
 */
class ConeRequireOpenEffectDeclaration(
    override val resource: ConeValueParameterReference,
    contextHandler: CoeffectFamilyContextHandler
) : ConeCoeffectEffectDeclaration(contextHandler), ConeResourceManagementEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitRequireOpenEffectDeclaration(this, data)
}

/**
 * Effect which specifies that [resource] is closed after function invocation
 */
class ConeClosesResourceEffectDeclaration(
    override val resource: ConeValueParameterReference,
    contextHandler: CoeffectFamilyContextHandler
) : ConeCoeffectEffectDeclaration(contextHandler), ConeResourceManagementEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitClosesResourceEffectDeclaration(this, data)
}

/**
* Effect which specifies that [link] is mutably borrowed by function
*/
class ConeBorrowsLinkEffectDeclaration(
    override val link: ConeValueParameterReference,
    contextHandler: CoeffectFamilyContextHandler
) : ConeCoeffectEffectDeclaration(contextHandler), ConeOwnershipEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitBorrowsLinkEffectDeclaration(this, data)
}

/**
 * Effect which specifies that [link] is fully consumed by function
 */
class ConeConsumesLinkEffectDeclaration(
    override val link: ConeValueParameterReference,
    contextHandler: CoeffectFamilyContextHandler
) : ConeCoeffectEffectDeclaration(contextHandler), ConeOwnershipEffectDeclaration {

    override fun <R, D> accept(contractDescriptionVisitor: ConeContractDescriptionVisitor<R, D>, data: D): R =
        contractDescriptionVisitor.visitConsumesLinkEffectDeclaration(this, data)
}