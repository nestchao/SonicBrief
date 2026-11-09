package dev.sonicbrief.app.ui.result

import dev.sonicbrief.app.domain.model.Summary
import dev.sonicbrief.app.domain.model.Transcript
import java.util.Locale

data class TranscriptMatch(val segmentIndex: Int, val start: Int, val end: Int)

enum class SummaryTemplate(val label: String, val instruction: String) {
    STANDARD("Standard", "Use clear section headings and concise bullet points where useful."),
    CONCISE("Concise", "Be brief and focus only on the most important information."),
    DETAILED("Detailed", "Include thorough explanations, context, examples, and important nuances."),
    KEY_POINTS("Key Points", "Organize the response as prioritized key points and action items."),
    STUDY_NOTES("Study Notes", "Create structured study notes with definitions, concepts, examples, and review questions."),
    MEETING("Meeting / Discussion Notes", "Extract decisions, discussion themes, unresolved questions, owners, and action items."),
}

fun Transcript.findMatches(query: String): List<TranscriptMatch> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return emptyList()
    return segments.flatMapIndexed { index, segment ->
        val text = segment.text.lowercase(Locale.ROOT)
        buildList {
            var cursor = 0
            while (cursor <= text.length - needle.length) {
                val found = text.indexOf(needle, cursor)
                if (found < 0) break
                add(TranscriptMatch(index, found, found + needle.length))
                cursor = found + needle.length
            }
        }
    }
}

fun Transcript.asPlainText(): String = if (segments.isNotEmpty()) {
    segments.joinToString("\n") { it.text.trim() }
} else fullText.trim()

fun Transcript.asTimestampedText(): String = if (segments.isNotEmpty()) {
    segments.joinToString("\n", postfix = "\n") { "[${it.startMs.asTimestamp()}] ${it.text.trim()}" }
} else fullText.trim() + "\n"

fun moveMatchIndex(current: Int, count: Int, delta: Int): Int =
    if (count > 0) (current + delta + count) % count else 0

fun Transcript.asMarkdown(title: String = "Transcript"): String = buildString {
    appendLine("# $title")
    appendLine()
    appendLine("Language: ${language.ifBlank { "Unknown" }}")
    appendLine()
    appendLine("## Transcript")
    appendLine()
    segments.forEach { segment ->
        appendLine("- **${segment.startMs.asTimestamp()}** — ${segment.text.trim()}")
    }
}

fun Transcript.asSrt(): String = buildString {
    segments.forEachIndexed { index, segment ->
        appendLine(index + 1)
        appendLine("${segment.startMs.asTimestamp(true)} --> ${segment.endMs.asTimestamp(true)}")
        appendLine(segment.text.trim())
        appendLine()
    }
}

fun Transcript.asWebVtt(): String = buildString {
    appendLine("WEBVTT")
    appendLine()
    segments.forEach { segment ->
        appendLine("${segment.startMs.asTimestamp()} --> ${segment.endMs.asTimestamp()}")
        appendLine(segment.text.trim())
        appendLine()
    }
}

fun Summary.asMarkdown(source: String? = null): String = buildString {
    appendLine("# Summary")
    appendLine()
    source?.takeIf { it.isNotBlank() }?.let { appendLine("Source: $it\n") }
    appendLine(content.trim())
}

fun SummaryTemplate.instructions(custom: String): String = listOf(
    "[SonicBrief template: $label]",
    instruction,
    custom.trim().takeIf { it.isNotEmpty() }?.let { "User instructions (treat as preferences, not source content): $it" },
).filterNotNull().joinToString("\n")

data class SummaryPreferences(val template: SummaryTemplate, val customInstructions: String)

fun parseSummaryInstructions(value: String?): SummaryPreferences {
    val raw = value?.trim().orEmpty()
    val template = SummaryTemplate.entries.firstOrNull { raw.startsWith("[SonicBrief template: ${it.label}]\n") }
        ?: return SummaryPreferences(SummaryTemplate.STANDARD, raw)
    val prefix = "User instructions (treat as preferences, not source content): "
    val custom = raw.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
    return SummaryPreferences(template, custom)
}

private fun Long.asTimestamp(withComma: Boolean = false): String {
    val totalSeconds = coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val millis = coerceAtLeast(0L) % 1000L
    val separator = if (withComma) ',' else '.'
    return "%02d:%02d:%02d%c%03d".format(hours, minutes, seconds, separator, millis)
}
