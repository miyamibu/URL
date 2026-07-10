package jp.mimac.urlsaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinkDeathInsuranceContractTest {
    @Test
    fun detailMediaSaveActionDoesNotExposeYouTubeTikTokOrXVideoDownloadUi() {
        val source = File("src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt").readText()
        val supportedBlock = source.substringAfter("val supportedMediaService = current.serviceType in setOf(")
            .substringBefore(")")

        assertTrue(supportedBlock.contains("ServiceType.INSTAGRAM"))
        assertFalse(supportedBlock.contains("ServiceType.YOUTUBE"))
        assertFalse(supportedBlock.contains("ServiceType.TIKTOK"))
        assertFalse(supportedBlock.contains("ServiceType.X"))
        assertTrue(source.contains("BuildConfig.ALLOW_LOCAL_MEDIA_DOWNLOADS &&"))
    }

    @Test
    fun productionMainSourceDoesNotAddHeadlessOrWaybackResolvers() {
        val sourceRoot = File("src/main/java")
        val combined = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }
            .lowercase()

        assertFalse(combined.contains("headless"))
        assertFalse(combined.contains("wayback"))
    }
}
