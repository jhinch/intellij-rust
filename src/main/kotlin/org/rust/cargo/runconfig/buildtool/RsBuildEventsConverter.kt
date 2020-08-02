/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

@file:Suppress("UnstableApiUsage")

package org.rust.cargo.runconfig.buildtool

import com.google.common.annotations.VisibleForTesting
import com.google.gson.JsonObject
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.StartEvent
import com.intellij.build.events.impl.*
import com.intellij.build.output.BuildOutputInstantReader
import com.intellij.build.output.BuildOutputParser
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.webcore.util.JsonUtil.tryParseJsonObject
import org.rust.cargo.runconfig.RsAnsiEscapeDecoder.Companion.quantizeAnsiColors
import org.rust.cargo.runconfig.removeEscapeSequences
import org.rust.cargo.toolchain.CargoTopMessage
import org.rust.cargo.toolchain.RustcMessage
import org.rust.cargo.toolchain.impl.CargoMetadata
import java.nio.file.Paths
import java.util.function.Consumer

class RsBuildEventsConverter(private val context: CargoBuildContext) : BuildOutputParser {
    private val decoder: AnsiEscapeDecoder = AnsiEscapeDecoder()
    private val startEvents: MutableList<StartEvent> = mutableListOf()
    private val messageEvents: MutableSet<MessageEvent> = hashSetOf()
    private val jsonBuffer: StringBuilder = StringBuilder()

    override fun parse(
        line: String,
        reader: BuildOutputInstantReader,
        messageConsumer: Consumer<in BuildEvent>
    ): Boolean {
        if (jsonBuffer.isEmpty() && "{\"reason\"" !in line) {
            return tryHandleCargoMessage(line, messageConsumer)
        }

        jsonBuffer.append(line.withNewLine())
        val message = jsonBuffer.dropWhile { it != '{' }.toString()
        val jsonObject = tryParseJsonObject(message)
        if (jsonObject == null) {
            return false
        } else {
            jsonBuffer.clear()
        }

        return tryHandleRustcMessage(jsonObject, messageConsumer) || tryHandleRustcArtifact(jsonObject)
    }

    private fun tryHandleRustcMessage(jsonObject: JsonObject, messageConsumer: Consumer<in BuildEvent>): Boolean {
        val topMessage = CargoTopMessage.fromJson(jsonObject) ?: return false
        val rustcMessage = topMessage.message

        val detailedMessage = rustcMessage.rendered
        if (detailedMessage != null) {
            messageConsumer.acceptText(context.buildId, detailedMessage.withNewLine())
        }

        val message = rustcMessage.message.trim().capitalize().trimEnd('.')
        if (message.startsWith("Aborting due")) return true

        val parentEventId = topMessage.package_id.substringBefore("(").trimEnd()

        val kind = getMessageKind(rustcMessage.level)
        if (kind == MessageEvent.Kind.SIMPLE) return true

        val filePosition = getFilePosition(rustcMessage)
        val messageEvent = createMessageEvent(parentEventId, kind, message, detailedMessage, filePosition)
        if (messageEvents.add(messageEvent)) {
            if (startEvents.none { it.id == parentEventId }) {
                handleCompilingMessage("Compiling $parentEventId", true, messageConsumer)
            }

            messageConsumer.accept(messageEvent)

            if (kind == MessageEvent.Kind.ERROR) {
                context.errors += 1
            } else {
                context.warnings += 1
            }
        }

        return true
    }

    private fun tryHandleRustcArtifact(jsonObject: JsonObject): Boolean {
        val rustcArtifact = CargoMetadata.Artifact.fromJson(jsonObject) ?: return false

        val isSuitableTarget = when (rustcArtifact.target.cleanKind) {
            CargoMetadata.TargetKind.BIN -> true
            CargoMetadata.TargetKind.EXAMPLE -> {
                // TODO: support cases when crate types list contains not only binary
                rustcArtifact.target.cleanCrateTypes.singleOrNull() == CargoMetadata.CrateType.BIN
            }
            CargoMetadata.TargetKind.TEST -> true
            CargoMetadata.TargetKind.LIB -> rustcArtifact.profile.test
            else -> false
        }
        if (!isSuitableTarget || context.isTestBuild && !rustcArtifact.profile.test) return true

        rustcArtifact.executables.mapTo(context.binaries) { Paths.get(it) }

        return true
    }

