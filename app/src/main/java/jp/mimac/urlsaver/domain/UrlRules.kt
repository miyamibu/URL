package jp.mimac.urlsaver.domain

import android.content.Intent
import java.net.URI
import java.util.Locale

object UrlRules {
    fun extractFromIntent(intent: Intent): ShareExtractionResult {
        val sources = extractCandidateSources(intent)
        var hasAnyCandidateString = false

        for (sourceCandidates in sources) {
            if (sourceCandidates.isEmpty()) continue
            hasAnyCandidateString = true

            val validInSource = findFirstValidUrl(sourceCandidates)
            if (validInSource != null) {
                return ShareExtractionResult.Found(validInSource)
            }
        }

        return if (hasAnyCandidateString) {
            ShareExtractionResult.InvalidUrl
        } else {
            ShareExtractionResult.NoUrlFound
        }
    }

    fun countValidUrlsInIntent(intent: Intent): Int {
        return extractAllFromIntent(intent).size
    }

    fun extractAllFromIntent(intent: Intent): List<String> {
        val normalizedUrls = linkedSetOf<String>()
        for (sourceCandidates in extractCandidateSources(intent)) {
            for (candidateText in sourceCandidates) {
                for (candidateUrl in extractUrlCandidates(candidateText)) {
                    val normalized = normalize(candidateUrl) ?: continue
                    normalizedUrls += normalized
                }
            }
        }
        return normalizedUrls.toList()
    }

