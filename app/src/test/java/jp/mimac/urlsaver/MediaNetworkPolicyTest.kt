package jp.mimac.urlsaver

import jp.mimac.urlsaver.video.MediaNetworkPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CancellationException

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
    fun onlyPublicHttpsUrlsAreAllowedByDefault() {
        assertTrue(MediaNetworkPolicy.isAllowedUrl("https://8.8.8.8/media.mp4"))
        assertFalse(MediaNetworkPolicy.isAllowedUrl("http://8.8.8.8/media.mp4"))
        assertFalse(MediaNetworkPolicy.isAllowedUrl("https://localhost/media.mp4"))
        assertFalse(MediaNetworkPolicy.isAllowedUrl("https://127.0.0.1/media.mp4"))
        assertFalse(MediaNetworkPolicy.isAllowedUrl("https://user:password@8.8.8.8/media.mp4"))
    }

    @Test
    fun mediaMagicAndHttpClassificationAreStrict() {
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        MediaNetworkPolicy.validateMagic("IMAGE", "image/png", png)
        assertTrue(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(408)))
        assertTrue(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(429)))
        assertTrue(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(503)))
        assertFalse(MediaNetworkPolicy.classifyForRetry(MediaNetworkPolicy.HttpStatusException(404)))
    }

    @Test
    fun resolverErrorStatusBodyRemainsBoundedAndReadable() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":false,\"error\":\"AUTH_REQUIRED\"}"),
            )
            val response = MediaNetworkPolicy.openPostResponse(
                rawUrl = server.url("/resolve").toString(),
                body = "{}".toByteArray(),
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 5_000,
                headers = emptyMap(),
                allowLoopbackHttp = true,
                allowErrorStatus = true,
            )
            try {
                assertEquals(403, response.statusCode)
                assertEquals(
                    "{\"ok\":false,\"error\":\"AUTH_REQUIRED\"}",
                    MediaNetworkPolicy.readLimitedUtf8(
                        connection = response.connection,
                        maxBytes = MediaNetworkPolicy.MAX_RESOLVER_RESPONSE_BYTES,
                        statusCode = response.statusCode,
                    ),
                )
            } finally {
                response.connection.disconnect()
            }
        }
    }

    @Test(expected = MediaNetworkPolicy.BlockedUrlException::class)
    fun mediaRedirectToPrivateAddressIsRejectedBeforeSecondConnection() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "https://127.0.0.1/private"),
            )
            MediaNetworkPolicy.openGetResponse(
                rawUrl = server.url("/redirect").toString(),
                connectTimeoutMillis = 5_000,
                readTimeoutMillis = 5_000,
                headers = emptyMap(),
                allowLoopbackHttp = true,
            )
        }
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedToRetry() {
        MediaNetworkPolicy.classifyForRetry(CancellationException("cancelled"))
    }
}
