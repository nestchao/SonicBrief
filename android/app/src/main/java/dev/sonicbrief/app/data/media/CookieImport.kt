package dev.sonicbrief.app.data.media

/**
 * Keeps an imported Netscape cookies.txt limited to the sites SonicBrief downloads from
 * (mirrors the desktop app's backend/download_settings.py behaviour), so unrelated logins are never stored.
 */
object CookieImport {
    const val MAX_BYTES = 1_000_000
    private val allowedSuffixes = listOf(
        "youtube.com", "google.com", "googlevideo.com", "youtu.be", "bilibili.com", "b23.tv",
    )
    private const val HEADER = "# Netscape HTTP Cookie File"

    /** Returns the filtered file text, or null if nothing usable remained. */
    fun filter(raw: String): String? {
        val kept = raw.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { line -> line.isNotBlank() && !(line.startsWith("#") && !line.startsWith("#HttpOnly_")) }
            .filter { line ->
                val domain = line.removePrefix("#HttpOnly_").split('\t').firstOrNull()?.trim()?.trimStart('.')?.lowercase()
                    ?: return@filter false
                line.split('\t').size >= 7 && allowedSuffixes.any { domain == it || domain.endsWith(".$it") }
            }
            .toList()
        if (kept.isEmpty()) return null
        return (listOf(HEADER) + kept).joinToString("\n", postfix = "\n")
    }
}