    fun extractForManualInput(input: String): ShareExtractionResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ShareExtractionResult.NoUrlFound
        val valid = findFirstValidUrl(listOf(trimmed))
        return if (valid != null) {
            ShareExtractionResult.Found(valid)
        } else {
            if (extractUrlCandidates(trimmed).isEmpty()) {
                ShareExtractionResult.NoUrlFound
            } else {
                ShareExtractionResult.InvalidUrl
            }
        }
    }

    fun parseUrl(original: String): ParsedUrl? {
        val normalized = normalize(original) ?: return null
        val uri = URI(normalized)
        val host = uri.host.orEmpty()
        val service = classifyService(host)
        val contentContext = classifyContent(service, uri)
        val display = toDisplayUrl(normalized, service)
        return ParsedUrl(
            originalUrl = original,
            normalizedUrl = normalized,
            displayUrl = display,
            openUrl = normalized,
            normalizedHost = host,
            rawSourceHost = host,
            serviceType = service,
            contentContext = contentContext,
        )
    }

    fun normalize(raw: String): String? {
        val value = raw.trim()
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!uri.isAbsolute) return null

        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val userInfo = uri.userInfo

        val port = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }

        var path = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (path != "/" && path.endsWith("/")) {
            path = path.trimEnd('/')
            if (path.isEmpty()) path = "/"
        }

        return try {
            URI(
                scheme,
                userInfo,
                host,
                port,
                path,
                uri.rawQuery,
                null,
            ).toASCIIString()
        } catch (_: Exception) {
            null
        }
    }

    fun toDisplayUrl(normalizedUrl: String, service: ServiceType): String {
        val uri = URI(normalizedUrl)
        val host = uri.host.orEmpty()
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = when (service) {
            ServiceType.YOUTUBE -> extractYoutubeQuery(uri.rawQuery)
            else -> null
        }
        return buildString {
            append(host)
            append(path)
            if (!query.isNullOrEmpty()) {
                append('?')
                append(query)
            }
        }
    }

    private fun extractYoutubeQuery(query: String?): String? {
        if (query.isNullOrEmpty()) return null
        val keep = query.split('&').firstOrNull { it.startsWith("v=") }
        return keep?.takeIf { it.length > 2 }
    }

    private fun findFirstValidUrl(sourceCandidates: List<String>): String? {
        for (candidateText in sourceCandidates) {
            val extracted = extractUrlCandidates(candidateText)
            for (candidateUrl in extracted) {
                if (normalize(candidateUrl) != null) {
                    return candidateUrl
                }
            }
        }
        return null
    }

    private fun extractUrlCandidates(text: String): List<String> {
        return URL_CANDIDATE_REGEX.findAll(text).map { it.value }.toList()
    }

    private fun extractCandidateSources(intent: Intent): List<List<String>> {
        val extraCandidates = mutableListOf<String>().apply {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::add)
            intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.forEach(::add)
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                ?.forEach { add(it.toString()) }
        }
        val clipCandidates = mutableListOf<String>().apply {
            val clip = intent.clipData ?: return@apply
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                item.text?.toString()?.let(::add)
                item.uri?.toString()?.let(::add)
            }
        }
        @Suppress("DEPRECATION")
        val streamCandidates = mutableListOf<String>().apply {
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                ?.forEach { add(it.toString()) }
        }
        val dataCandidates = mutableListOf<String>().apply {
            intent.dataString?.let(::add)
        }
        return listOf(extraCandidates, clipCandidates, streamCandidates, dataCandidates)
    }

    private fun classifyService(host: String): ServiceType {
        val lowered = host.lowercase(Locale.ROOT)
        return when {
            lowered.endsWith("youtube.com") || lowered == "youtu.be" -> ServiceType.YOUTUBE
            lowered.endsWith("tiktok.com") -> ServiceType.TIKTOK
            lowered == "x.com" || lowered.endsWith("twitter.com") -> ServiceType.X
            lowered.endsWith("instagram.com") -> ServiceType.INSTAGRAM
            else -> ServiceType.WEB
        }
    }

    private fun classifyContent(service: ServiceType, uri: URI): ContentContext {
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        return when (service) {
            ServiceType.YOUTUBE -> when {
                path.startsWith("/shorts/") -> ContentContext.SHORTS
                path.startsWith("/live/") -> ContentContext.LIVE
                path.startsWith("/watch") || uri.query?.contains("v=") == true -> ContentContext.VIDEO
                else -> ContentContext.STANDARD
            }
            ServiceType.TIKTOK -> when {
                path.contains("/video/") -> ContentContext.VIDEO
                path.contains("/music/") -> ContentContext.MUSIC
                path.contains("/tag/") -> ContentContext.HASHTAG
                else -> ContentContext.STANDARD
            }
            ServiceType.X -> when {
                path.contains("/status/") -> ContentContext.POST
                else -> ContentContext.STANDARD
            }
            ServiceType.INSTAGRAM -> when {
                path.startsWith("/reel/") -> ContentContext.REEL
                path.startsWith("/p/") -> ContentContext.POST
                path.startsWith("/@") -> ContentContext.PROFILE
                else -> ContentContext.STANDARD
            }
            ServiceType.WEB -> ContentContext.STANDARD
            ServiceType.ALL -> ContentContext.STANDARD
        }
    }

    fun effectiveTitle(
        userTitle: String?,
        fetchedTitle: String?,
        serviceType: ServiceType,
        normalizedHost: String,
    ): String {
        return when {
            !userTitle.isNullOrBlank() -> userTitle
            !fetchedTitle.isNullOrBlank() -> fetchedTitle
            serviceType != ServiceType.WEB -> "${serviceType.displayName}のリンク"
            normalizedHost.isNotBlank() -> normalizedHost
            else -> "保存したリンク"
        }
    }

    fun normalizeUserTitle(input: String?): String? {
        val trimmed = input?.trim() ?: return null
        if (trimmed.isBlank()) return null
        if (trimmed.length > 120) return null
        return trimmed
    }

    fun normalizeMemo(input: String?): String {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        return trimmed
    }

    fun isMemoLengthValid(input: String): Boolean = input.trim().length <= 2000
    fun isTitleLengthValid(input: String): Boolean = input.trim().length <= 120

    private val URL_CANDIDATE_REGEX = Regex("https?://[^\\s<>()\\[\\]\"']+")
}
