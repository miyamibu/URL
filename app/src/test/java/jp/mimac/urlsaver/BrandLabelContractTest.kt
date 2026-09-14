package jp.mimac.urlsaver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandLabelContractTest {
    @Test
    fun userVisibleMobileLabelsUseRinbamBrand() {
        val resources = File("src/main/res/values/strings.xml").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val visibleSources = listOf(
            "src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt",
            "src/main/java/jp/mimac/urlsaver/ui/ExportScreen.kt",
            "src/main/java/jp/mimac/urlsaver/ui/TagDetailScreen.kt",
            "src/main/java/jp/mimac/urlsaver/ui/UrlSaverRoot.kt",
        ).joinToString("\n") { File(it).readText() }
        val quotedLegacyBrand = Regex("\\\"[^\\\"\\n]*(URL Saver|UrlSaver)[^\\\"\\n]*\\\"")

        assertTrue(resources.contains("<string name=\"app_name\">りんばむ</string>"))
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertFalse(quotedLegacyBrand.containsMatchIn(visibleSources))
    }

    @Test
    fun productionSourcesDoNotExposeInternalLaunchEditionLabel() {
        val productionRoot = File("src/main")
        val sourceFiles = productionRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .toList()

        assertFalse(sourceFiles.any { it.readText().contains("ローンチ版") })
    }
}
