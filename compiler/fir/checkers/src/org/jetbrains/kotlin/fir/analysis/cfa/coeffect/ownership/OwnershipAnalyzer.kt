/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect.ownership

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.CoeffectFamilyActionsCollector
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.CoeffectFamilyAnalyzer
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.contract.contextual.ownership.*
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.coeffectActions
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectNodeContextBuilder
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.plusAssign
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.FunctionCallNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.VariableDeclarationNode
import org.jetbrains.kotlin.fir.resolve.transformers.firClassLike
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.CallableId
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object OwnershipAnalyzer : CoeffectFamilyAnalyzer() {

    override val family: CoeffectFamily get() = OwnershipCoeffectFamily

    override val actionsCollector: CoeffectFamilyActionsCollector get() = OwnershipActionsCollector

    private val linkAnnotation = CallableId(FqName(""), FqName("Link"), Name.identifier("Link"))
    private val mutatesAnnotation = CallableId(FqName(""), FqName("Mutates"), Name.identifier("Mutates"))

    private object OwnershipActionsCollector : CoeffectFamilyActionsCollector() {

        override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: CoeffectNodeContextBuilder<*>) {
            if (node.fir.isVar) return

            val functionCall = node.fir.initializer as? FirFunctionCall ?: return
            val constructorSymbol = functionCall.toResolvedCallableSymbol() as? FirConstructorSymbol ?: return

            val constructor = constructorSymbol.fir
            if (!constructor.returnTypeRef.isLinkClass(constructor.session)) return

            data += coeffectActions {
                modifiers += OwnershipContextProvider(node.fir.symbol, OwnershipLinkState.MUTABLE)
            }
        }

        override fun visitFunctionCallNode(node: FunctionCallNode, data: CoeffectNodeContextBuilder<*>) {
            val functionSymbol = node.fir.toResolvedCallableSymbol() ?: return
            val receiverSymbol = node.fir.dispatchReceiver.toSymbol() as? FirCallableSymbol<*> ?: return
            if (!node.fir.dispatchReceiver.typeRef.isLinkClass(functionSymbol.fir.session)) return

            if (isMemberMutatesLink(functionSymbol)) {
                data += coeffectActions {
                    verifiers += OwnershipContextVerifier(receiverSymbol)
                }
            }
        }
    }

    private fun isLinkClass(classDeclaration: FirRegularClass): Boolean {
        return classDeclaration.annotations.any { it.toResolvedCallableSymbol()?.callableId == linkAnnotation }
    }

    private fun FirTypeRef.isLinkClass(session: FirSession): Boolean {
        val typeClass = firClassLike(session) as? FirRegularClass ?: return false
        return isLinkClass(typeClass)
    }


    private fun FirExpression?.toSymbol(): AbstractFirBasedSymbol<*>? = when (this) {
        is FirThisReceiverExpression -> calleeReference.boundSymbol
        is FirResolvable -> (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
        else -> null
    }

    private fun isMemberMutatesLink(member: FirCallableSymbol<*>): Boolean {
        for (annotation in member.fir.annotations) {
            when (annotation.toResolvedCallableSymbol()?.callableId) {
                mutatesAnnotation -> return true
            }
        }
        return false
    }

    override fun getFirError(error: CoeffectContextVerificationError, source: FirSourceElement): FirDiagnostic<*>? = with(error) {
        when (this) {
            is OwnershipImmutableLinkError -> FirErrors.IMMUTABLE_LINK.on(source, link)
            else -> null
        }
    }
}