    private fun tryHandleCargoMessage(line: String, messageConsumer: Consumer<in BuildEvent>): Boolean {
        val cleanLine = decoder.removeEscapeSequences(line)
        if (cleanLine.isEmpty()) return true

        val kind = getMessageKind(cleanLine.substringBefore(":"))
        val message = cleanLine
            .let { if (kind in ERROR_OR_WARNING) it.substringAfter(":") else it }
            .removePrefix(" internal compiler error:")
            .trim()
            .capitalize()
            .trimEnd('.')
        when {
            message.startsWith("Compiling") ->
                handleCompilingMessage(message, false, messageConsumer)
            message.startsWith("Fresh") ->
                handleCompilingMessage(message, true, messageConsumer)

            message.startsWith("Building") -> {
                handleProgressMessage(cleanLine, messageConsumer)
                return true // don't print the progress bar
            }
            message.startsWith("Downloading") || message.startsWith("Checkout") || message.startsWith("Fetch") -> {
                return true // don't print the progress bar
            }

            message.startsWith("Finished") ->
                handleFinishedMessage(null, messageConsumer)
            message.startsWith("Could not compile") -> {
                val taskName = message.substringAfter("`").substringBefore("`")
                handleFinishedMessage(taskName, messageConsumer)
            }

            kind in ERROR_OR_WARNING ->
                handleProblemMessage(kind, message, cleanLine, messageConsumer)
        }

        messageConsumer.acceptText(context.buildId, line.withNewLine())
        return true
    }

    private fun handleCompilingMessage(
        originalMessage: String,
        isUpToDate: Boolean,
        messageConsumer: Consumer<in BuildEvent>
    ) {
        val message = originalMessage.replace("Fresh", "Compiling").substringBefore("(").trimEnd()
        val eventId = message.substringAfter(" ").replace(" v", " ")
        val startEvent = StartEventImpl(eventId, context.buildId, System.currentTimeMillis(), message)
        messageConsumer.accept(startEvent)
        if (isUpToDate) {
            val finishEvent = FinishEventImpl(
                eventId,
                context.buildId,
                System.currentTimeMillis(),
                message,
                SuccessResultImpl(isUpToDate)
            )
            messageConsumer.accept(finishEvent)
        } else {
            startEvents.add(startEvent)
        }
    }

    private fun handleProgressMessage(message: String, messageConsumer: Consumer<in BuildEvent>) {

        fun finishNonActiveTasks(activeTaskNames: List<String>) {
            val startEventsIterator = startEvents.iterator()
            while (startEventsIterator.hasNext()) {
                val startEvent = startEventsIterator.next()
                val taskName = startEvent.taskName
                if (taskName !in activeTaskNames) {
                    startEventsIterator.remove()
                    val finishEvent = FinishEventImpl(
                        startEvent.id,
                        context.buildId,
                        System.currentTimeMillis(),
                        startEvent.message,
                        SuccessResultImpl()
                    )
                    messageConsumer.accept(finishEvent)
                }
            }
        }

        fun parseProgress(message: String): Progress {
            val result = PROGRESS_TOTAL_RE.find(message)?.destructured
            val current = result?.component1()?.toLongOrNull() ?: -1
            val total = result?.component2()?.toLongOrNull() ?: -1
            return Progress(current, total)
        }

        fun updateIndicator(indicator: ProgressIndicator, title: String, description: String, progress: Progress) {
            indicator.isIndeterminate = progress.total < 0
            indicator.text = title
            indicator.text2 = description
            indicator.fraction = progress.fraction
        }

        val activeTaskNames = message
            .substringAfter(":")
            .split(",")
            .map { it.substringBefore("(").trim() }
        finishNonActiveTasks(activeTaskNames)

        updateIndicator(
            context.indicator,
            context.progressTitle,
            message.substringAfter(":").trim(),
            parseProgress(message)
        )
    }

