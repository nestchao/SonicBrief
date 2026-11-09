package dev.sonicbrief.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicMediaUrlValidatorTest {
    @Test
    fun youtubeUrlIsClassifiedAndFragmentIsRemoved() {
        val result = PublicMediaUrlValidator
            .validate(" HTTPS://WWW.YouTube.COM/watch?v=abc123#comments ")
            .getOrThrow()

        assertEquals(PublicMediaPlatform.YOUTUBE, result.platform)
        assertEquals("https://www.youtube.com/watch?v=abc123", result.normalizedUrl)
    }

    @Test
    fun bilibiliShortUrlIsClassified() {
        val result = PublicMediaUrlValidator
            .validate("https://b23.tv/AbCd123")
            .getOrThrow()

        assertEquals(PublicMediaPlatform.BILIBILI, result.platform)
        assertEquals("https://b23.tv/AbCd123", result.normalizedUrl)
    }

    @Test
    fun genericPublicHttpsUrlIsAccepted() {
        val result = PublicMediaUrlValidator
            .validate("https://media.example.com/video/file.mp4?token=abc")
            .getOrThrow()

        assertEquals(PublicMediaPlatform.GENERIC, result.platform)
        assertEquals(
            "https://media.example.com/video/file.mp4?token=abc",
            result.normalizedUrl,
        )
    }

    @Test
    fun nonHttpSchemeIsRejected() {
        val result = PublicMediaUrlValidator.validate("file:///sdcard/video.mp4")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP"))
    }

    @Test
    fun localhostAndPrivateIpv4AreRejected() {
        assertTrue(PublicMediaUrlValidator.validate("http://localhost/video").isFailure)
        assertTrue(PublicMediaUrlValidator.validate("http://192.168.1.20/video").isFailure)
        assertTrue(PublicMediaUrlValidator.validate("http://10.0.0.5/video").isFailure)
    }

    @Test
    fun embeddedCredentialsAreRejected() {
        val result = PublicMediaUrlValidator.validate("https://user:pass@example.com/video")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("credentials"))
    }

    @Test
    fun hostNamesStartingWithFcOrFdAreNotTreatedAsIpv6PrivateRanges() {
        assertEquals(
            "https://fdroid.org/packages",
            PublicMediaUrlValidator.validate("https://fdroid.org/packages").getOrThrow().normalizedUrl,
        )
        assertTrue(PublicMediaUrlValidator.validate("https://fc-media.example.com/a").isSuccess)
    }

    @Test
    fun ipv6PrivateLiteralsAreStillRejected() {
        assertTrue(PublicMediaUrlValidator.validate("http://[fd12:3456::1]/a").isFailure)
        assertTrue(PublicMediaUrlValidator.validate("http://[fe80::1]/a").isFailure)
        assertTrue(PublicMediaUrlValidator.validate("http://[::1]/a").isFailure)
    }
}
