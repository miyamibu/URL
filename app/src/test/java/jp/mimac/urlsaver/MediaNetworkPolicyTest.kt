package jp.mimac.urlsaver

import jp.mimac.urlsaver.video.MediaNetworkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class MediaNetworkPolicyTest {
    @Test
    fun privateLoopbackAndMetadataAddressesAreBlocked() {
        assertTrue(MediaNetworkPolicy.isBlockedHostLiteral("localhost"))
        assertTrue(MediaNetworkPolicy.isBlockedHostLiteral("metadata.google.internal"))
        assertTrue(MediaNetworkPolicy.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(MediaNetworkPolicy.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(MediaNetworkPolicy.isBlockedAddress(InetAddress.getByName("10.0.0.4")))
        assertFalse(MediaNetworkPolicy.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun nonHttpsAndBlockedHostUrlsAreRejected() {
        assertFalse(MediaNetworkPolicy.isAllowedUrl("http://example.com/image.jpg"))
        assertFalse(MediaNetworkPolicy.isAllowedUrl("https://localhost/image.jpg"))
        assertFalse(MediaNetworkPolicy.isAllowedUrl("https://127.0.0.1/image.jpg"))
    }

    @Test
    fun mediaMagicAndHttpClassificationAreStrict() {
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        MediaNetworkPolicy.validateMagic("IMAGE", "image/png", png)
        assertTrue(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(408)))
        assertTrue(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(429)))
        assertTrue(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(503)))
        assertFalse(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(404)))
        assertTrue(MediaNetworkPolicy.classifyForRetry(UnknownHostException("dns")))
    }
}
