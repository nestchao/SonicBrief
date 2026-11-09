package dev.sonicbrief.app.data.media

object VisualSamplingPlanner {
    const val MAX_VISUAL_FRAMES = 24
    const val MIN_VISUAL_INTERVAL_MS = 5_000L
    const val MAX_VISUAL_INTERVAL_MS = 60_000L

    fun timestamps(
        durationMs: Long?,
        maxFrames: Int = MAX_VISUAL_FRAMES,
    ): List<Long> {
        if (durationMs == null || durationMs <= 0L || maxFrames <= 0) return emptyList()

        val intervalMs = (durationMs / maxFrames)
            .coerceAtLeast(1L)
            .coerceIn(MIN_VISUAL_INTERVAL_MS, MAX_VISUAL_INTERVAL_MS)

        val candidates = buildList {
            var timestamp = 0L
            while (timestamp < durationMs) {
                add(timestamp)
                timestamp += intervalMs
            }
        }

        if (candidates.size <= maxFrames) return candidates
        if (maxFrames == 1) return listOf(candidates.first())

        val lastIndex = candidates.lastIndex
        return (0 until maxFrames)
            .map { index ->
                val sourceIndex = ((index.toDouble() * lastIndex) / (maxFrames - 1))
                    .toInt()
                    .coerceIn(0, lastIndex)
                candidates[sourceIndex]
            }
            .distinct()
            .take(maxFrames)
    }
}
