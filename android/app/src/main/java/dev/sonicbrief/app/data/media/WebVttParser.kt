package dev.sonicbrief.app.data.media

internal object WebVttParser {
    data class Cue(
        val sequence: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    data class Parsed(
        val fullText: String,
        val cues: List<Cue>,
    )

    fun parse(content: String): Parsed {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val blocks = normalized.split(Regex("\\n\\s*\\n"))
        val cues = mutableListOf<Cue>()

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }
            if (lines.isEmpty()) continue

            val timingIndex = lines.indexOfFirst { it.contains("-->") }
            if (timingIndex < 0) continue

            val timingParts = lines[timingIndex].split("-->", limit = 2)
            if (timingParts.size != 2) continue
            val startMs = parseTimestampMs(timingParts[0].trim()) ?: continue
            val endToken = timingParts[1].trim().substringBefore(' ')
            val endMs = parseTimestampMs(endToken) ?: continue

            val cueText = lines
                .drop(timingIndex + 1)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replace(Regex("<[^>]+>"), "")
                .decodeHtmlEntities()
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cueText.isBlank()) continue

            val previous = cues.lastOrNull()
            if (previous != null && previous.text == cueText && previous.endMs >= startMs) {
                cues[cues.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, endMs))
                continue
            }

            cues += Cue(
                sequence = cues.size,
                startMs = startMs,
                endMs = maxOf(startMs, endMs),
                text = cueText,
            )
        }

        require(cues.isNotEmpty()) { "The selected subtitle track contained no readable cues." }
        val fullText = cues.joinToString("\n") { it.text }.trim()
        require(fullText.isNotBlank()) { "The selected subtitle track contained no readable text." }
        return Parsed(fullText = fullText, cues = cues)
    }

    private fun parseTimestampMs(value: String): Long? {
        val timestamp = value.trim().substringBefore(' ')
        val parts = timestamp.split(':')
        if (parts.size !in 2..3) return null

        val hours: Long
        val minutes: Long
        val secondsPart: String
        if (parts.size == 3) {
            hours = parts[0].toLongOrNull() ?: return null
            minutes = parts[1].toLongOrNull() ?: return null
            secondsPart = parts[2]
        } else {
            hours = 0L
            minutes = parts[0].toLongOrNull() ?: return null
            secondsPart = parts[1]
        }

        val secondPieces = secondsPart.replace(',', '.').split('.', limit = 2)
        val seconds = secondPieces[0].toLongOrNull() ?: return null
        val millis = secondPieces.getOrNull(1)
            ?.padEnd(3, '0')
            ?.take(3)
            ?.toLongOrNull()
            ?: 0L

        if (minutes !in 0..59 || seconds !in 0..59) return null
        return (((hours * 60L + minutes) * 60L + seconds) * 1000L) + millis
    }

    private fun String.decodeHtmlEntities(): String = this
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
