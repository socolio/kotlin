/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect.resourcemanagement

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.CoeffectFamilyActionsCollector
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.CoeffectFamilyAnalyzer
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.contract.contextual.resourcemanagement.*
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

object ResourceManagementAnalyzer : CoeffectFamilyAnalyzer() {
    override val family: CoeffectFamily get() = ResourceManagementCoeffectFamily
    override val actionsCollector: CoeffectFamilyActionsCollector get() = ResourceManagementActionsCollector

    private val resourceAnnotation = CallableId(FqName(""), FqName("Resource"), Name.identifier("Resource"))
    private val closesResourceAnnotation = CallableId(FqName(""), FqName("ClosesResource"), Name.identifier("ClosesResource"))
    private val opensResourceAnnotation = CallableId(FqName(""), FqName("OpensResource"), Name.identifier("OpensResource"))
    private val requireOpenAnnotation = CallableId(FqName(""), FqName("RequireOpen"), Name.identifier("RequireOpen"))
    private val requireClosedAnnotation = CallableId(FqName(""), FqName("RequireClosed"), Name.identifier("RequireClosed"))

    private object ResourceManagementActionsCollector : CoeffectFamilyActionsCollector() {

        override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: CoeffectNodeContextBuilder<*>) {
            if (node.fir.isVar) return

            val functionCall = node.fir.initializer as? FirFunctionCall ?: return
            val constructorSymbol = functionCall.toResolvedCallableSymbol() as? FirConstructorSymbol ?: return

            val constructor = constructorSymbol.fir
            if (!constructor.returnTypeRef.isResourceClass(constructor.session)) return
            val initialState = getNewStateAfterMemberInvocation(constructorSymbol) ?: return

            data += coeffectActions {
                modifiers += ResourceManagementContextProvider(node.fir.symbol, initialState)
            }

            data.trackSymbolForLeaking(family, node.fir.symbol) {
                coeffectActions {
                    modifiers += ResourceManagementContextCleaner(node.fir.symbol)
                }
            }
        }

        override fun visitFunctionCallNode(node: FunctionCallNode, data: CoeffectNodeContextBuilder<*>) {
            val functionSymbol = node.fir.toResolvedCallableSymbol() ?: return
            val receiverSymbol = node.fir.dispatchReceiver.toSymbol() as? FirCallableSymbol<*> ?: return
            if (!node.fir.dispatchReceiver.typeRef.isResourceClass(functionSymbol.fir.session)) return

            val newState = getNewStateAfterMemberInvocation(functionSymbol)
            if (newState != null) {
                data += coeffectActions {
                    modifiers += ResourceManagementContextProvider(receiverSymbol, newState)
                }
            }

            val requiredState = getRequiredStateForMemberInvocation(functionSymbol)
            if (requiredState != null) {
                data += coeffectActions {
                    verifiers += ResourceManagementContextVerifier(receiverSymbol, requiredState)
                }
            }

            data.markSymbolAsNotLeaked(node.fir, receiverSymbol, family)
        }
    }

    private fun isResourceClass(classDeclaration: FirRegularClass): Boolean {
        return classDeclaration.annotations.any { it.toResolvedCallableSymbol()?.callableId == resourceAnnotation }
    }

    private fun FirTypeRef.isResourceClass(session: FirSession): Boolean {
        val typeClass = firClassLike(session) as? FirRegularClass ?: return false
        return isResourceClass(typeClass)
    }

    private fun getNewStateAfterMemberInvocation(member: FirCallableSymbol<*>): Boolean? {
        for (annotation in member.fir.annotations) {
            when (annotation.toResolvedCallableSymbol()?.callableId) {
                opensResourceAnnotation -> return true
                closesResourceAnnotation -> return false
            }
        }
        return null
    }

    private fun getRequiredStateForMemberInvocation(member: FirCallableSymbol<*>): Boolean? {
        for (annotation in member.fir.annotations) {
            when (annotation.toResolvedCallableSymbol()?.callableId) {
                opensResourceAnnotation -> return false
                closesResourceAnnotation -> return true
                requireOpenAnnotation -> return true
                requireClosedAnnotation -> return false
            }
        }
        return null
    }

    private fun FirExpression?.toSymbol(): AbstractFirBasedSymbol<*>? = when (this) {
        is FirThisReceiverExpression -> calleeReference.boundSymbol
        is FirResolvable -> (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
        else -> null
    }

    override fun getFirError(error: CoeffectContextVerificationError, source: FirSourceElement): FirDiagnostic<*>? = with(error) {
        when (this) {
            is ResourceManagementUndefinedStateError -> FirErrors.UNDEFINED_RESOURCE_STATE.on(source, target)
            is ResourceManagementStateError -> when (shouldBeOpen) {
                true -> FirErrors.RESOURCE_SHOULD_BE_OPEN.on(source, target)
                false -> FirErrors.RESOURCE_SHOULD_BE_CLOSED.on(source, target)
            }
            else -> null
        }
    }
}