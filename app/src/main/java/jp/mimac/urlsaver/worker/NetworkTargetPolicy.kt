package jp.mimac.urlsaver.worker

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Rejects network targets that can cross a device's private-network boundary. */
object NetworkTargetPolicy {
    fun isAllowed(url: String, allowTestHosts: Boolean = false): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (uri.userInfo != null || host.isBlank()) return false
        if (scheme == "http") return allowTestHosts && isLoopbackHost(host)
        if (scheme != "https") return false
        if (allowTestHosts && host.endsWith(".test")) return true
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".home.arpa")
        ) {
            return false
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.none(::isPrivateAddress)
    }

    private fun isLoopbackHost(host: String): Boolean {
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val first = bytes[0]
            val second = bytes[1]
            return (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 192 && second == 0 && bytes[2] in 0..2) ||
                (first == 198 && second in 18..19) ||
                (first == 203 && second == 0 && bytes[2] == 113) ||
                first >= 224
        }
        return bytes.size == 16 &&
            ((bytes[0] and 0xfe) == 0xfc ||
                (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80))
    }
}
