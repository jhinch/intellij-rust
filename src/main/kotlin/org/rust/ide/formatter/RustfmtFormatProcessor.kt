/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.formatter

import com.intellij.application.options.CodeStyle
import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.UndoConstants
import com.intellij.openapi.util.TextRange
import com.intellij.openapiext.Testmark
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.ExternalFormatProcessor
import com.intellij.psi.impl.source.codeStyle.CodeFormatterFacade
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.Rustup.Companion.checkNeedInstallRustfmt
import org.rust.ide.notifications.showBalloon
import org.rust.lang.RsLanguage
import org.rust.lang.core.psi.RsFile
import org.rust.openapiext.document

@Suppress("UnstableApiUsage")
class RustfmtFormatProcessor : ExternalFormatProcessor {

    override fun getId(): String = "rustfmt"

    override fun activeForFile(source: PsiFile): Boolean =
        source is RsFile && source.project.rustSettings.useRustfmt

    override fun indent(source: PsiFile, lineStartOffset: Int): String? = null

    override fun format(
        source: PsiFile,
        range: TextRange,
        canChangeWhiteSpacesOnly: Boolean,
        keepLineBreaks: Boolean
    ): TextRange? = if (source.textRange == range && !canChangeWhiteSpacesOnly) {
        formatWithRustfmt(source, range)
    } else {
        // delegate unsupported cases to the built-in formatter
        formatWithBuiltin(source, range, canChangeWhiteSpacesOnly)
    }

    private fun formatWithRustfmt(source: PsiFile, range: TextRange): TextRange? {
        Testmarks.rustfmtUsed.hit()

        if (source !is RsFile) return null
        val file = source.virtualFile ?: return null
        val document = file.document ?: return null
        val project = source.project
        val cargoProject = project.cargoProjects.findProjectForFile(file) ?: return null
        val rustfmt = project.toolchain?.rustfmt() ?: return null

        val before = document.modificationStamp
        executeOnPooledThread {
            if (checkNeedInstallRustfmt(project, cargoProject.workingDirectory)) {
                return@executeOnPooledThread
            }

            val output = try {
                rustfmt.reformatDocumentText(cargoProject, document, suppressErrors = false)
            } catch (e: ExecutionException) {
                e.message?.let { project.showBalloon(it, NotificationType.ERROR) }
                null
            } ?: return@executeOnPooledThread

            if (output.exitCode == 0) {
                val text = output.stdout
                invokeLater {
                    val after = document.modificationStamp
                    if (after > before) return@invokeLater
                    CommandProcessor.getInstance().executeCommand(project, {
                        runWriteAction { document.setText(text) }
                        file.putUserData(UndoConstants.FORCE_RECORD_UNDO, null)
                    }, "Reformat Code with $id", null, document)
                }
            } else {
                project.showBalloon(output.stderr, NotificationType.ERROR)
            }
        }

        return range
    }

    private fun formatWithBuiltin(source: PsiFile, range: TextRange, canChangeWhiteSpacesOnly: Boolean): TextRange? {
        val document = source.document ?: return null
        val project = source.project
        val formatter = CodeFormatterFacade(CodeStyle.getSettings(source), RsLanguage, canChangeWhiteSpacesOnly)
        invokeLater {
            CommandProcessor.getInstance().executeCommand(project, {
                runWriteAction { formatter.processRange(source.node, range.startOffset, range.endOffset) }
            }, "Reformat Code", null, document)
        }
        return range
    }

    object Testmarks {
        val rustfmtUsed: Testmark = Testmark("rustfmtUsed")
    }
}

private fun executeOnPooledThread(runnable: () -> Unit) {
    if (isUnitTestMode) runnable() else ApplicationManager.getApplication().executeOnPooledThread(runnable)
}

private fun invokeLater(runnable: () -> Unit) {
    if (isUnitTestMode) runnable() else ApplicationManager.getApplication().invokeLater(runnable)
}
