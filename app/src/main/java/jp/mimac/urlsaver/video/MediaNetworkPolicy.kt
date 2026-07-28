package jp.mimac.urlsaver.video

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
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
        "image/gif",
        "image/heic",
        "image/heif",
        "image/avif",
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
    )

    class HttpStatusException(val statusCode: Int) : IOException("HTTP_$statusCode")

    class InvalidResponseException(message: String) : IOException(message)

    class BlockedUrlException : IOException("MEDIA_URL_BLOCKED")

    class QuotaExceededException : IOException("MEDIA_QUOTA_EXCEEDED")

    class InsufficientStorageException : IOException("MEDIA_STORAGE_LOW")

    fun normalizeUrl(rawUrl: String): String {
        return rawUrl
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\%", "%")
            .trim()
    }

    fun validateUrl(rawUrl: String): URI {
        val uri = runCatching { URI(normalizeUrl(rawUrl)) }
            .getOrElse { throw BlockedUrlException() }
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        if (scheme != "https" || host.isNullOrBlank() || uri.userInfo != null) {
            throw BlockedUrlException()
        }
        if (isBlockedHostLiteral(host)) throw BlockedUrlException()
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { throw it }
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw BlockedUrlException()
        }
        return uri
    }

    fun isAllowedUrl(rawUrl: String): Boolean = runCatching {
        validateUrl(rawUrl)
        true
    }.getOrDefault(false)

    fun openGetResponse(
        rawUrl: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        headers: Map<String, String>,
    ): Response = openResponse(
        rawUrl = rawUrl,
        method = "GET",
        body = null,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        headers = headers,
    )

    fun openPostResponse(
        rawUrl: String,
        body: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        headers: Map<String, String>,
    ): Response = openResponse(
        rawUrl = rawUrl,
        method = "POST",
        body = body,
        connectTimeoutMillis = connectTimeoutMillis,
        readTimeoutMillis = readTimeoutMillis,
        headers = headers,
    )

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

    fun validateMagic(
        mediaType: String,
        mimeType: String?,
        bytes: ByteArray,
    ) {
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

    fun isBlockedHostLiteral(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase(Locale.US).trimEnd('.')
        return normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized == "metadata" ||
            normalized == "metadata.google.internal" ||
            normalized == "instance-data.ec2.internal" ||
            normalized == "169.254.169.254" ||
            normalized == "100.100.100.200" ||
            normalized == "fd00:ec2::254"
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            val first = bytes[0]
            val second = bytes[1]
            return first == 0 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 192 && second == 0 && bytes[2] == 0) ||
                (first == 192 && second == 0 && bytes[2] == 2) ||
                (first == 198 && second in 18..19) ||
                (first == 198 && second == 51 && bytes[2] == 100) ||
                (first == 203 && second == 0 && bytes[2] == 113)
        }
        if (address is Inet6Address) {
            val bytes = address.address
            return (bytes[0].toInt() and 0xff) in 0xfc..0xfd ||
                ((bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80)
        }
        return false
    }

    private fun openResponse(
        rawUrl: String,
        method: String,
        body: ByteArray?,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        headers: Map<String, String>,
    ): Response {
        var current = validateUrl(rawUrl)
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
                    current = validateUrl(current.resolve(location).toString())
                    connection.disconnect()
                    continue
                }
                if (responseCode !in 200..299) throw HttpStatusException(responseCode)
                return Response(current, connection)
            } catch (error: CancellationException) {
                connection.disconnect()
                throw error
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
            if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")) {
                return "image/heic"
            }
            if (brand == "qt  ") return "video/quicktime"
            return "video/mp4"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) {
            return "video/webm"
        }
        return null
    }
}
