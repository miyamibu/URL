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

        val rootNormalized = UrlRules.normalize("http://Example.com:80/?q=1")
        assertEquals("http://example.com/?q=1", rootNormalized)
    }

    @Test
    fun normalize_rejectsMissingSchemeAndNonHttp() {
        assertNull(UrlRules.normalize("example.com/path"))
        assertNull(UrlRules.normalize("ftp://example.com/path"))
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
            extracted,
        )
    }

    @Test
    fun extractManual_noCandidateIsNoUrlFound() {
        val extracted = UrlRules.extractForManualInput("hello world")
        assertEquals(ShareExtractionResult.NoUrlFound, extracted)
    }

    @Test
    fun extractManual_invalidCandidateIsInvalidUrl() {
        val extracted = UrlRules.extractForManualInput("https:///broken")
        assertEquals(ShareExtractionResult.InvalidUrl, extracted)
    }

    @Test
    fun parseUrl_openUrlEqualsNormalized() {
        val parsed = UrlRules.parseUrl("https://Example.com:443/path?x=1#section")
        assertNotNull(parsed)
        assertEquals(parsed?.normalizedUrl, parsed?.openUrl)
        assertEquals("https://example.com/path?x=1", parsed?.openUrl)
    }
}
