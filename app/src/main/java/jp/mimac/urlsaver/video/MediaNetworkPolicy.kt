package jp.mimac.urlsaver.video

import jp.mimac.urlsaver.network.NetworkUrlPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CancellationException

internal object MediaNetworkPolicy {
    const val MAX_REDIRECTS = 5
    const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    const val MAX_MEDIA_BYTES = 256L * 1024L * 1024L
    const val MAX_ENTRY_MEDIA_BYTES = 512L * 1024L * 1024L
    const val MAX_RESOLVER_RESPONSE_BYTES = 2L * 1024L * 1024L
    const val MIN_FREE_BYTES = 32L * 1024L * 1024L

    private val redirectStatusCodes = setOf(301, 302, 303, 307, 308)
    private val imageMimeTypes = setOf(
        "image/avif",
        "image/gif",
        "image/heic",
        "image/heif",
        "image/jpeg",
        "image/png",
        "image/webp",
    )
    private val videoMimeTypes = setOf(
        "video/mp4",
        "video/quicktime",
        "video/webm",
    )

    data class Response(
        val uri: URI,
        val connection: HttpURLConnection,
        val statusCode: Int,
    )

    class HttpStatusException(val statusCode: Int) : IOException("HTTP_$statusCode")

    class InvalidResponseException(message: String) : IOException(message)

    class BlockedUrlException : IOException("MEDIA_URL_BLOCKED")

    class QuotaExceededException : IOException("MEDIA_QUOTA_EXCEEDED")

    class InsufficientStorageException : IOException("MEDIA_STORAGE_LOW")

    fun normalizeUrl(rawUrl: String): String = NetworkUrlPolicy.normalizeUrl(rawUrl)

    fun validateUrl(rawUrl: String, allowLoopbackHttp: Boolean = false): URI {
        return try {
            NetworkUrlPolicy.validateExternalUrl(rawUrl, allowLoopbackHttp = allowLoopbackHttp)
        } catch (error: NetworkUrlPolicy.BlockedUrlException) {
            throw BlockedUrlException()
        }
    }

    fun hasAllowedUrlShape(rawUrl: String, allowLoopbackHttp: Boolean = false): Boolean =
        NetworkUrlPolicy.hasAllowedUrlShape(rawUrl, allowLoopbackHttp)

    fun isAllowedUrl(rawUrl: String, allowLoopbackHttp: Boolean = false): Boolean =
        NetworkUrlPolicy.isAllowedUrl(rawUrl, allowLoopbackHttp)

    fun isBlockedHostLiteral(host: String): Boolean = NetworkUrlPolicy.isBlockedHostLiteral(host)

    internal fun isBlockedAddress(address: InetAddress): Boolean = NetworkUrlPolicy.isBlockedAddress(address)

    fun openGetResponse(
        rawUrl: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        headers: Map<String, String>,
        allowLoopbackHttp: Boolean = false,
        onConnectionOpened: (HttpURLConnection) -> Unit = {},
    ): Response = openResponse(
        rawUrl = rawUrl,
        method = "GET",
        body = null,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        headers = headers,
        allowLoopbackHttp = allowLoopbackHttp,
        onConnectionOpened = onConnectionOpened,
    )

    fun openPostResponse(
        rawUrl: String,
        body: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        headers: Map<String, String>,
        allowLoopbackHttp: Boolean = false,
        allowErrorStatus: Boolean = false,
        onConnectionOpened: (HttpURLConnection) -> Unit = {},
    ): Response = openResponse(
        rawUrl = rawUrl,
        method = "POST",
        body = body,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        headers = headers,
        allowLoopbackHttp = allowLoopbackHttp,
        allowErrorStatus = allowErrorStatus,
        onConnectionOpened = onConnectionOpened,
    )

