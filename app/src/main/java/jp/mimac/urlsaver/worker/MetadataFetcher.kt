package jp.mimac.urlsaver.worker

import jp.mimac.urlsaver.domain.MetadataError
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

sealed interface FetchOutcome {
    data class Ready(
        val fetchedTitle: String?,
        val thumbnailUrl: String?,
        val canonicalId: String?,
        val normalizedHost: String?,
        val rawSourceHost: String?,
    ) : FetchOutcome

    data class Unavailable(val error: MetadataError) : FetchOutcome
    data class FailedRetryable(val error: MetadataError) : FetchOutcome
}

class MetadataFetcher {
    fun fetch(url: String): FetchOutcome {
        return try {
            fetchInternal(url)
        } catch (_: SocketTimeoutException) {
            FetchOutcome.FailedRetryable(MetadataError.TIMEOUT)
        } catch (_: java.io.InterruptedIOException) {
            FetchOutcome.FailedRetryable(MetadataError.TIMEOUT)
        } catch (_: java.io.IOException) {
            FetchOutcome.FailedRetryable(MetadataError.NETWORK_IO)
        } catch (_: Exception) {
            FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
        }
    }

    private fun fetchInternal(inputUrl: String): FetchOutcome {
        var current = inputUrl
        var redirects = 0

        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }

            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        if (location.isNullOrBlank()) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        redirects += 1
                        if (redirects > 5) {
                            return FetchOutcome.Unavailable(MetadataError.TOO_MANY_REDIRECTS)
                        }
                        current = URI(current).resolve(location).toString()
                        continue
                    }

                    code == 404 -> return FetchOutcome.Unavailable(MetadataError.HTTP_404)
                    code in 400..499 -> return FetchOutcome.Unavailable(MetadataError.HTTP_4XX)
                    code in 500..599 -> return FetchOutcome.FailedRetryable(MetadataError.HTTP_5XX)
                }

                if (connection.contentLengthLong > BODY_LIMIT_BYTES) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val body = BufferedInputStream(connection.inputStream).use { input ->
                    readLimitedBody(input, BODY_LIMIT_BYTES)
                }
                if (body == null) {
                    return FetchOutcome.Unavailable(MetadataError.OVERSIZED)
                }

                val contentType = connection.contentType?.lowercase().orEmpty()
                val isHtmlByHeader = contentType.contains("text/html") ||
                    contentType.contains("application/xhtml+xml")
                val bodyText = body.toString(Charsets.UTF_8)
                val looksHtml = bodyText.contains("<html", ignoreCase = true) ||
                    bodyText.contains("<!doctype html", ignoreCase = true)

                val isHtml = if (contentType.isBlank()) looksHtml else isHtmlByHeader
                if (!isHtml) {
                    return FetchOutcome.Unavailable(MetadataError.NON_HTML)
                }

                val document = Jsoup.parse(bodyText, current)
                val title = extractTitle(document)
                val ogImage = extractOgImage(document)
                val uri = URI(current)
                val canonical = extractCanonical(document, uri)

                if (title == null && ogImage == null) {
                    return FetchOutcome.Unavailable(MetadataError.PARSE_FAILED)
                }

                return FetchOutcome.Ready(
                    fetchedTitle = title,
                    thumbnailUrl = ogImage,
                    canonicalId = canonical,
                    normalizedHost = uri.host,
                    rawSourceHost = uri.host,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readLimitedBody(input: BufferedInputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun extractTitle(document: Document): String? {
        return document.title().trim().takeIf { it.isNotBlank() }
    }

    private fun extractOgImage(document: Document): String? {
        val metaContent = sequenceOf(
            "meta[property=og:image]",
            "meta[name=og:image]",
            "meta[property=twitter:image]",
            "meta[name=twitter:image]",
        ).mapNotNull { selector ->
            document.selectFirst(selector)?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        }.firstOrNull()

        if (metaContent == null) {
            return null
        }

        val absolute = runCatching { URI(document.baseUri()).resolve(metaContent).toString() }.getOrNull()
        return absolute ?: metaContent
    }

    private fun extractCanonical(document: Document, currentUri: URI): String? {
        val canonicalHref = document.selectFirst("link[rel=canonical]")
            ?.attr("abs:href")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val canonicalUri = canonicalHref?.let { href ->
            runCatching { URI(href) }.getOrNull()
        }

        return extractServiceCanonical(canonicalUri ?: currentUri)
    }

    private fun extractServiceCanonical(uri: URI): String? {
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return when {
            host.endsWith("youtube.com") -> URI("https", host, "/watch", uri.rawQuery, null).rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("v=") }
                ?.removePrefix("v=")
            host == "youtu.be" -> path.trim('/').ifBlank { null }
            host.endsWith("tiktok.com") -> path.substringAfterLast('/', "").ifBlank { null }
            host == "x.com" || host.endsWith("twitter.com") -> path.substringAfterLast("/status/", "").ifBlank { null }
            else -> null
        }
    }

    companion object {
        private const val BODY_LIMIT_BYTES = 512L * 1024L
        private const val USER_AGENT = "UrlSaver/1.0 (+https://example.invalid/urlsaver)"
    }
}
