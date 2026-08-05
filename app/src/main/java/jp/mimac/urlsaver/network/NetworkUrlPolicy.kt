package jp.mimac.urlsaver.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal object NetworkUrlPolicy {
    class BlockedUrlException : IllegalArgumentException("URL_NOT_ALLOWED")

    private val defaultAddressResolver: (String) -> Array<InetAddress> = InetAddress::getAllByName

    fun normalizeUrl(rawUrl: String): String {
        return rawUrl
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("\\%", "%")
            .trim()
    }

    fun validateExternalUrl(
        rawUrl: String,
        allowLoopbackHttp: Boolean = false,
        allowTestHosts: Boolean = false,
        addressResolver: (String) -> Array<InetAddress> = defaultAddressResolver,
    ): URI {
        val uri = runCatching { URI(normalizeUrl(rawUrl)) }
            .getOrElse { throw BlockedUrlException() }
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        if (host.isNullOrBlank() || uri.userInfo != null) {
            throw BlockedUrlException()
        }

        val isTestHost = allowTestHosts && isTestHost(host)
        val isLoopbackHttp = scheme == "http" && allowLoopbackHttp && isLoopbackHostLiteral(host)
        val isAllowedScheme = scheme == "https" || (scheme == "http" && (isLoopbackHttp || isTestHost))
        if (!isAllowedScheme) {
            throw BlockedUrlException()
        }
        if (isLoopbackHttp || isTestHost) return uri
        if (isBlockedHostLiteral(host)) {
            throw BlockedUrlException()
        }

        val addresses = addressResolver(host)
        if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
            throw BlockedUrlException()
        }
        return uri
    }

    fun hasAllowedUrlShape(rawUrl: String, allowLoopbackHttp: Boolean = false): Boolean {
        val uri = runCatching { URI(normalizeUrl(rawUrl)) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (uri.userInfo != null) return false
        if (scheme == "http") {
            return allowLoopbackHttp && isLoopbackHostLiteral(host)
        }
        return scheme == "https" && !isBlockedHostLiteral(host)
    }

    fun isAllowedUrl(
        rawUrl: String,
        allowLoopbackHttp: Boolean = false,
        addressResolver: (String) -> Array<InetAddress> = defaultAddressResolver,
    ): Boolean = runCatching {
        validateExternalUrl(
            rawUrl = rawUrl,
            allowLoopbackHttp = allowLoopbackHttp,
            addressResolver = addressResolver,
        )
    }.isSuccess

    fun isBlockedHostLiteral(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase(Locale.US).trimEnd('.')
        if (
            normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized == "metadata" ||
            normalized == "metadata.google.internal" ||
            normalized == "instance-data.ec2.internal" ||
            normalized == "169.254.169.254" ||
            normalized == "100.100.100.200" ||
            normalized == "fd00:ec2::254"
        ) {
            return true
        }

        val numericAddress = when {
            normalized.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) ->
                runCatching { InetAddress.getByName(normalized) }.getOrNull()
            normalized.contains(':') ->
                runCatching { InetAddress.getByName(normalized) }.getOrNull()
            else -> null
        }
        return numericAddress?.let(::isBlockedAddress) == true
    }

    internal fun isBlockedAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
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
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            val fourth = bytes[3].toInt() and 0xff
            val isIpv4Mapped = bytes.take(10).all { it == 0.toByte() } &&
                (bytes[10].toInt() and 0xff) == 0xff &&
                (bytes[11].toInt() and 0xff) == 0xff
            if (isIpv4Mapped) {
                val mapped = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                if (isBlockedAddress(mapped)) return true
            }
            return first in 0xfc..0xfd ||
                (first == 0xfe && (second and 0xc0) == 0x80) ||
                (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8)
        }
        return false
    }

    private fun isTestHost(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase(Locale.US).trimEnd('.')
        return normalized == "localhost" ||
            normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized.endsWith(".test")
    }

    private fun isLoopbackHostLiteral(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase(Locale.US).trimEnd('.')
        if (normalized == "localhost" || normalized == "::1") return true
        val address = normalized
            .takeIf { it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        return address?.isLoopbackAddress == true
    }
}
