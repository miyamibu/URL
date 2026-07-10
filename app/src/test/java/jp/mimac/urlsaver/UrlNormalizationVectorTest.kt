package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UrlNormalizationVectorTest {

    @Test
    fun normalizationVectors_matchCurrentAndroidRules() {
        val vectors = loadVectors()
        vectors.forEach { vector ->
            assertEquals(
                "Normalization mismatch for ${vector.input}",
                vector.expectedNormalizedUrl,
                UrlRules.normalize(vector.input),
            )
        }
    }

    private fun loadVectors(): List<UrlNormalizationVector> {
        val candidates = listOf(
            File("contracts/shared-tag-sync/url-normalization-v1.json"),
            File("../contracts/shared-tag-sync/url-normalization-v1.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate url-normalization-v1.json from ${System.getProperty("user.dir")}")
        return Json.decodeFromString(file.readText())
    }
}

@Serializable
private data class UrlNormalizationVector(
    val input: String,
    val expectedNormalizedUrl: String? = null,
    val reason: String,
)
