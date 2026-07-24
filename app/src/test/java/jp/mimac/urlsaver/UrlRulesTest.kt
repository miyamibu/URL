package jp.mimac.urlsaver

import android.content.ClipData
import android.content.Intent
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareExtractionResult
import jp.mimac.urlsaver.domain.UrlRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UrlRulesTest {

    @Test
    fun normalize_appliesPhase1aRules() {
        val normalized = UrlRules.normalize("HTTPS://Example.COM:443/path/?a=1#frag")
        assertEquals("https://example.com/path?a=1", normalized)

        val rootNormalized = UrlRules.normalize("HTTPS://Example.com:443/?q=1")
        assertEquals("https://example.com/?q=1", rootNormalized)
    }

    @Test
    fun normalize_rejectsMissingSchemeAndNonHttp() {
        assertNull(UrlRules.normalize("example.com/path"))
        assertNull(UrlRules.normalize("http://example.com/path"))
        assertNull(UrlRules.normalize("ftp://example.com/path"))
    }

    @Test
    fun normalize_rejectsUrlUserInfo() {
        assertNull(UrlRules.normalize("https://user:password@example.com/private"))
    }

    @Test
    fun normalize_allowsLoopbackHttp_forLocalTestInfra() {
        assertEquals("http://127.0.0.1/path", UrlRules.normalize("http://127.0.0.1/path"))
    }

    @Test
    fun displayUrl_keepsYoutubeVOnly() {
        val normalized = "https://www.youtube.com/watch?v=abc123&t=9"
        val display = UrlRules.toDisplayUrl(normalized, ServiceType.YOUTUBE)
        assertEquals("www.youtube.com/watch?v=abc123", display)
    }

    @Test
    fun displayUrl_dropsQueryForWeb() {
        val normalized = "https://example.com/news?a=1&b=2"
        val display = UrlRules.toDisplayUrl(normalized, ServiceType.WEB)
        assertEquals("example.com/news", display)
    }

    @Test
    fun extractIntent_fallbacksSourcePriority() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "first source has invalid: https:///broken")
            clipData = ClipData.newPlainText("url", "https://example.com/path")
            data = android.net.Uri.parse("https://ignored.com")
        }

        val extracted = UrlRules.extractFromIntent(intent)
        assertEquals(ShareExtractionResult.Found("https://example.com/path"), extracted)
    }

    @Test
    fun extractIntent_usesFirstValidWithinSameSource() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(
                Intent.EXTRA_TEXT,
                "invalid https:///broken then valid https://example.com/ok and later https://example.com/after",
            )
        }

        val extracted = UrlRules.extractFromIntent(intent)
        assertEquals(ShareExtractionResult.Found("https://example.com/ok"), extracted)
    }

    @Test
    fun extractIntent_returnsNoUrlWhenNoCandidate() {
        val intent = Intent(Intent.ACTION_SEND)
        val extracted = UrlRules.extractFromIntent(intent)
        assertEquals(ShareExtractionResult.NoUrlFound, extracted)
    }

    @Test
    fun extractIntent_returnsInvalidWhenCandidatesExistButAllInvalid() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, "only invalid: https:///broken")
        }

        val extracted = UrlRules.extractFromIntent(intent)
        assertEquals(ShareExtractionResult.InvalidUrl, extracted)
    }

    @Test
    fun extractIntent_readsSingleExtraStreamUrl() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("https://example.com/from-stream"))
        }

        val extracted = UrlRules.extractFromIntent(intent)
        assertEquals(ShareExtractionResult.Found("https://example.com/from-stream"), extracted)
    }

    @Test
    fun extractIntent_readsHtmlTextWhenExtraTextIsMissing() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_HTML_TEXT, "<a href=\"https://example.com/from-html\">link</a>")
        }

        val extracted = UrlRules.extractFromIntent(intent)
        assertEquals(ShareExtractionResult.Found("https://example.com/from-html"), extracted)
    }

    @Test
    fun extractAllFromIntent_collectsUniqueNormalizedUrls_forSendMultiple() {
        val first = "HTTPS://Example.com:443/path?x=1"
        val duplicate = "https://example.com/path?x=1"
        val second = "https://example.com/next"
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putStringArrayListExtra(Intent.EXTRA_TEXT, arrayListOf(first, duplicate, second))
        }

        val extracted = UrlRules.extractAllFromIntent(intent)
        assertEquals(
            listOf("https://example.com/path?x=1", "https://example.com/next"),
            extracted.urls,
        )
        assertEquals(false, extracted.truncatedToMaxUrls)
    }

    @Test
    fun extractAllFromIntent_capsBatchAtMaxUrls() {
        val urls = (0 until UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE + 10).joinToString("\n") {
            "https://example.com/item-$it"
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putExtra(Intent.EXTRA_TEXT, urls)
        }

        val extracted = UrlRules.extractAllFromIntent(intent)

        assertEquals(UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE, extracted.urls.size)
        assertEquals(true, extracted.truncatedToMaxUrls)
        assertEquals("https://example.com/item-0", extracted.urls.first())
    }

    @Test
    fun extractIntent_oversizedInputReturnsInputTooLarge() {
        val oversized = "a".repeat(UrlRules.MAX_INPUT_TEXT_BYTES + 1) + " https://example.com/late"
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, oversized)
        }

        val extracted = UrlRules.extractFromIntent(intent)

        assertEquals(ShareExtractionResult.InputTooLarge, extracted)
    }

    @Test
    fun extractManual_noCandidateIsNoUrlFound() {
        val extracted = UrlRules.extractForManualInput("hello world")
        assertEquals(ShareExtractionResult.NoUrlFound, extracted)
    }

    @Test
    fun extractManual_acceptsUppercaseHttpsScheme() {
        val extracted = UrlRules.extractForManualInput("HTTPS://Example.COM:443/path/#frag")
        assertEquals(ShareExtractionResult.Found("HTTPS://Example.COM:443/path/#frag"), extracted)
    }

    @Test
    fun extractMemoWithoutUrls_removesValidUrlsAndKeepsSharedText() {
        val memo = UrlRules.extractMemoWithoutUrls(
            """
            あとで読む
            https://example.com/a
            メモ本文
            https://example.com/b?x=1
            """.trimIndent(),
        )

        assertEquals("あとで読む\nメモ本文", memo)
    }

    @Test
    fun extractManual_invalidCandidateIsInvalidUrl() {
        val extracted = UrlRules.extractForManualInput("https:///broken")
        assertEquals(ShareExtractionResult.InvalidUrl, extracted)
    }

    @Test
    fun extractManual_oversizedInputIsInputTooLarge() {
        val oversized = "a".repeat(UrlRules.MAX_INPUT_TEXT_BYTES + 1)
        val extracted = UrlRules.extractForManualInput(oversized)
        assertEquals(ShareExtractionResult.InputTooLarge, extracted)
    }

    @Test
    fun parseUrl_openUrlEqualsNormalized() {
        val parsed = UrlRules.parseUrl("https://Example.com:443/path?x=1#section")
        assertNotNull(parsed)
        assertEquals(parsed?.normalizedUrl, parsed?.openUrl)
        assertEquals("https://example.com/path?x=1", parsed?.openUrl)
    }

    @Test
    fun parseUrl_tiktokIsClassifiedAsTikTok() {
        val parsed = UrlRules.parseUrl("https://www.tiktok.com/@user/video/12345")
        assertNotNull(parsed)
        assertEquals(ServiceType.TIKTOK, parsed?.serviceType)
    }

    @Test
    fun toLegacyHttpTwin_convertsNormalizedHttps() {
        val twin = UrlRules.toLegacyHttpTwin("https://example.com/path?x=1")
        assertEquals("http://example.com/path?x=1", twin)

        assertNull(UrlRules.toLegacyHttpTwin("http://example.com/path"))
    }

    @Test
    fun metadataFetchUrls_xStatus_keepsOriginalUrl() {
        val urls = UrlRules.metadataFetchUrls(
            openUrl = "https://x.com/openai/status/1234567890?s=20",
            serviceType = ServiceType.X,
        )

        assertEquals(
            listOf("https://x.com/openai/status/1234567890?s=20"),
            urls,
        )
    }

    @Test
    fun extractXStatusId_supportsOfficialHosts() {
        assertEquals("111222333", UrlRules.extractXStatusId("https://x.com/openai/status/111222333?s=20"))
        assertEquals("444555666", UrlRules.extractXStatusId("https://twitter.com/openai/status/444555666"))
        assertEquals("9876543210", UrlRules.extractXStatusId("https://x.com/i/web/status/9876543210"))
        assertEquals("2044819736014283060", UrlRules.extractXStatusId("https://x.com/i/status/2044819736014283060"))
    }

    @Test
    fun extractXStatusId_nonProviderOrNonStatus_returnsNull() {
        assertEquals(null, UrlRules.extractXStatusId("https://example.com/openai/status/111"))
        assertEquals(null, UrlRules.extractXStatusId("https://x.com/openai"))
        assertEquals(null, UrlRules.extractXStatusId("not a url"))
    }

    @Test
    fun metadataFetchUrls_web_keepsOriginalUrl() {
        val urls = UrlRules.metadataFetchUrls(
            openUrl = "https://example.com/article",
            serviceType = ServiceType.WEB,
        )

        assertEquals(listOf("https://example.com/article"), urls)
    }
}
