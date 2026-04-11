package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.worker.FetchOutcome
import jp.mimac.urlsaver.worker.MetadataFetcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFetcherTest {

    @Test
    fun fetch_htmlTitle_returnsReady() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><head><title>Hello</title></head><body>ok</body></html>"),
            )
            val result = MetadataFetcher().fetch(server.url("/ok").toString())
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Hello", result.fetchedTitle)
        }
    }

    @Test
    fun fetch_nonHtml_returnsUnavailableNonHtml() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/plain")
                    .setBody("plain text"),
            )
            val result = MetadataFetcher().fetch(server.url("/plain").toString())
            assertEquals(FetchOutcome.Unavailable(MetadataError.NON_HTML), result)
        }
    }

    @Test
    fun fetch_oversized_returnsUnavailableOversized() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("a".repeat(513 * 1024)),
            )
            val result = MetadataFetcher().fetch(server.url("/big").toString())
            assertEquals(FetchOutcome.Unavailable(MetadataError.OVERSIZED), result)
        }
    }

    @Test
    fun fetch_redirectLoop_returnsTooManyRedirects() {
        withServer { server ->
            val loopUrl = server.url("/loop").toString()
            repeat(6) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", loopUrl),
                )
            }
            val result = MetadataFetcher().fetch(loopUrl)
            assertEquals(FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS), result)
        }
    }

    @Test
    fun fetch_parseFailedClassifiedAsUnavailable() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html><body>no title and no og image</body></html>"),
            )
            val result = MetadataFetcher().fetch(server.url("/parse").toString())
            assertEquals(FetchOutcome.Unavailable(MetadataError.PARSE_FAILED), result)
        }
    }

    @Test
    fun fetch_http5xx_isRetryableFailed() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val result = MetadataFetcher().fetch(server.url("/server-error").toString())
            assertEquals(FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX), result)
        }
    }

    @Test
    fun fetch_setsUserAgentHeader() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html><head><title>Header Check</title></head><body>ok</body></html>"),
            )

            MetadataFetcher().fetch(server.url("/header").toString())

            val request = server.takeRequest()
            val userAgent = request.getHeader("User-Agent")
            assertTrue(userAgent?.startsWith("UrlSaver/") == true)
        }
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }
}