    fun readLimitedUtf8(connection: HttpURLConnection, maxBytes: Long, statusCode: Int = connection.responseCode): String {
        if (connection.contentLengthLong > maxBytes) {
            throw InvalidResponseException("MEDIA_RESOLVER_RESPONSE_TOO_LARGE")
        }
        val output = ByteArrayOutputStream()
        val responseStream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
        responseStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Media response read interrupted")
                }
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (total > maxBytes - read) {
                    throw InvalidResponseException("MEDIA_RESOLVER_RESPONSE_TOO_LARGE")
                }
                output.write(buffer, 0, read)
                total += read
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    fun validateContentType(mediaType: String, expectedMimeType: String?, responseMimeType: String?) {
        val actual = responseMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: throw InvalidResponseException("MEDIA_MIME_MISSING")
        val expected = expectedMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
        val allowed = if (mediaType == "IMAGE") imageMimeTypes else videoMimeTypes
        if (actual !in allowed || (expected != null && expected != actual)) {
            throw InvalidResponseException("MEDIA_MIME_INVALID")
        }
    }

    fun validateMagic(mediaType: String, mimeType: String?, bytes: ByteArray) {
        val detected = detectMimeType(bytes)
            ?: throw InvalidResponseException("MEDIA_MAGIC_INVALID")
        val normalizedMime = mimeType?.substringBefore(';')?.lowercase(Locale.US)
        if (mediaType == "IMAGE" && !detected.startsWith("image/")) {
            throw InvalidResponseException("MEDIA_MAGIC_TYPE_MISMATCH")
        }
        if (mediaType != "IMAGE" && !detected.startsWith("video/")) {
            throw InvalidResponseException("MEDIA_MAGIC_TYPE_MISMATCH")
        }
        val mimeMatches = normalizedMime == null || normalizedMime == detected ||
            (normalizedMime in setOf("image/heic", "image/heif") && detected == "image/heic")
        if (!mimeMatches) {
            throw InvalidResponseException("MEDIA_MAGIC_MIME_MISMATCH")
        }
    }

    fun classifyForRetry(error: Throwable): Boolean {
        if (error is CancellationException) throw error
        if (error is HttpStatusException) {
            return error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        }
        return error is SocketTimeoutException ||
            error is UnknownHostException ||
            error is ConnectException ||
            error is NoRouteToHostException ||
            error is InterruptedIOException
    }

    private fun openResponse(
        rawUrl: String,
        method: String,
        body: ByteArray?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        headers: Map<String, String>,
        allowLoopbackHttp: Boolean,
        allowErrorStatus: Boolean = false,
        onConnectionOpened: (HttpURLConnection) -> Unit,
    ): Response {
        var current = validateUrl(rawUrl, allowLoopbackHttp)
        var redirects = 0
        while (true) {
            val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                requestMethod = method
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            onConnectionOpened(connection)
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                val responseCode = connection.responseCode
                if (responseCode in redirectStatusCodes) {
                    val location = connection.getHeaderField("Location")
                        ?: throw InvalidResponseException("MEDIA_REDIRECT_LOCATION_MISSING")
                    if (redirects >= MAX_REDIRECTS) {
                        throw InvalidResponseException("MEDIA_REDIRECT_LIMIT")
                    }
                    redirects += 1
                    current = validateUrl(current.resolve(location).toString(), allowLoopbackHttp)
                    connection.disconnect()
                    continue
                }
                if (responseCode !in 200..299 && !allowErrorStatus) throw HttpStatusException(responseCode)
                return Response(current, connection, responseCode)
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
        }
    }

    private fun detectMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) {
            return "image/png"
        }
        if (bytes.size >= 6) {
            val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (header == "GIF87a" || header == "GIF89a") return "image/gif"
        }
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }
        if (bytes.size >= 12 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII).lowercase(Locale.US)
            if (brand in setOf("avif", "avis")) return "image/avif"
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")) return "image/heic"
            if (brand == "qt  ") return "video/quicktime"
            return "video/mp4"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) {
            return "video/webm"
        }
        return null
    }
}
