package jp.mimac.urlsaver.domain

import java.util.Locale

/** Shared title cleanup for fetched metadata and its display fallback. */
object MetadataTitleRules {
    private const val SHORT_TITLE_MAX_LENGTH = 100

    private val WHITESPACE = Regex("\\s+")
    private val INSTAGRAM_ENGAGEMENT_COUNT = Regex(
        """(?ix)(?:\b\d[\d,.]*(?:\s*[kmb])?\s*(?:likes?|comments?|views?|shares?|reactions?)(?=\s*(?:[,，、:|/•·-]|and\s+\d|\bon\s+instagram\b|$))|\d[\d,.]*(?:\s*(?:件|人|回))?(?:の)?\s*(?:いいね|コメント|再生|シェア|保存)(?=\s*(?:[,，、:|/•·-]|(?:instagram|インスタグラム)|$)))""",
    )
    private val INSTAGRAM_ENGAGEMENT_START = Regex(
        """(?ix)^\d[\d,.]*(?:\s*[kmb])?\s*(?:likes?|comments?|views?|shares?|reactions?)(?=\s*(?:[,，、:|/•·-]|and\s+\d|\bon\s+instagram\b|$))""",
    )
    private val INSTAGRAM_WRAPPER_SUFFIX = Regex(
        """(?i)(?:\s+on\s+Instagram(?::.*)?|\s*[|•·]\s*Instagram(?:\s+photos\s+and\s+videos)?)\s*$""",
    )
    private val INSTAGRAM_DATE_PREFIX = Regex(
        """(?ix)^(?:posted\s+on\s+)?(?:\d{4}[./-]\d{1,2}[./-]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日|(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+\d{1,2}(?:st|nd|rd|th)?[,]?\s+\d{4})""",
    )
    private val INSTAGRAM_BRAND_SUFFIX = Regex(
        """\s*[|•·]\s*Instagram(?:\s+photos\s+and\s+videos)?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val INSTAGRAM_PROFILE_DECORATION = Regex("\\s*\\(@[^)]{1,40}\\)\\s*$")
    private val INSTAGRAM_ENGLISH_CAPTION_ENVELOPE = Regex(
        """(?is)^\s*[\d,.KM万]+\s+likes?,\s*[\d,.KM万]+\s+comments?\s*[-–—]\s*.+?\s+on\s+.+?:\s*[\"“](.+)[\"”]\s*\.?\s*$""",
    )
    private val INSTAGRAM_JAPANESE_CAPTION_ENVELOPE = Regex(
        """(?is)^\s*いいね[！!]?\s*[\d,.万]+\s*件[、,]\s*コメント\s*[\d,.万]+\s*件\s*[-–—]\s*.+?:\s*[\"“「](.+)[\"”」]\s*\.?\s*$""",
    )
    private val INSTAGRAM_CAPTION_PREFIXES = listOf(
        Regex("""(?is)^\s*[\d,.KM万]+\s+likes?,\s*[\d,.KM万]+\s+comments?\s*[-–—]\s*.+?\s+on\s+.+?:\s*[\"“]"""),
        Regex("""(?is)^\s*いいね[！!]?\s*[\d,.万]+\s*件[、,]\s*コメント\s*[\d,.万]+\s*件\s*[-–—]\s*.+?:\s*[\"“「]"""),
    )

    fun normalizeInstagramTitle(raw: String?): String? {
        val normalized = normalizeWhitespace(raw)?.let { unwrapInstagramCaption(it) ?: it } ?: return null
        val lower = normalized.lowercase(Locale.ROOT)
        val mentionsInstagram = lower.contains("instagram") || lower.contains("インスタグラム")
        val hasEngagement = INSTAGRAM_ENGAGEMENT_COUNT.containsMatchIn(normalized)
        if (
            normalized.equals("Instagram", ignoreCase = true) ||
            normalized.equals("Instagram photos and videos", ignoreCase = true) ||
            normalized.equals("Join my group chat:", ignoreCase = true) ||
            INSTAGRAM_ENGAGEMENT_START.containsMatchIn(normalized) ||
            (INSTAGRAM_DATE_PREFIX.containsMatchIn(normalized) && (mentionsInstagram || hasEngagement))
        ) {
            return null
        }

        return normalizeWhitespace(normalized.replace(INSTAGRAM_BRAND_SUFFIX, ""))
            ?.takeUnless {
                it.equals("Instagram", ignoreCase = true) ||
                    it.equals("Instagram photos and videos", ignoreCase = true)
            }
    }

    fun isInstagramAuthorTitle(title: String?, authorName: String?): Boolean {
        val normalizedTitle = normalizeInstagramTitle(title) ?: return false
        val author = normalizeWhitespace(authorName)
        if (!author.isNullOrBlank() && comparableInstagramName(normalizedTitle) == comparableInstagramName(author)) {
            return true
        }
        return normalizedTitle.startsWith('@') ||
            INSTAGRAM_PROFILE_DECORATION.containsMatchIn(normalizedTitle) ||
            INSTAGRAM_WRAPPER_SUFFIX.containsMatchIn(normalizedTitle)
    }

    /** Unwraps only recognized provider-generated engagement envelopes. */
    fun unwrapInstagramCaption(raw: String?): String? {
        val normalized = normalizeWhitespace(raw) ?: return null
        val match = INSTAGRAM_ENGLISH_CAPTION_ENVELOPE.find(normalized)
            ?: INSTAGRAM_JAPANESE_CAPTION_ENVELOPE.find(normalized)
        if (match != null) return normalizeWhitespace(match.groupValues.getOrNull(1))
        // List projections can end before the closing quote of a long saved caption.
        val prefix = INSTAGRAM_CAPTION_PREFIXES.firstNotNullOfOrNull { it.find(normalized) } ?: return null
        return normalizeWhitespace(normalized.substring(prefix.range.last + 1))
    }

    /** Returns a compact sentence suitable for a card or detail title fallback. */
    fun shortContentTitle(vararg candidates: String?): String? {
        val source = candidates.asSequence()
            .mapNotNull { candidate ->
                normalizeWhitespace(candidate)?.let { unwrapInstagramCaption(it) ?: it }
            }
            .firstOrNull()
            ?: return null
        val withoutUrls = source
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), " ")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { source }
        val boundary = SENTENCE_BOUNDARY.find(withoutUrls)
        val sentence = if (boundary == null) {
            withoutUrls
        } else {
            withoutUrls.substring(0, boundary.range.last + 1).trim()
        }
        if (sentence.isBlank()) return null
        return if (sentence.length <= SHORT_TITLE_MAX_LENGTH) {
            sentence
        } else {
            val limit = SHORT_TITLE_MAX_LENGTH - 1
            val safeLimit = if (sentence[limit - 1].isHighSurrogate() && sentence[limit].isLowSurrogate()) {
                limit - 1
            } else {
                limit
            }
            sentence.take(safeLimit).trimEnd() + "…"
        }
    }

    private fun comparableInstagramName(raw: String): String {
        return normalizeWhitespace(raw)
            .orEmpty()
            .replace(INSTAGRAM_PROFILE_DECORATION, "")
            .trim()
            .removePrefix("@")
            .lowercase(Locale.ROOT)
    }

    private fun normalizeWhitespace(raw: String?): String? {
        return raw
            ?.replace('\u00A0', ' ')
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private val SENTENCE_BOUNDARY = Regex("[。！？]|[.!?](?=\\s|$)")
}
