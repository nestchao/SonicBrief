package dev.sonicbrief.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLanguageClassifierTest {
    @Test
    fun bilibiliAiLanguagesAreAutomaticCaptions() {
        val classified = classifySubtitleLanguages(
            platform = PublicMediaPlatform.BILIBILI,
            subtitleLanguages = listOf("zh-CN", "ai-zh", "ai-en"),
            automaticCaptionLanguages = emptyList(),
        )

        assertEquals(listOf("zh-CN"), classified.manual)
        assertEquals(listOf("ai-en", "ai-zh"), classified.automatic)
    }

    @Test
    fun youtubeKeepsNativeSubtitleAndAutomaticBucketsSeparate() {
        val classified = classifySubtitleLanguages(
            platform = PublicMediaPlatform.YOUTUBE,
            subtitleLanguages = listOf("en"),
            automaticCaptionLanguages = listOf("en-orig", "ms"),
        )

        assertEquals(listOf("en"), classified.manual)
        assertEquals(listOf("en-orig", "ms"), classified.automatic)
    }
}
