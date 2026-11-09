package dev.sonicbrief.app.data.media

import java.net.URI

enum class PublicMediaPlatform {
    YOUTUBE,
    BILIBILI,
    GENERIC,
}

data class ValidatedPublicMediaUrl(
    val normalizedUrl: String,
    val platform: PublicMediaPlatform,
)

object PublicMediaUrlValidator {
    private const val MAX_URL_LENGTH = 8_192

    fun validate(rawValue: String): Result<ValidatedPublicMediaUrl> = runCatching {
        val value = rawValue.trim()
        require(value.isNotEmpty()) { "Enter a public media URL." }
        require(value.length <= MAX_URL_LENGTH) { "The media URL is too long." }

        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Enter a valid public media URL.") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            "Only public HTTP or HTTPS media URLs are supported."
        }
        require(uri.userInfo.isNullOrEmpty()) {
            "Media URLs containing embedded credentials are not supported."
        }

        val host = uri.host?.trim()?.lowercase()?.trimEnd('.')
        require(!host.isNullOrEmpty()) { "The media URL must include a public host name." }
        require(!isObviouslyNonPublicHost(host)) {
            "Local or private-network media URLs are not supported."
        }

        val platform = when {
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ->
                PublicMediaPlatform.YOUTUBE

            host == "b23.tv" || host.endsWith(".b23.tv") ||
                host == "bilibili.com" || host.endsWith(".bilibili.com") ->
                PublicMediaPlatform.BILIBILI

            else -> PublicMediaPlatform.GENERIC
        }

        val normalizedPort = when {
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val port = if (normalizedPort >= 0) ":$normalizedPort" else ""
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()

        ValidatedPublicMediaUrl(
            normalizedUrl = "$scheme://$renderedHost$port$path$query",
            platform = platform,
        )
    }

    private fun isObviouslyNonPublicHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
        if (
            normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized == "::1"
        ) {
            return true
        }
        // IPv6 literals contain ':'; only then are the fc00::/7 and fe80::/10 prefixes meaningful.
        // Matching them on plain host names would wrongly reject domains such as fdroid.org.
        if (normalized.contains(':') &&
            (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:"))
        ) {
            return true
        }

        val octets = normalized.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false

        val a = octets[0]!!
        val b = octets[1]!!
        return when {
            a == 0 -> true
            a == 10 -> true
            a == 100 && b in 64..127 -> true
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 198 && b in 18..19 -> true
            a >= 224 -> true
            else -> false
        }
    }
}
