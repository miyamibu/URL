package jp.mimac.urlsaver.domain

import android.content.Intent
import java.security.MessageDigest
import java.net.URI
import java.util.Locale

object UrlRules {
    const val MAX_INPUT_TEXT_BYTES: Int = 256 * 1024
    const val MAX_EXTRACTED_URLS_PER_INPUT: Int = 50
    const val MAX_BATCH_SAVE_URLS_PER_INTAKE: Int = 50
    const val TEXT_CARD_HOST: String = "text.rinbam.local"

    data class ExtractedUrlBatch(
        val urls: List<String>,
        val truncatedToMaxUrls: Boolean,
        val sawOversizedInput: Boolean,
    )

    fun extractFromIntent(intent: Intent): ShareExtractionResult {
        val sources = extractCandidateSources(intent)
        var hasAnyCandidateString = false
        var sawOversizedInput = false

        for (sourceCandidates in sources) {
            if (sourceCandidates.isEmpty()) continue
            hasAnyCandidateString = true

            val validInSource = findFirstValidUrl(sourceCandidates)
            when (validInSource) {
                is ShareExtractionResult.Found -> return validInSource
                ShareExtractionResult.InputTooLarge -> sawOversizedInput = true
                ShareExtractionResult.InvalidUrl,
                ShareExtractionResult.NoUrlFound,
                null -> Unit
            }
        }

        return when {
            sawOversizedInput -> ShareExtractionResult.InputTooLarge
            hasAnyCandidateString -> ShareExtractionResult.InvalidUrl
            else -> ShareExtractionResult.NoUrlFound
        }
    }

    fun countValidUrlsInIntent(intent: Intent): Int {
        return extractAllFromIntent(intent).urls.size
    }

    fun extractAllFromIntent(intent: Intent): ExtractedUrlBatch {
        val normalizedUrls = linkedSetOf<String>()
        var truncatedToMaxUrls = false
        var sawOversizedInput = false

        outer@ for (sourceCandidates in extractCandidateSources(intent)) {
            for (candidateText in sourceCandidates) {
                if (!isWithinInputTextByteLimit(candidateText)) {
                    sawOversizedInput = true
                    continue
                }
                for (candidateUrl in extractUrlCandidates(candidateText)) {
                    val normalized = normalize(candidateUrl) ?: continue
                    val inserted = normalizedUrls.add(normalized)
                    if (!inserted) continue
                    if (normalizedUrls.size > MAX_EXTRACTED_URLS_PER_INPUT) {
                        normalizedUrls.remove(normalized)
                        truncatedToMaxUrls = true
                        break@outer
                    }
                }
            }
        }
        return ExtractedUrlBatch(
            urls = normalizedUrls.toList().take(MAX_BATCH_SAVE_URLS_PER_INTAKE),
            truncatedToMaxUrls = truncatedToMaxUrls || normalizedUrls.size > MAX_BATCH_SAVE_URLS_PER_INTAKE,
            sawOversizedInput = sawOversizedInput,
        )
    }

