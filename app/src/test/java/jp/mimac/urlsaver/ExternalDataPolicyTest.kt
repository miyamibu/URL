package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.ExternalDataPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDataPolicyTest {
    @Test
    fun rejectsNonWebAndPathSensitiveUrls() {
        val fileResult = ExternalDataPolicy.inspect("file:///Users/mimac/private.txt")
        val pathResult = ExternalDataPolicy.inspect("https://example.com/contact@example.com")
        val encodedKeyResult = ExternalDataPolicy.inspect("https://example.com/?%74oken=secretvalue123")

        assertFalse(fileResult.allowed)
        assertFalse(pathResult.allowed)
        assertFalse(encodedKeyResult.allowed)
        assertTrue(pathResult.reasons.contains("email"))
        assertTrue(encodedKeyResult.reasons.contains("sensitive_query_key"))
    }
}
