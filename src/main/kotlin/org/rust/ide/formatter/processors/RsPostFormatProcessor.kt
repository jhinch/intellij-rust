/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.formatter.processors

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.ExternalFormatProcessor
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import org.rust.lang.core.psi.RsFile

class RsPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    @Suppress("UnstableApiUsage")
    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (source !is RsFile) return rangeToReformat
        val range = ExternalFormatProcessor.formatRangeInFile(source, rangeToReformat, false, false)
        return range ?: rangeToReformat
    }
}
