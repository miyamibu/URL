package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.worker.FetchOutcome
import jp.mimac.urlsaver.worker.MetadataFetcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

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
    fun fetch_httpScheme_isUnavailableUnsupportedScheme() {
        val result = MetadataFetcher().fetch("http://example.com")
        assertEquals(FetchOutcome.Unavailable(MetadataError.UNSUPPORTED_SCHEME), result)
    }

    @Test
    fun fetch_canonicalXHost_extractsXStatusId() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>X canonical</title>
                            <link rel="canonical" href="https://x.com/openai/status/111222333444" />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            val result = MetadataFetcher().fetch(server.url("/x").toString())
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("111222333444", result.canonicalId)
        }
    }

    @Test
    fun fetch_canonicalTwitterHost_extractsXStatusId() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>Twitter canonical</title>
                            <link rel="canonical" href="https://twitter.com/openai/status/555666777888" />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            val result = MetadataFetcher().fetch(server.url("/twitter").toString())
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("555666777888", result.canonicalId)
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

            MetadataFetcher("UrlSaver/test").fetch(server.url("/header").toString())

            val request = server.takeRequest()
            val userAgent = request.getHeader("User-Agent")
            assertEquals("UrlSaver/test", userAgent)
        }
    }

    @Test
    fun fetch_xSyndicationSuccess_usesUserNameAndFirstPhoto() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id_str": "1234567890123",
                          "user": {
                            "name": "OpenAI",
                            "screen_name": "OpenAI",
                            "profile_image_url_https": "https://pbs.twimg.com/profile_images/12345/user_normal.jpg"
                          },
                          "text": "Hello world. https://x.com/test More context here.",
                          "photos": [
                            {"url": "https://pbs.twimg.com/media/first.jpg"},
                            {"url": "https://pbs.twimg.com/media/second.jpg"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/1234567890123")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("OpenAI", result.fetchedTitle)
            assertEquals("Hello world. https://x.com/test More context here.", result.fetchedBody)
            assertEquals("Hello world.", result.bodySummary)
            assertEquals("https://pbs.twimg.com/media/first.jpg", result.thumbnailUrl)
            assertEquals(
                "https://pbs.twimg.com/profile_images/12345/user_400x400.jpg",
                result.badgeImageUrl,
            )
            assertEquals("1234567890123", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/oembed"))
            assertTrue(secondRequest.path.orEmpty().startsWith("/syndication/1234567890123"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_xSyndicationBody_isTrimmedToBodyLengthLimit() {
        withServer { server ->
            val longText = "A".repeat(4_500)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id_str": "1234567890123",
                          "user": {"name": "OpenAI"},
                          "text": "$longText"
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/1234567890123")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals(4_000, result.fetchedBody?.length)
            assertTrue(result.fetchedBody?.endsWith("…") == true)
        }
    }

    @Test
    fun fetch_xSyndicationSuccess_withoutPhoto_isStillReady() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id_str": "9988776655",
                          "user": {"screen_name": "openai_dev"}
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://twitter.com/openai/status/9988776655")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("openai_dev", result.fetchedTitle)
            assertEquals(null, result.thumbnailUrl)
            assertEquals("9988776655", result.canonicalId)
        }
    }

    @Test
    fun fetch_xOEmbedSuccess_usesAuthorName() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "OpenAI Research"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id_str": "111222333",
                          "user": {
                            "name": "OpenAI",
                            "profile_image_url_https": "https://pbs.twimg.com/profile_images/88888/openai_normal.jpg"
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/111222333")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("OpenAI Research", result.fetchedTitle)
            assertEquals("111222333", result.canonicalId)
            assertEquals(
                "https://pbs.twimg.com/profile_images/88888/openai_400x400.jpg",
                result.badgeImageUrl,
            )

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/oembed"))
            assertEquals("https://x.com/openai/status/111222333", firstRequest.requestUrl?.queryParameter("url"))
            assertTrue(secondRequest.path.orEmpty().startsWith("/syndication/111222333"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_xOEmbedBody_isTrimmedToBodyLengthLimit() {
        withServer { server ->
            val longText = "B".repeat(4_500)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "OpenAI Research",
                          "text": "$longText"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/111222334")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals(4_000, result.fetchedBody?.length)
            assertTrue(result.fetchedBody?.endsWith("…") == true)
        }
    }

    @Test
    fun fetch_xIStatusFallback_passesOriginalUrlToOEmbed() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "Polymarket"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/i/status/2044819736014283060")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Polymarket", result.fetchedTitle)
            assertEquals("2044819736014283060", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertEquals("https://x.com/i/status/2044819736014283060", firstRequest.requestUrl?.queryParameter("url"))
            assertTrue(secondRequest.path.orEmpty().startsWith("/syndication/2044819736014283060"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_xBothEndpoints5xx_returnsRetryableFailed() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(503))

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/222333444")

            assertEquals(FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX), result)
        }
    }

    @Test
    fun fetch_x404BothEndpoints_returnsUnavailable404() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/222333445")

            assertEquals(FetchOutcome.Unavailable(MetadataError.HTTP_404), result)
        }
    }

    @Test
    fun fetch_xMixedRetryableAndUnavailable_prefersRetryable() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(404))

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/222333446")

            assertEquals(FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX), result)
        }
    }

    @Test
    fun fetch_xBothParseFailures_returnsUnavailableParseFailed() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/222333447")

            assertEquals(FetchOutcome.Unavailable(MetadataError.PARSE_FAILED), result)
        }
    }

    @Test
    fun fetch_xCanonicalFallsBackToStatusId_whenPayloadIdMissing() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "user": {"name": "OpenAI"}
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createXFetcher(server)
            val result = fetcher.fetch("https://x.com/openai/status/76543210")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("76543210", result.canonicalId)
        }
    }

    @Test
    fun fetch_youtube_usesOEmbedTitleAndJsonLdDescription() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "title": "OEmbed title",
                          "thumbnail_url": "https://img.youtube.com/vi/abc123/hqdefault.jpg"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="OG title" />
                            <meta property="og:image" content="https://img.youtube.com/vi/abc123/maxresdefault.jpg" />
                            <script type="application/ld+json">
                              {
                                "@context": "https://schema.org",
                                "@type": "VideoObject",
                                "description": "Watch this demo https://example.com now. More details later."
                              }
                            </script>
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="OG title" />
                            <meta property="og:image" content="https://img.youtube.com/vi/abc123/maxresdefault.jpg" />
                            <script type="application/ld+json">
                              {
                                "@context": "https://schema.org",
                                "@type": "VideoObject",
                                "description": "Watch this demo https://example.com now. More details later."
                              }
                            </script>
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ ->
                    "https://oembed.youtube.test/oembed"
                },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed").toString()
                        requestedUrl.startsWith("https://www.youtube.com/") -> server.url("/youtube/watch").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/watch?v=abc123")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("OEmbed title", result.fetchedTitle)
            assertEquals("Watch this demo https://example.com now. More details later.", result.fetchedBody)
            assertEquals("Watch this demo now.", result.bodySummary)
            assertEquals("https://img.youtube.com/vi/abc123/hqdefault.jpg", result.thumbnailUrl)
            assertEquals(null, result.badgeImageUrl)
            assertEquals("abc123", result.canonicalId)
        }
    }

    @Test
    fun fetch_youtube_fallsBackToMetaDescription_whenJsonLdIsMissing() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="OG title fallback" />
                            <meta property="og:description" content="Video fallback description. Another sentence." />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="OG title fallback" />
                            <meta property="og:description" content="Video fallback description. Another sentence." />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ ->
                    "https://oembed.youtube.test/oembed"
                },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed2").toString()
                        requestedUrl.startsWith("https://www.youtube.com/") -> server.url("/youtube/watch2").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/watch?v=def456")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("OG title fallback", result.fetchedTitle)
            assertEquals("Video fallback description. Another sentence.", result.fetchedBody)
            assertEquals("Video fallback description.", result.bodySummary)
            assertEquals(null, result.badgeImageUrl)
            assertEquals("def456", result.canonicalId)
        }
    }

    @Test
    fun fetch_youtube_playerFallback_rescuesShortsDescriptionWhenHtmlBodyIsGeneric() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "title": "Shorts oEmbed title",
                          "thumbnail_url": "https://img.youtube.com/vi/aeqP8iRHeWQ/hqdefault.jpg"
                        }
                        """.trimIndent(),
                    ),
            )
            repeat(2) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(
                            """
                            <html>
                              <head>
                                <meta name="description" content="作成した動画を友だち、家族、世界中の人たちと共有" />
                              </head>
                              <body>shorts shell</body>
                            </html>
                            """.trimIndent(),
                        ),
                )
            }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "videoDetails": {
                            "title": "Player title",
                            "shortDescription": "🎵 タイトル : baby\n🎤 アーティスト : justin bieber\n\n#shorts #song",
                            "thumbnail": {
                              "thumbnails": [
                                {"url": "https://img.youtube.com/vi/aeqP8iRHeWQ/default.jpg", "width": 120, "height": 90},
                                {"url": "https://img.youtube.com/vi/aeqP8iRHeWQ/maxresdefault.jpg", "width": 1280, "height": 720}
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ -> "https://oembed.youtube.test/shorts" },
                youtubePlayerEndpointBuilder = { _ -> "https://youtubei.test/player" },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed-shorts").toString()
                        requestedUrl.startsWith("https://youtubei.test/") -> server.url("/youtube/player-shorts").toString()
                        requestedUrl.startsWith("https://www.youtube.com/") -> server.url("/youtube/shorts-shell").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/shorts/aeqP8iRHeWQ?si=abc")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Shorts oEmbed title", result.fetchedTitle)
            assertEquals("🎵 タイトル : baby 🎤 アーティスト : justin bieber #shorts #song", result.fetchedBody)
            assertEquals(MetadataBodyKind.YOUTUBE_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("🎵 タイトル : baby 🎤 アーティスト : justin bieber #shorts #song", result.bodySummary)
            assertEquals("https://img.youtube.com/vi/aeqP8iRHeWQ/hqdefault.jpg", result.thumbnailUrl)
            assertEquals("aeqP8iRHeWQ", result.canonicalId)

            repeat(3) { server.takeRequest() }
            val fourthRequest = server.takeRequest()
            assertEquals("POST", fourthRequest.method)
            assertTrue(fourthRequest.body.readUtf8().contains("\"videoId\": \"aeqP8iRHeWQ\""))
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun fetch_youtube_prefersEmbeddedShortDescription_whenAvailable() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"title":"Embedded title"}"""),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><head><title>channel probe</title></head><body>ok</body></html>"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="OG title fallback" />
                            <meta name="description" content="Meta description fallback." />
                          </head>
                          <body>
                            <script>
                              var ytInitialPlayerResponse = {
                                "videoDetails": {
                                  "shortDescription": "This is embedded short description.\nMore details here."
                                }
                              };
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ -> "https://oembed.youtube.test/embedded-short" },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed-embedded").toString()
                        requestedUrl.startsWith("https://www.youtube.com/") -> server.url("/youtube/watch-embedded").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/watch?v=ytshort1")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Embedded title", result.fetchedTitle)
            assertEquals("This is embedded short description. More details here.", result.fetchedBody)
            assertEquals(MetadataBodyKind.YOUTUBE_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("This is embedded short description.", result.bodySummary)
            assertEquals("Meta description fallback.", result.description)
            assertEquals("ytshort1", result.canonicalId)
        }
    }

    @Test
    fun fetch_youtube_descriptionPrefersOgThenTwitterThenMeta() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"title":"Priority title"}"""),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><head><title>channel probe</title></head><body>ok</body></html>"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:description" content="OG description wins." />
                            <meta name="twitter:description" content="Twitter description second." />
                            <meta name="description" content="Meta description fallback." />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ -> "https://oembed.youtube.test/priority" },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed-priority").toString()
                        requestedUrl.startsWith("https://www.youtube.com/") -> server.url("/youtube/watch-priority").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/watch?v=priority1")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("OG description wins.", result.description)
        }
    }

    @Test
    fun fetch_youtube_authorUrl_addsBadgeImageWithoutBreakingMainMetadata() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "title": "Badge title",
                          "thumbnail_url": "https://img.youtube.com/vi/xyz987/hqdefault.jpg",
                          "author_url": "https://www.youtube.com/@OpenAI"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:image" content="https://yt3.ggpht.com/openai-channel-avatar.jpg" />
                          </head>
                          <body>channel</body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="HTML title fallback" />
                            <meta name="description" content="HTML fallback body." />
                          </head>
                          <body>
                            <script>
                              var test = "https://yt3.ggpht.com/channel-image=s400-c-k-c0x00ffffff-no-rj";
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="Main title" />
                            <meta name="description" content="Video description text." />
                          </head>
                          <body>watch</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ -> "https://oembed.youtube.test/badge" },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed-badge").toString()
                        requestedUrl.startsWith("https://www.youtube.com/@") -> server.url("/youtube/channel-badge").toString()
                        requestedUrl.startsWith("https://www.youtube.com/watch") -> server.url("/youtube/watch-badge").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/watch?v=xyz987")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Badge title", result.fetchedTitle)
            assertEquals("https://img.youtube.com/vi/xyz987/hqdefault.jpg", result.thumbnailUrl)
            assertEquals("https://yt3.ggpht.com/openai-channel-avatar.jpg", result.badgeImageUrl)
            assertEquals("xyz987", result.canonicalId)
        }
    }

    @Test
    fun fetch_youtube_htmlChannelBadgeFallback_isUsedWhenAuthorUrlIsMissing() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "title": "HTML badge fallback title",
                          "thumbnail_url": "https://img.youtube.com/vi/aaa111/hqdefault.jpg"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="HTML title fallback" />
                            <meta name="description" content="HTML fallback body." />
                          </head>
                          <body>
                            <script>
                              var test = "https://yt3.ggpht.com/channel-image=s400-c-k-c0x00ffffff-no-rj";
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="HTML title fallback" />
                            <meta name="description" content="HTML fallback body." />
                          </head>
                          <body>
                            <script>
                              var test = "https://yt3.ggpht.com/channel-image=s400-c-k-c0x00ffffff-no-rj";
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                youtubeOEmbedEndpointBuilder = { _ -> "https://oembed.youtube.test/fallback" },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith("https://oembed.youtube.test/") -> server.url("/youtube/oembed-html-badge").toString()
                        requestedUrl.startsWith("https://www.youtube.com/watch") -> server.url("/youtube/watch-html-badge").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.youtube.com/watch?v=aaa111")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("HTML badge fallback title", result.fetchedTitle)
            assertEquals("https://yt3.ggpht.com/channel-image=s400-c-k-c0x00ffffff-no-rj", result.badgeImageUrl)
            assertEquals("aaa111", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_usesJsonLdThenSummaryAndKeepsPartialReady() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="OG Insta Title" />
                            <meta property="og:image" content="https://instagram.example/thumb.jpg" />
                            <script type="application/ld+json">
                              {
                                "@context": "https://schema.org",
                                "@type": "SocialMediaPosting",
                                "caption": "Caption from JSON-LD. https://example.com/extra details.",
                                "author": {"name": "openai"}
                              }
                            </script>
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                connectionFactory = { requestedUrl ->
                    val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                        server.url("/instagram/p/ABC123").toString()
                    } else {
                        requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/p/ABC123/")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("OG Insta Title", result.fetchedTitle)
            assertEquals("Caption from JSON-LD. https://example.com/extra details.", result.fetchedBody)
            assertEquals("Caption from JSON-LD.", result.bodySummary)
            assertEquals("https://instagram.example/thumb.jpg", result.thumbnailUrl)
            assertEquals("ABC123", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_embeddedJsonFallback_extractsCaption() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="Insta Embedded Title" />
                            <meta property="og:image" content="https://instagram.example/thumb2.jpg" />
                          </head>
                          <body>
                            <script>
                              window.__a = {
                                "edge_media_to_caption": {
                                  "edges": [{"node": {"text": "Caption from embedded fallback. Another sentence."}}]
                                }
                              };
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                connectionFactory = { requestedUrl ->
                    val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                        server.url("/instagram/reel/XYZ789").toString()
                    } else {
                        requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/reel/XYZ789/")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Insta Embedded Title", result.fetchedTitle)
            assertEquals("Caption from embedded fallback. Another sentence.", result.fetchedBody)
            assertEquals("Caption from embedded fallback.", result.bodySummary)
            assertEquals("XYZ789", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_quotedOgDescription_extractsQuotedCaption() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="Quoted OG Title" />
                            <meta property="og:description" content="openai on Instagram: &quot;Caption from quoted OG description. Another sentence.&quot;" />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                connectionFactory = { requestedUrl ->
                    val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                        server.url("/instagram/p/QUOTE001").toString()
                    } else {
                        requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/p/QUOTE001/")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Quoted OG Title", result.fetchedTitle)
            assertEquals("Caption from quoted OG description. Another sentence.", result.fetchedBody)
            assertEquals("Caption from quoted OG description.", result.bodySummary)
            assertEquals("QUOTE001", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_oEmbedAndOversizedHtml_returnsPartialReady_withoutAtPrefix() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "openai.creator",
                          "thumbnail_url": "https://instagram.example/oembed-thumb.jpg",
                          "provider_name": "Instagram",
                          "provider_url": "https://www.instagram.com/"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("a".repeat(513 * 1024)),
            )

            val fetcher = createInstagramFetcher(
                server = server,
                htmlPath = "/instagram/p/OVERSIZED123",
                oEmbedPath = "/instagram/oembed",
            )
            val result = fetcher.fetch("https://www.instagram.com/p/OVERSIZED123/")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("openai.creator", result.fetchedTitle)
            assertTrue(result.fetchedTitle?.startsWith("@") == false)
            assertEquals(null, result.fetchedBody)
            assertEquals(null, result.bodySummary)
            assertEquals("https://instagram.example/oembed-thumb.jpg", result.thumbnailUrl)
            assertEquals(null, result.badgeImageUrl)
            assertEquals("OVERSIZED123", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/instagram/oembed"))
            assertTrue(secondRequest.path.orEmpty().startsWith("/instagram/p/OVERSIZED123"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_instagram_oEmbedSuccessAndHtmlSuccess_prefersHtmlAndSupplementsThumbnail() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "oembed-author",
                          "thumbnail_url": "https://instagram.example/oembed-thumb2.jpg",
                          "provider_name": "Instagram",
                          "provider_url": "https://www.instagram.com/"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="HTML Insta Title" />
                            <meta property="og:description" content="HTML body sentence. Another sentence." />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = createInstagramFetcher(
                server = server,
                htmlPath = "/instagram/reel/MERGE001",
                oEmbedPath = "/instagram/oembed",
            )
            val result = fetcher.fetch("https://www.instagram.com/reel/MERGE001/")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("HTML Insta Title", result.fetchedTitle)
            assertEquals("HTML body sentence. Another sentence.", result.fetchedBody)
            assertEquals("HTML body sentence.", result.bodySummary)
            assertEquals("https://instagram.example/oembed-thumb2.jpg", result.thumbnailUrl)
            assertEquals("MERGE001", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_oEmbed4xxAndHtmlRetryable_keepsHtmlRetryableMeaning() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"error":"forbidden"}"""),
            )
            server.enqueue(MockResponse().setResponseCode(503))

            val fetcher = createInstagramFetcher(
                server = server,
                htmlPath = "/instagram/p/RETRY001",
                oEmbedPath = "/instagram/oembed",
            )
            val result = fetcher.fetch("https://www.instagram.com/p/RETRY001/")

            assertEquals(FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX), result)
        }
    }

    @Test
    fun fetch_instagram_oEmbedParseFailedAndHtmlRetryable_keepsHtmlRetryableMeaning() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(MockResponse().setResponseCode(503))

            val fetcher = createInstagramFetcher(
                server = server,
                htmlPath = "/instagram/p/RETRY002",
                oEmbedPath = "/instagram/oembed",
            )
            val result = fetcher.fetch("https://www.instagram.com/p/RETRY002/")

            assertEquals(FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX), result)
        }
    }

    @Test
    fun fetch_instagram_oEmbedBuilderNullAndHtmlOversized_returnsOversizedAsBefore() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("b".repeat(513 * 1024)),
            )

            val fetcher = createInstagramFetcher(
                server = server,
                htmlPath = "/instagram/p/BUILDERNULL",
                oEmbedPath = null,
            )
            val result = fetcher.fetch("https://www.instagram.com/p/BUILDERNULL/")

            assertEquals(FetchOutcome.Unavailable(MetadataError.OVERSIZED), result)
        }
    }

    @Test
    fun fetch_instagram_authorUrlFetchFailure_doesNotBreakHtmlSuccess() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "Display Name",
                          "author_url": "https://www.instagram.com/openai/",
                          "thumbnail_url": "https://instagram.example/oembed-thumb3.jpg"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="HTML Insta Title 2" />
                            <meta property="og:description" content="HTML instagram body." />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                instagramOEmbedEndpointBuilder = { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("/instagram/oembed-author?url=$encoded").toString()
                },
                connectionFactory = { requestedUrl ->
                    val mapped = when {
                        requestedUrl.startsWith(server.url("/").toString()) -> requestedUrl
                        requestedUrl.startsWith("https://www.instagram.com/openai/") -> server.url("/instagram/profile-openai").toString()
                        requestedUrl.startsWith("https://www.instagram.com/") -> server.url("/instagram/p/POST001").toString()
                        else -> requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/p/POST001/")
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("HTML Insta Title 2", result.fetchedTitle)
            assertEquals("HTML instagram body.", result.fetchedBody)
            assertEquals("https://instagram.example/oembed-thumb3.jpg", result.thumbnailUrl)
            assertEquals(null, result.badgeImageUrl)
            assertEquals("POST001", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_publicOEmbedFallback_usesCaptionWhenHtmlIsErrorShell() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "title": "Caption from public oEmbed. Another sentence.",
                          "author_name": "public.author",
                          "thumbnail_url": "https://instagram.example/public-thumb.jpg"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head><title>Page not found • Instagram</title></head>
                          <body>
                            <script type="application/json">
                              {"route":"PolarisErrorRoute","page":"httpErrorPage","show_lox_redesigned_404_page":true}
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                instagramPublicOEmbedEndpointBuilder = { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("/instagram/public-oembed?url=$encoded").toString()
                },
                connectionFactory = { requestedUrl ->
                    val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                        server.url("/instagram/reel/PUBLIC001").toString()
                    } else {
                        requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/reel/PUBLIC001/")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("public.author", result.fetchedTitle)
            assertEquals("Caption from public oEmbed. Another sentence.", result.fetchedBody)
            assertEquals(MetadataBodyKind.INSTAGRAM_CAPTION, result.fetchedBodyKind)
            assertEquals("Caption from public oEmbed.", result.bodySummary)
            assertEquals("https://instagram.example/public-thumb.jpg", result.thumbnailUrl)
            assertEquals("PUBLIC001", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_publicOEmbed_supplementsHtmlWhenBodyMissing() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "title": "Caption from public oEmbed body.",
                          "author_name": "public.author",
                          "thumbnail_url": "https://instagram.example/public-thumb2.jpg"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="HTML Insta Title 3" />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                instagramPublicOEmbedEndpointBuilder = { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("/instagram/public-oembed-merge?url=$encoded").toString()
                },
                connectionFactory = { requestedUrl ->
                    val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                        server.url("/instagram/p/PUBLIC002").toString()
                    } else {
                        requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/p/PUBLIC002/")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("HTML Insta Title 3", result.fetchedTitle)
            assertEquals("Caption from public oEmbed body.", result.fetchedBody)
            assertEquals(MetadataBodyKind.INSTAGRAM_CAPTION, result.fetchedBodyKind)
            assertEquals("Caption from public oEmbed body.", result.bodySummary)
            assertEquals("https://instagram.example/public-thumb2.jpg", result.thumbnailUrl)
            assertEquals("PUBLIC002", result.canonicalId)
        }
    }

    @Test
    fun fetch_instagram_captionedEmbedFallback_recoversCaptionWhenHtmlAndOEmbedBodyAreMissing() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "public.author"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <body>
                            <div class="Header">
                              <div class="AvatarContainer">
                                <a class="Avatar" href="https://www.instagram.com/embed.user/">
                                  <img src="https://instagram.example/profile.jpg&amp;size=150" alt="embed.user" />
                                </a>
                              </div>
                              <div class="HeaderText">
                                <a class="Username" href="https://www.instagram.com/embed.user/">embed.user</a>
                              </div>
                            </div>
                            <img class="EmbeddedMediaImage" src="https://instagram.example/media.jpg&amp;w=1080" />
                            <div class="Caption">
                              <a class="CaptionUsername" href="https://www.instagram.com/embed.user/">embed.user</a>
                              <br /><br />
                              Caption rescued from captioned embed. Another sentence.
                              <div class="CaptionComments">
                                <a class="CaptionCommentsExpand" href="https://www.instagram.com/p/EMBED001/">View all comments</a>
                              </div>
                            </div>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head><title>Page not found • Instagram</title></head>
                          <body>
                            <script type="application/json">
                              {"route":"PolarisErrorRoute","page":"httpErrorPage","show_lox_redesigned_404_page":true}
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher(
                instagramPublicOEmbedEndpointBuilder = { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("/instagram/public-oembed-empty?url=$encoded").toString()
                },
                instagramCaptionedEmbedEndpointBuilder = { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("/instagram/embed-captioned?url=$encoded").toString()
                },
                connectionFactory = { requestedUrl ->
                    val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                        server.url("/instagram/p/EMBED001").toString()
                    } else {
                        requestedUrl
                    }
                    URL(mapped).openConnection() as HttpURLConnection
                },
            )

            val result = fetcher.fetch("https://www.instagram.com/p/EMBED001/")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("public.author", result.fetchedTitle)
            assertEquals("Caption rescued from captioned embed. Another sentence.", result.fetchedBody)
            assertEquals(MetadataBodyKind.INSTAGRAM_CAPTION, result.fetchedBodyKind)
            assertEquals("Caption rescued from captioned embed.", result.bodySummary)
            assertEquals("https://instagram.example/media.jpg&w=1080", result.thumbnailUrl)
            assertEquals("https://instagram.example/profile.jpg&size=150", result.badgeImageUrl)
            assertEquals("EMBED001", result.canonicalId)
        }
    }

    @Test
    fun fetch_tiktok_oEmbedSuccess_usesCaptionThumbnailAndCanonicalId() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "Scout2015",
                          "title": "This is a TikTok caption. More context follows.",
                          "thumbnail_url": "https://p16-sign-va.tiktokcdn.com/thumb.jpeg",
                          "embed_product_id": "6718335390845095173"
                        }
                        """.trimIndent(),
                    ),
            )

            val originalUrl = "https://www.tiktok.com/@scout2015/video/6718335390845095173"
            val fetcher = createTikTokFetcher(server)
            val result = fetcher.fetch(originalUrl)

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Scout2015", result.fetchedTitle)
            assertEquals("This is a TikTok caption. More context follows.", result.fetchedBody)
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("This is a TikTok caption.", result.bodySummary)
            assertEquals("This is a TikTok caption. More context follows.", result.description)
            assertEquals("https://p16-sign-va.tiktokcdn.com/thumb.jpeg", result.thumbnailUrl)
            assertEquals("6718335390845095173", result.canonicalId)

            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().startsWith("/tiktok/oembed"))
            assertEquals(originalUrl, request.requestUrl?.queryParameter("url"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetch_tiktok_oEmbedHtmlFallback_extractsCaptionWhenTitleMissing() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "Creator",
                          "html": "<blockquote class=\"tiktok-embed\"><section><p>Caption from embed html. Another sentence.</p><p>♬ original sound - creator</p></section></blockquote>",
                          "thumbnail_url": "https://p16-sign-va.tiktokcdn.com/embed-thumb.jpeg",
                          "embed_product_id": "7000000000000000001"
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createTikTokFetcher(server)
            val result = fetcher.fetch("https://www.tiktok.com/@creator/video/7000000000000000001")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Creator", result.fetchedTitle)
            assertEquals("Caption from embed html. Another sentence.", result.fetchedBody)
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("Caption from embed html.", result.bodySummary)
            assertEquals("https://p16-sign-va.tiktokcdn.com/embed-thumb.jpeg", result.thumbnailUrl)
            assertEquals("7000000000000000001", result.canonicalId)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun fetch_tiktok_oEmbedPartial_supplementsFromHtmlMetadata() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "Creator",
                          "title": "Caption from oEmbed. Another sentence."
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <link rel="canonical" href="https://www.tiktok.com/@creator/video/7000000000000000003" />
                            <meta property="og:title" content="HTML TikTok title" />
                            <meta property="og:description" content="HTML caption fallback. Another sentence." />
                            <meta property="og:image" content="https://p16-sign-va.tiktokcdn.com/html-thumb.jpeg" />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = createTikTokFetcher(
                server = server,
                htmlPath = "/tiktok/html-supplement",
            )
            val result = fetcher.fetch("https://www.tiktok.com/@creator/video/7000000000000000003")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Creator", result.fetchedTitle)
            assertEquals("Caption from oEmbed. Another sentence.", result.fetchedBody)
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("Caption from oEmbed.", result.bodySummary)
            assertEquals("Caption from oEmbed. Another sentence.", result.description)
            assertEquals("https://p16-sign-va.tiktokcdn.com/html-thumb.jpeg", result.thumbnailUrl)
            assertEquals("7000000000000000003", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/tiktok/oembed"))
            assertEquals("/tiktok/html-supplement", secondRequest.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_tiktok_embeddedJson_rescuesShortUrlPostContentAndImages() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "@",
                          "title": "",
                          "thumbnail_url": "",
                          "author_url": "https://www.tiktok.com/",
                          "embed_product_id": "7622724848539782408"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head><title>TikTok - Make Your Day</title></head>
                          <body>
                            <script type="application/json" id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
                            {
                              "webapp.video-detail": {
                                "itemInfo": {
                                  "itemStruct": {
                                    "id": "7622724848539782408",
                                    "desc": "今回はカルフォルニアを回った中でおすすめだったスポットを紹介しました♪ 学生旅行なのでできるだけ費用を抑えて無料で楽しめるところも多く紹介しています✨",
                                    "video": {
                                      "cover": "https://p16-common-sign.tiktokcdn.com/cover.jpeg",
                                      "shareCover": ["https://p16-common-sign.tiktokcdn.com/share.jpeg"]
                                    },
                                    "author": {
                                      "uniqueId": "trip123suuu",
                                      "nickname": "ス",
                                      "avatarThumb": "https://p16-common-sign.tiktokcdn.com/avatar.jpeg"
                                    },
                                    "shareInfo": {
                                      "title": "2週間のLA観光おすすめスポット"
                                    }
                                  }
                                },
                                "shareMeta": {
                                  "title": "TikTok · ス",
                                  "desc": "206 likes, 11 comments. “今回はカルフォルニアを回った中でおすすめだったスポットを紹介しました♪”",
                                  "cover_url": "https://p16-common-sign.tiktokcdn.com/share-meta.jpeg"
                                }
                              }
                            }
                            </script>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = createTikTokFetcher(
                server = server,
                htmlPath = "/tiktok/embedded-json",
            )
            val result = fetcher.fetch("https://vt.tiktok.com/ZS981yAnN")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("ス", result.fetchedTitle)
            assertEquals(
                "今回はカルフォルニアを回った中でおすすめだったスポットを紹介しました♪ 学生旅行なのでできるだけ費用を抑えて無料で楽しめるところも多く紹介しています✨",
                result.fetchedBody,
            )
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("今回はカルフォルニアを回った中でおすすめだったスポットを紹介しました♪ 学生旅行なのでできるだけ費用を抑えて無料で楽しめるところも多く紹介しています✨", result.bodySummary)
            assertEquals("https://p16-common-sign.tiktokcdn.com/cover.jpeg", result.thumbnailUrl)
            assertEquals("https://p16-common-sign.tiktokcdn.com/avatar.jpeg", result.badgeImageUrl)
            assertEquals("7622724848539782408", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/tiktok/oembed"))
            assertEquals("/tiktok/embedded-json", secondRequest.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_tiktok_freeFallback_rescuesPostContentWhenOfficialPathsAreEmpty() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "author_name": "@",
                          "title": "",
                          "thumbnail_url": "",
                          "author_url": "https://www.tiktok.com/",
                          "embed_product_id": "7632251073494781204"
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head><title>TikTok - Make Your Day</title></head>
                          <body>empty official shell</body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "code": 0,
                          "msg": "success",
                          "data": {
                            "id": "7632251073494781204",
                            "title": "中央線沿いならココが1番オススメ。ここでしか味わえない絶品です。",
                            "cover": "https://p16-common-sign.tiktokcdn.com/free-cover.webp",
                            "author": {
                              "nickname": "ぽよログ I 東京グルメ",
                              "unique_id": "poyo__log",
                              "avatar": "https://p16-common-sign.tiktokcdn.com/free-avatar.jpeg"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            val fetcher = createTikTokFetcher(
                server = server,
                htmlPath = "/tiktok/empty-official",
            )
            val result = fetcher.fetch("https://vt.tiktok.com/ZS96QKESc")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("ぽよログ I 東京グルメ", result.fetchedTitle)
            assertEquals("中央線沿いならココが1番オススメ。ここでしか味わえない絶品です。", result.fetchedBody)
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("https://p16-common-sign.tiktokcdn.com/free-cover.webp", result.thumbnailUrl)
            assertEquals("https://p16-common-sign.tiktokcdn.com/free-avatar.jpeg", result.badgeImageUrl)
            assertEquals("7632251073494781204", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            val thirdRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/tiktok/oembed"))
            assertEquals("/tiktok/empty-official", secondRequest.path)
            assertTrue(thirdRequest.path.orEmpty().startsWith("/tiktok/free-fallback"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun fetch_tiktok_oEmbedParseFailed_fallsBackToHtmlMetadata() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("{}"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <link rel="canonical" href="https://www.tiktok.com/@fallback/video/7000000000000000002" />
                            <meta property="og:title" content="Fallback TikTok title" />
                            <meta property="og:description" content="Fallback page caption. Another sentence." />
                            <meta property="og:image" content="https://p16-sign-va.tiktokcdn.com/fallback-thumb.jpeg" />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = createTikTokFetcher(
                server = server,
                htmlPath = "/tiktok/html-fallback",
            )
            val result = fetcher.fetch("https://www.tiktok.com/@fallback/video/7000000000000000002")

            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("Fallback TikTok title", result.fetchedTitle)
            assertEquals("Fallback page caption. Another sentence.", result.fetchedBody)
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, result.fetchedBodyKind)
            assertEquals("Fallback page caption.", result.bodySummary)
            assertEquals("https://p16-sign-va.tiktokcdn.com/fallback-thumb.jpeg", result.thumbnailUrl)
            assertEquals("7000000000000000002", result.canonicalId)

            val firstRequest = server.takeRequest()
            val secondRequest = server.takeRequest()
            assertTrue(firstRequest.path.orEmpty().startsWith("/tiktok/oembed"))
            assertEquals("/tiktok/html-fallback", secondRequest.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun fetch_web_usesMetaDescription_thenParagraphFallback() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <meta property="og:title" content="Web title with meta" />
                            <meta name="description" content="Meta description body. More details." />
                          </head>
                          <body></body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>Web title with paragraphs</title>
                          </head>
                          <body>
                            <article>
                              <p>Paragraph first sentence. second sentence continues.</p>
                              <p>Paragraph two for extraction.</p>
                            </article>
                          </body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val fetcher = MetadataFetcher()

            val metaResult = fetcher.fetch(server.url("/web/meta").toString())
            assertTrue(metaResult is FetchOutcome.Ready)
            metaResult as FetchOutcome.Ready
            assertEquals("Web title with meta", metaResult.fetchedTitle)
            assertEquals("Meta description body. More details.", metaResult.fetchedBody)
            assertEquals(MetadataBodyKind.WEB_DESCRIPTION, metaResult.fetchedBodyKind)
            assertEquals("Meta description body.", metaResult.bodySummary)

            val paragraphResult = fetcher.fetch(server.url("/web/article").toString())
            assertTrue(paragraphResult is FetchOutcome.Ready)
            paragraphResult as FetchOutcome.Ready
            assertEquals("Web title with paragraphs", paragraphResult.fetchedTitle)
            assertEquals(
                "Paragraph first sentence. second sentence continues.\n\nParagraph two for extraction.",
                paragraphResult.fetchedBody,
            )
            assertEquals(MetadataBodyKind.WEB_EXCERPT, paragraphResult.fetchedBodyKind)
            assertEquals("Paragraph first sentence.", paragraphResult.bodySummary)
        }
    }

    @Test
    fun fetch_webSummary_handlesJapaneseSentenceWithoutSpaces() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>日本語ページ</title>
                            <meta name="description" content="最初の文です。次の文です。さらに続きます。" />
                          </head>
                          <body></body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val result = MetadataFetcher().fetch(server.url("/web/japanese-summary").toString())
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("最初の文です。", result.bodySummary)
        }
    }

    @Test
    fun fetch_webMetaDescription_isTrimmedToBodyLengthLimit() {
        withServer { server ->
            val longDescription = "あ".repeat(4_500)
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>Long description</title>
                            <meta name="description" content="$longDescription" />
                          </head>
                          <body></body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val result = MetadataFetcher().fetch(server.url("/web/long-description").toString())
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals(4_000, result.fetchedBody?.length)
            assertTrue(result.fetchedBody?.endsWith("…") == true)
        }
    }

    @Test
    fun fetch_web_faviconIsUsedForBadgeImage() {
        withServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <html>
                          <head>
                            <title>Favicon target</title>
                            <link rel="icon" href="/favicon.ico" />
                            <meta name="description" content="Body for favicon test." />
                          </head>
                          <body>ok</body>
                        </html>
                        """.trimIndent(),
                    ),
            )

            val result = MetadataFetcher().fetch(server.url("/web/favicon").toString())
            assertTrue(result is FetchOutcome.Ready)
            result as FetchOutcome.Ready
            assertEquals("http://localhost:${server.port}/favicon.ico", result.badgeImageUrl)
        }
    }

    private fun createXFetcher(server: MockWebServer): MetadataFetcher {
        return MetadataFetcher(
            userAgent = "UrlSaver/test",
            syndicationEndpointBuilder = { statusId ->
                server.url("/syndication/$statusId").toString()
            },
            oEmbedEndpointBuilder = { targetUrl ->
                val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                server.url("/oembed?url=$encoded").toString()
            },
        )
    }

    private fun createInstagramFetcher(
        server: MockWebServer,
        htmlPath: String,
        oEmbedPath: String?,
        embedPath: String? = null,
    ): MetadataFetcher {
        return MetadataFetcher(
            instagramPublicOEmbedEndpointBuilder = oEmbedPath?.let { path ->
                { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("$path?url=$encoded").toString()
                }
            },
            instagramCaptionedEmbedEndpointBuilder = embedPath?.let { path ->
                { targetUrl ->
                    val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                    server.url("$path?url=$encoded").toString()
                }
            },
            connectionFactory = { requestedUrl ->
                val mapped = if (requestedUrl.startsWith("https://www.instagram.com/")) {
                    server.url(htmlPath).toString()
                } else {
                    requestedUrl
                }
                URL(mapped).openConnection() as HttpURLConnection
            },
        )
    }

    private fun createTikTokFetcher(
        server: MockWebServer,
        htmlPath: String? = null,
    ): MetadataFetcher {
        return MetadataFetcher(
            tiktokOEmbedEndpointBuilder = { targetUrl ->
                val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                server.url("/tiktok/oembed?url=$encoded").toString()
            },
            tiktokFallbackEndpointBuilder = { targetUrl ->
                val encoded = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
                server.url("/tiktok/free-fallback?url=$encoded").toString()
            },
            connectionFactory = { requestedUrl ->
                val mapped = if (
                    htmlPath != null &&
                    (
                        requestedUrl.startsWith("https://www.tiktok.com/") ||
                            requestedUrl.startsWith("https://m.tiktok.com/") ||
                            requestedUrl.startsWith("https://vm.tiktok.com/") ||
                            requestedUrl.startsWith("https://vt.tiktok.com/")
                        )
                ) {
                    server.url(htmlPath).toString()
                } else {
                    requestedUrl
                }
                URL(mapped).openConnection() as HttpURLConnection
            },
        )
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
