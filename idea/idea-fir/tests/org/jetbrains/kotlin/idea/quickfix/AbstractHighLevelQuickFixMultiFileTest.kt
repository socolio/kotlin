/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.uitls.IgnoreTests
import java.nio.file.Paths

abstract class AbstractHighLevelQuickFixMultiFileTest : AbstractQuickFixMultiFileTest() {
    override fun doTestWithExtraFile(beforeFileName: String) {
        IgnoreTests.runTestIfEnabledByFileDirective(
            Paths.get(beforeFileName),
            enableTestDirective = IgnoreTests.DIRECTIVES.FIR_COMPARISON_MULTILINE_COMMENT,
            directivePosition = IgnoreTests.DirectivePosition.LAST_LINE_IN_FILE
        ) {
            super.doTestWithExtraFile(beforeFileName)
        }
    }

    override val captureExceptions: Boolean = false

    override fun checkForUnexpectedErrors(file: KtFile) {}

    override fun checkAvailableActionsAreExpected(file: PsiFile, actions: Collection<IntentionAction>) {}
}