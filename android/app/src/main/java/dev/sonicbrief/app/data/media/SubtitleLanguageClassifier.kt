package dev.sonicbrief.app.data.media

internal data class ClassifiedSubtitleLanguages(
    val manual: List<String>,
    val automatic: List<String>,
)

internal fun classifySubtitleLanguages(
    platform: PublicMediaPlatform,
    subtitleLanguages: List<String>,
    automaticCaptionLanguages: List<String>,
): ClassifiedSubtitleLanguages {
    val normalizedSubtitles = subtitleLanguages
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    val normalizedAutomatic = automaticCaptionLanguages
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    val bilibiliAutomatic = if (platform == PublicMediaPlatform.BILIBILI) {
        normalizedSubtitles.filter { it.lowercase().startsWith("ai-") }
    } else {
        emptyList()
    }
    val manual = if (platform == PublicMediaPlatform.BILIBILI) {
        normalizedSubtitles.filterNot { it.lowercase().startsWith("ai-") }
    } else {
        normalizedSubtitles
    }

    return ClassifiedSubtitleLanguages(
        manual = manual.sorted().take(50),
        automatic = (normalizedAutomatic + bilibiliAutomatic)
            .distinct()
            .sorted()
            .take(50),
    )
}