    private fun handleFinishedMessage(failedTaskName: String?, messageConsumer: Consumer<in BuildEvent>) {
        for (startEvent in startEvents) {
            val finishEvent = FinishEventImpl(
                startEvent.id,
                context.buildId,
                System.currentTimeMillis(),
                startEvent.message,
                when (failedTaskName) {
                    startEvent.taskName -> FailureResultImpl(null as Throwable?)
                    null -> SuccessResultImpl()
                    else -> SkippedResultImpl()
                }
            )
            messageConsumer.accept(finishEvent)
        }
    }

    private fun handleProblemMessage(
        kind: MessageEvent.Kind,
        message: String,
        detailedMessage: String?,
        messageConsumer: Consumer<in BuildEvent>
    ) {
        if (message in MESSAGES_TO_IGNORE) return
        val messageEvent = createMessageEvent(context.buildId, kind, message, detailedMessage)
        if (messageEvents.add(messageEvent)) {
            messageConsumer.accept(messageEvent)
            if (kind == MessageEvent.Kind.ERROR) {
                context.errors += 1
            } else {
                context.warnings += 1
            }
        }
    }

    private fun getFilePosition(message: RustcMessage): FilePosition? {
        val span = message.mainSpan ?: return null
        val filePath = run {
            var filePath = Paths.get(span.file_name)
            if (!filePath.isAbsolute) {
                filePath = context.workingDirectory.resolve(filePath)
            }
            filePath
        }
        return FilePosition(
            filePath.toFile(),
            span.line_start - 1,
            span.column_start - 1,
            span.line_end - 1,
            span.column_end - 1
        )
    }

    companion object {
        @VisibleForTesting
        const val RUSTC_MESSAGE_GROUP: String = "Rust compiler"

        private val PROGRESS_TOTAL_RE: Regex = """(\d+)/(\d+)""".toRegex()

        private val MESSAGES_TO_IGNORE: List<String> =
            listOf("Build failed, waiting for other jobs to finish", "Build failed")

        private val ERROR_OR_WARNING: List<MessageEvent.Kind> =
            listOf(MessageEvent.Kind.ERROR, MessageEvent.Kind.WARNING)

        private val StartEvent.taskName: String?
            get() = (id as? String)?.substringBefore(" ")?.substringBefore("(")?.trimEnd()

        private fun String.withNewLine(): String = if (endsWith('\n')) this else this + '\n'

        private fun Consumer<in BuildEvent>.acceptText(parentId: Any?, text: String) =
            accept(OutputBuildEventImpl(parentId, quantizeAnsiColors(text), true))

        private fun getMessageKind(kind: String): MessageEvent.Kind =
            when (kind) {
                "error", "error: internal compiler error" -> MessageEvent.Kind.ERROR
                "warning" -> MessageEvent.Kind.WARNING
                else -> MessageEvent.Kind.SIMPLE
            }

        private fun createMessageEvent(
            parentEventId: Any,
            kind: MessageEvent.Kind,
            message: String,
            detailedMessage: String?,
            filePosition: FilePosition? = null
        ): MessageEvent = if (filePosition == null) {
            MessageEventImpl(parentEventId, kind, RUSTC_MESSAGE_GROUP, message, detailedMessage)
        } else {
            FileMessageEventImpl(parentEventId, kind, RUSTC_MESSAGE_GROUP, message, detailedMessage, filePosition)
        }

        private data class Progress(val current: Long, val total: Long) {
            val fraction: Double = current.toDouble() / total
        }
    }
}
