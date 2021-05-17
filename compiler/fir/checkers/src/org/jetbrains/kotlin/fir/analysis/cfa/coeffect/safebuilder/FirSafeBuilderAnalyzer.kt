/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect.safebuilder

import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.coeffect.*
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.contract.contextual.safeBuilder.*
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.contracts.contextual.coeffectActions
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.CoeffectNodeContextBuilder
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.ConeAbstractCoeffectEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.contextual.declaration.plusAssign
import org.jetbrains.kotlin.fir.contracts.contextual.diagnostics.CoeffectContextVerificationError
import org.jetbrains.kotlin.fir.contracts.description.ConeActionDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeFunctionInvocationAction
import org.jetbrains.kotlin.fir.contracts.description.ConePropertyInitializationAction
import org.jetbrains.kotlin.fir.contracts.description.ConeSafeBuilderEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.effects
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.resolve.transformers.firClassLike
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.CallableId
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object FirSafeBuilderAnalyzer : CoeffectFamilyAnalyzer() {

    override val family: CoeffectFamily get() = SafeBuilderCoeffectFamily
    override val actionsCollector: CoeffectFamilyActionsCollector get() = SafeBuilderActionsCollector

    private val safeBuilderAnnotation = CallableId(FqName(""), FqName("SafeBuilder"), Name.identifier("SafeBuilder"))
    private val notConstructionAnnotation = CallableId(FqName(""), FqName("NotConstruction"), Name.identifier("NotConstruction"))
    private val buildAnnotation = CallableId(FqName(""), FqName("Build"), Name.identifier("Build"))

    override fun analyze(function: FirFunction<*>, graph: ControlFlowGraph, reporter: DiagnosticReporter) {
        val contractDescription = (function as? FirContractDescriptionOwner)?.contractDescription
        val effects = contractDescription?.effects ?: emptyList()

        for (effect in effects) {
            if (effect !is ConeAbstractCoeffectEffectDeclaration) continue
            val action = (effect as? ConeSafeBuilderEffectDeclaration)?.action ?: continue
            val member = action.memberSymbol ?: continue

            if (!isSafeBuilderMember(action.targetClass, member)) {
                contractDescription?.source?.let {
                    reporter.report(FirErrors.NOT_A_SAFE_BUILDER_MEMBER.on(it, action.targetClass, member))
                }
            }
        }
    }

    private object SafeBuilderActionsCollector : CoeffectFamilyActionsCollector() {

        override fun visitFunctionCallNode(node: FunctionCallNode, data: CoeffectNodeContextBuilder<*>) {
            val functionSymbol = node.fir.toResolvedCallableSymbol() ?: return
            val receiverSymbol = node.fir.dispatchReceiver.toSymbol() as? FirCallableSymbol<*> ?: return
            val safeBuilderClass = node.fir.dispatchReceiver.typeRef.toSafeBuilderClass(functionSymbol.fir.session) ?: return

            if (isSafeBuilderConstructionMember(functionSymbol)) {
                val safeBuilderAction = SafeBuilderAction(receiverSymbol, functionSymbol, SafeBuilderActionType.INVOCATION)
                data += coeffectActions {
                    modifiers += SafeBuilderCoeffectContextProvider(safeBuilderAction, EventOccurrencesRange.EXACTLY_ONCE)
                }
                data.markSymbolAsNotLeaked(node.fir, receiverSymbol, family)
                return
            }

            if (functionSymbol.fir.annotations.any { it.toResolvedCallableSymbol()?.callableId == buildAnnotation }) {
                data.markSymbolAsNotLeaked(node.fir, receiverSymbol, family)
                safeBuilderClass.forEachSafeBuilderMember { member, actionType ->
                    data += coeffectActions {
                        modifiers += SafeBuilderCoeffectContextCleaner(SafeBuilderAction(receiverSymbol, member.symbol, actionType))
                    }
                }
            }
        }

        override fun visitVariableAssignmentNode(node: VariableAssignmentNode, data: CoeffectNodeContextBuilder<*>) {
            val receiverSymbol = node.fir.dispatchReceiver.toSymbol() as? FirCallableSymbol<*> ?: return
            val propertySymbol = node.fir.resolvedCallableReference?.resolvedSymbol as? FirCallableSymbol<*> ?: return
            if (!isSafeBuilderMember(receiverSymbol, propertySymbol)) return

            val safeBuilderAction = SafeBuilderAction(receiverSymbol, propertySymbol, SafeBuilderActionType.INITIALIZATION)
            data += coeffectActions {
                modifiers += SafeBuilderCoeffectContextProvider(safeBuilderAction, EventOccurrencesRange.EXACTLY_ONCE)
            }
        }

        override fun visitVariableDeclarationNode(node: VariableDeclarationNode, data: CoeffectNodeContextBuilder<*>) {
            if (node.fir.isVar) return

            val functionCall = node.fir.initializer as? FirFunctionCall ?: return
            val constructorSymbol = functionCall.toResolvedCallableSymbol() as? FirConstructorSymbol ?: return

            val constructor = constructorSymbol.fir
            val constructedClass = constructor.returnTypeRef.firClassLike(constructor.session) as? FirRegularClass ?: return
            if (!isSafeBuilderClass(constructedClass)) return

            constructedClass.forEachSafeBuilderMember { member, actionType ->
                val safeBuilderAction = SafeBuilderAction(node.fir.symbol, member.symbol, actionType)
                data += coeffectActions {
                    modifiers += SafeBuilderCoeffectContextProvider(safeBuilderAction, EventOccurrencesRange.ZERO)
                }
            }
        }

        override fun visitFunctionExitNode(node: FunctionExitNode, data: CoeffectNodeContextBuilder<*>) {
            data += coeffectActions {
                verifiers += SafeBuilderActionProvidingVerifier
            }
        }

        inline fun FirRegularClass.forEachSafeBuilderMember(
            handler: (member: FirCallableMemberDeclaration<*>, actionType: SafeBuilderActionType) -> Unit
        ) {
            for (declaration in declarations) {
                if (declaration !is FirCallableMemberDeclaration<*> || !isSafeBuilderConstructionMember(declaration.symbol)) continue
                val actionType = when (declaration) {
                    is FirProperty -> SafeBuilderActionType.INITIALIZATION
                    is FirSimpleFunction -> SafeBuilderActionType.INVOCATION
                    else -> continue
                }
                handler(declaration, actionType)
            }
        }
    }

    private fun FirExpression?.toSymbol(): AbstractFirBasedSymbol<*>? = when (this) {
        is FirThisReceiverExpression -> calleeReference.boundSymbol
        is FirResolvable -> (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
        else -> null
    }

    private val FirResolvable.resolvedCallableReference: FirResolvedNamedReference?
        get() = calleeReference as? FirResolvedNamedReference

    private val ConeActionDeclaration.memberSymbol: FirCallableSymbol<*>?
        get() = when (this) {
            is ConePropertyInitializationAction -> property
            is ConeFunctionInvocationAction -> function
            else -> null
        }

    fun isSafeBuilderClass(classDeclaration: FirRegularClass): Boolean {
        return classDeclaration.annotations.any { it.toResolvedCallableSymbol()?.callableId == safeBuilderAnnotation }
    }

    fun FirTypeRef.toSafeBuilderClass(session: FirSession): FirRegularClass? {
        val typeClass = firClassLike(session) as? FirRegularClass ?: return null
        return typeClass.takeIf(::isSafeBuilderClass)
    }

    fun isSafeBuilder(owner: FirCallableSymbol<*>): Boolean =
        owner.fir.receiverTypeRef?.toSafeBuilderClass(owner.fir.session) != null

    fun isSafeBuilderMember(owner: FirCallableSymbol<*>, member: FirCallableSymbol<*>): Boolean =
        isSafeBuilder(owner) && isSafeBuilderConstructionMember(member)

    fun isSafeBuilderMember(owner: FirRegularClassSymbol, member: FirCallableSymbol<*>): Boolean =
        isSafeBuilderClass(owner.fir) && isSafeBuilderConstructionMember(member)

    fun isSafeBuilderConstructionMember(member: FirCallableSymbol<*>): Boolean =
        member.fir.annotations.none {
            val id = it.toResolvedCallableSymbol()?.callableId
            id == notConstructionAnnotation || id == buildAnnotation
        }

    override fun getFirError(error: CoeffectContextVerificationError, source: FirSourceElement): FirDiagnostic<*>? = with(error) {
        when (this) {
            is SafeBuilderInitializationRequiredError -> FirErrors.PROPERTY_INITIALIZATION_REQUIRED.on(source, target, property, range)
            is SafeBuilderInvocationRequiredError -> FirErrors.FUNCTION_INVOCATION_REQUIRED.on(source, target, function, range)
            is SafeBuilderUnprovidedInitializationError -> FirErrors.UNPROVIDED_SAFE_BUILDER_INITIALIZATION.on(source, target, property)
            is SafeBuilderUnprovidedInvocationError -> FirErrors.UNPROVIDED_SAFE_BUILDER_INVOCATION.on(source, target, function)
            is SafeBuilderTargetLeakError -> FirErrors.SAFE_BUILDER_TARGET_LEAK.on(source, target)
            else -> null
        }
    }
}