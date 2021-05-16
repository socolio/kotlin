/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.cfa.coeffect

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.cfa.TraverseDirection
import org.jetbrains.kotlin.fir.analysis.cfa.collectDataForNodeOptimized
import org.jetbrains.kotlin.fir.analysis.cfa.previousCfgNodes
import org.jetbrains.kotlin.fir.analysis.cfa.traverse
import org.jetbrains.kotlin.fir.analysis.checkers.cfa.FirControlFlowChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.contracts.contextual.CoeffectFamily
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.isLambda
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.CFGNode
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.resolve.dfa.contracts.createArgumentsMapping
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

class FirCoeffectAnalyzer(private vararg val familyAnalyzers: CoeffectFamilyAnalyzer) : FirControlFlowChecker() {

    override fun analyze(graph: ControlFlowGraph, reporter: DiagnosticReporter) {
        val function = graph.declaration as? FirFunction<*> ?: return

        familyAnalyzers.forEach { it.analyze(function, graph, reporter) }
        val familyAnalyzers = familyAnalyzers.associateBy { it.family }

        val rawContextOnNodes = graph.buildRawContext(function, familyAnalyzers)
        if (!rawContextOnNodes.hasVerifiers) return

        val contextOnNodes = graph.resolveContext(rawContextOnNodes)

        for ((node, nodeActions) in rawContextOnNodes) {
            val prevNode = node.previousCfgNodes.firstOrNull() ?: node
            val data = contextOnNodes[node] ?: continue
            val prevData = contextOnNodes[prevNode] ?: continue

            for ((family, familyActions) in nodeActions) {
                for (verifier in familyActions.verifiers) {
                    val (context, _) = (if (verifier.needVerifyOnCurrentNode) data else prevData)[family]
                    val errors = verifier.verifyContext(context, function.session)

                    for (error in errors) {
                        node.fir.source?.let {
                            val familyAnalyzer = familyAnalyzers[family]
                            val firError = familyAnalyzer?.getFirError(error, it)
                            reporter.report(firError)
                        }
                    }
                }
            }
        }
    }

    private fun ControlFlowGraph.buildRawContext(
        function: FirFunction<*>,
        familyAnalyzers: Map<CoeffectFamily, CoeffectFamilyAnalyzer>
    ): CoeffectRawContextOnNodes {
        val lambdaToOwnerFunction = function.collectLambdaOwnerFunctions()
        val collector = CoeffectRawContextBuilder(function, familyAnalyzers, lambdaToOwnerFunction)
        val data = CoeffectRawContextOnNodes()
        traverse(TraverseDirection.Forward, collector, data)
        return data
    }

    private fun ControlFlowGraph.resolveContext(rawContext: CoeffectRawContextOnNodes): Map<CFGNode<*>, CoeffectContextOnNodes> =
        collectDataForNodeOptimized(TraverseDirection.Forward, CoeffectContextOnNodes.EMPTY, CoeffectContextResolver(rawContext))

    private fun FirFunction<*>.collectLambdaOwnerFunctions(): Map<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>> {
        val lambdaToOwnerFunction = mutableMapOf<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>()
        body?.accept(LambdaToOwnerFunctionCollector(lambdaToOwnerFunction))
        return lambdaToOwnerFunction
    }

    private class LambdaToOwnerFunctionCollector(
        val lambdaToOwnerFunction: MutableMap<FirAnonymousFunction, Pair<FirFunction<*>, AbstractFirBasedSymbol<*>>>
    ) : FirVisitorVoid() {
        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val functionSymbol = functionCall.toResolvedCallableSymbol() ?: return
            val argumentMapping by lazy { createArgumentsMapping(functionCall)?.map { it.value to it.key }?.toMap() }
            val function = functionSymbol.fir as? FirFunction ?: return

            for (argument in functionCall.argumentList.arguments) {
                val expression = if (argument is FirWrappedArgumentExpression) argument.expression else argument
                if (expression.isLambda()) {
                    val lambdaParameterIndex = argumentMapping?.get(expression) ?: continue
                    val lambdaSymbol = function.valueParameters[lambdaParameterIndex].symbol

                    lambdaToOwnerFunction[expression] = function to lambdaSymbol
                }
            }
            super.visitFunctionCall(functionCall)
        }
    }
}