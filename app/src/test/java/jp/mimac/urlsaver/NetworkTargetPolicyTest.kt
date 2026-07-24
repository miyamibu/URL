package jp.mimac.urlsaver

import jp.mimac.urlsaver.worker.NetworkTargetPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTargetPolicyTest {
    @Test
    fun rejectsPrivateAndCredentialBearingTargetsByDefault() {
        assertFalse(NetworkTargetPolicy.isAllowed("https://127.0.0.1/private"))
        assertFalse(NetworkTargetPolicy.isAllowed("https://10.0.0.1/private"))
        assertFalse(NetworkTargetPolicy.isAllowed("https://user:pass@example.com/path"))
        assertFalse(NetworkTargetPolicy.isAllowed("http://127.0.0.1/path"))
    }

    @Test
    fun permitsOnlyExplicitTestTargetsWhenTestModeIsEnabled() {
        assertTrue(NetworkTargetPolicy.isAllowed("http://127.0.0.1/path", allowTestHosts = true))
        assertFalse(NetworkTargetPolicy.isAllowed("https://resolver.test/path"))
        assertTrue(NetworkTargetPolicy.isAllowed("https://resolver.test/path", allowTestHosts = true))
    }

    @Test
    fun acceptsAKnownPublicIpLiteral() {
        assertTrue(NetworkTargetPolicy.isAllowed("https://93.184.216.34/path"))
    }
}