    fun extractForManualInput(input: String): ShareExtractionResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ShareExtractionResult.NoUrlFound
        if (!isWithinInputTextByteLimit(trimmed)) return ShareExtractionResult.InputTooLarge
        val valid = findFirstValidUrl(listOf(trimmed))
        return when (valid) {
            is ShareExtractionResult.Found -> valid
            ShareExtractionResult.InputTooLarge -> ShareExtractionResult.InputTooLarge
            ShareExtractionResult.InvalidUrl,
            ShareExtractionResult.NoUrlFound,
            null -> {
                if (extractUrlCandidates(trimmed).isEmpty()) {
                    ShareExtractionResult.NoUrlFound
                } else {
                    ShareExtractionResult.InvalidUrl
                }
            }
        }
    }

    fun extractTextFallbackFromIntent(intent: Intent): String? {
        return extractCandidateSources(intent)
            .asSequence()
            .flatten()
            .mapNotNull(::normalizeTextCardBody)
            .firstOrNull()
    }

    fun extractMemoWithoutUrlsFromIntent(intent: Intent): String? {
        return extractCandidateSources(intent)
            .asSequence()
            .flatten()
            .mapNotNull(::extractMemoWithoutUrls)
            .firstOrNull()
    }

    fun extractMemoWithoutUrls(input: String): String? {
        val normalized = normalizeTextCardBody(input) ?: return null
        val ranges = URL_CANDIDATE_REGEX
            .findAll(normalized)
            .filter { normalize(it.value) != null }
            .map { it.range }
            .toList()
        if (ranges.isEmpty()) return null

        val withoutUrls = buildString {
            var index = 0
            for (range in ranges) {
                if (index < range.first) {
                    append(normalized.substring(index, range.first))
                }
                append('\n')
                index = range.last + 1
            }
            if (index < normalized.length) {
                append(normalized.substring(index))
            }
        }
        val memo = withoutUrls
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
        if (memo.isBlank()) return null
        return memo.take(2000)
    }

    fun normalizeTextCardBody(input: String): String? {
        val normalized = input
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isEmpty()) return null
        if (!isWithinInputTextByteLimit(normalized)) return null
        return normalized
    }

    fun parseTextCard(input: String): ParsedUrl? {
        val body = normalizeTextCardBody(input) ?: return null
        val hash = sha256(body)
        val normalized = "https://$TEXT_CARD_HOST/note/$hash"
        return ParsedUrl(
            originalUrl = body,
            normalizedUrl = normalized,
            displayUrl = "テキスト",
            openUrl = normalized,
            normalizedHost = TEXT_CARD_HOST,
            rawSourceHost = TEXT_CARD_HOST,
            serviceType = ServiceType.WEB,
            contentContext = ContentContext.POST,
        )
    }

    fun isTextCardHost(host: String?): Boolean {
        return host?.equals(TEXT_CARD_HOST, ignoreCase = true) == true
    }

    fun textCardTitle(body: String): String {
        val firstLine = body
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: body.trim()
        return firstLine
            .replace(Regex("\\s+"), " ")
            .take(120)
            .ifBlank { "テキスト" }
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
        if (!uri.isAbsolute) return null

        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        if (scheme != "https" && !(scheme == "http" && isLoopbackHost(host))) {
            return null
        }
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

    fun toLegacyHttpTwin(normalizedUrl: String): String? {
        val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            URI(
                "http",
                uri.userInfo,
                host,
                uri.port,
                uri.rawPath,
                uri.rawQuery,
                null,
            ).toASCIIString()
        }.getOrNull()
    }

    fun metadataFetchUrls(openUrl: String, @Suppress("UNUSED_PARAMETER") serviceType: ServiceType): List<String> {
        return listOf(openUrl)
    }

    fun extractXStatusId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!isXStatusProviderHost(host)) return null
        return extractXStatusPath(uri)?.statusId
    }

    private fun extractYoutubeQuery(query: String?): String? {
        if (query.isNullOrEmpty()) return null
        val keep = query.split('&').firstOrNull { it.startsWith("v=") }
        return keep?.takeIf { it.length > 2 }
    }

    private fun findFirstValidUrl(sourceCandidates: List<String>): ShareExtractionResult? {
        var sawOversizedInput = false
        var scannedCandidateUrls = 0
        for (candidateText in sourceCandidates) {
            if (!isWithinInputTextByteLimit(candidateText)) {
                sawOversizedInput = true
                continue
            }
            val extracted = extractUrlCandidates(candidateText)
            for (candidateUrl in extracted) {
                scannedCandidateUrls += 1
                if (scannedCandidateUrls > MAX_EXTRACTED_URLS_PER_INPUT) {
                    return null
                }
                if (normalize(candidateUrl) != null) {
                    return ShareExtractionResult.Found(candidateUrl)
                }
            }
        }
        return if (sawOversizedInput) ShareExtractionResult.InputTooLarge else null
    }

    private fun extractUrlCandidates(text: String): List<String> {
        return URL_CANDIDATE_REGEX.findAll(text).map { it.value }.toList()
    }

    private fun isWithinInputTextByteLimit(text: String): Boolean {
        return text.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_TEXT_BYTES
    }

    private fun extractCandidateSources(intent: Intent): List<List<String>> {
        val extraCandidates = mutableListOf<String>().apply {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::add)
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::add)
            intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.forEach(::add)
            intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
                ?.forEach { add(it.toString()) }
            intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let(::add)
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let(::add)
            intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.let(::add)
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
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                ?.let { add(it.toString()) }
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                ?.forEach { add(it.toString()) }
        }
        val dataCandidates = mutableListOf<String>().apply {
            intent.dataString?.let(::add)
        }
        return listOf(extraCandidates, clipCandidates, streamCandidates, dataCandidates)
    }

    private fun isLoopbackHost(host: String): Boolean {
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isXHost(host: String): Boolean {
        val lowered = host.lowercase(Locale.ROOT)
        return lowered == "x.com" || lowered.endsWith("twitter.com")
    }

    private fun isXStatusProviderHost(host: String): Boolean {
        val lowered = host.lowercase(Locale.ROOT)
        return lowered == "x.com" ||
            lowered.endsWith("twitter.com")
    }

    private fun classifyService(host: String): ServiceType {
        val lowered = host.lowercase(Locale.ROOT)
        return when {
            lowered.endsWith("youtube.com") || lowered == "youtu.be" -> ServiceType.YOUTUBE
            lowered.endsWith("tiktok.com") -> ServiceType.TIKTOK
            isXHost(host) -> ServiceType.X
            lowered.endsWith("instagram.com") -> ServiceType.INSTAGRAM
            else -> ServiceType.WEB
        }
    }

    private fun extractXStatusPath(uri: URI): XStatusPath? {
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        if (segments.size >= 3 && segments[1].equals("status", ignoreCase = true)) {
            val screenName = segments[0].takeIf { it.isNotBlank() }
            val statusId = segments[2].takeIf { it.isNotBlank() } ?: return null
            return XStatusPath(screenName = screenName, statusId = statusId)
        }
        if (segments.size >= 4 &&
            segments[0].equals("i", ignoreCase = true) &&
            segments[1].equals("web", ignoreCase = true) &&
            segments[2].equals("status", ignoreCase = true)
        ) {
            val statusId = segments[3].takeIf { it.isNotBlank() } ?: return null
            return XStatusPath(screenName = null, statusId = statusId)
        }
        return null
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
        val titleService = if (serviceType == ServiceType.TIKTOK) ServiceType.WEB else serviceType
        return when {
            !userTitle.isNullOrBlank() -> userTitle
            !fetchedTitle.isNullOrBlank() -> fetchedTitle
            titleService != ServiceType.WEB -> "${titleService.displayName}のリンク"
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

    private data class XStatusPath(
        val screenName: String?,
        val statusId: String,
    )

    private val URL_CANDIDATE_REGEX = Regex("https?://[^\\s<>()\\[\\]\"']+", RegexOption.IGNORE_CASE)
}
