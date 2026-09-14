package jp.mimac.urlsaver

import android.content.Intent
import androidx.activity.ComponentActivity
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_INVALID
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_MAIN_INTENT_EVENT_TOKEN
import jp.mimac.urlsaver.data.EXTRA_PROMO_CODE
import jp.mimac.urlsaver.data.EXTRA_PROMO_CODE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_TOKEN
import jp.mimac.urlsaver.data.TagRepository
import java.util.UUID

internal object ShareReceiverEntrypointRouter {
    @Suppress("UNUSED_PARAMETER")
    suspend fun resolve(
        activity: ComponentActivity,
        sourceIntent: Intent,
        tagRepository: TagRepository,
    ): Intent? {
        if (sourceIntent.action == Intent.ACTION_VIEW) {
            return buildMainIntentForDeepLink(activity, sourceIntent)
        }
        return null
    }

    private fun buildMainIntentForDeepLink(activity: ComponentActivity, sourceIntent: Intent): Intent {
        val uri = sourceIntent.data
        val tagId = parseSharedTagDeepLinkTagId(uri)
        val inviteToken = parseSharedTagInviteToken(uri)
        val promoCode = parsePromoCode(uri)
        return buildMainRedirectIntent(activity).apply {
            if (tagId != null) {
                putExtra(EXTRA_DEEP_LINK_TAG_ID, tagId)
            } else if (promoCode != null) {
                putExtra(EXTRA_PROMO_CODE, promoCode)
            } else if (inviteToken != null) {
                putExtra(EXTRA_SHARED_TAG_INVITE_TOKEN, inviteToken)
            } else if (isInviteUri(uri)) {
                putExtra(EXTRA_SHARED_TAG_INVITE_INVALID, true)
            } else if (isPromoUri(uri)) {
                putExtra(EXTRA_PROMO_CODE_INVALID, true)
            } else {
                putExtra(EXTRA_DEEP_LINK_INVALID, true)
            }
        }
    }

    private fun buildMainRedirectIntent(activity: ComponentActivity): Intent {
        return Intent(activity, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, UUID.randomUUID().toString())
        }
    }

    private fun parseSharedTagDeepLinkTagId(uri: android.net.Uri?): Long? {
        uri ?: return null
        if (uri.scheme != "urlsaver" || uri.host != "tag") return null
        return uri.pathSegments.singleOrNull()?.toLongOrNull()
    }

    private fun parseSharedTagInviteToken(uri: android.net.Uri?): String? {
        uri ?: return null
        if (!isInviteUri(uri)) return null
        val token = when {
            uri.scheme == "urlsaver" -> uri.pathSegments.singleOrNull()
            uri.scheme?.equals("https", ignoreCase = true) == true -> {
                uri.pathSegments.takeIf { it.firstOrNull() == "invite" }?.drop(1)?.singleOrNull()
            }
            else -> null
        }
        return token?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isInviteUri(uri: android.net.Uri?): Boolean {
        uri ?: return false
        return (uri.scheme == "urlsaver" && uri.host == "invite") ||
            (isCanonicalHttpsUri(uri) && isCanonicalWebInvitePath(uri))
    }

    private fun parsePromoCode(uri: android.net.Uri?): String? {
        uri ?: return null
        if (!isPromoUri(uri)) return null
        val queryParameterNames = uri.getQueryParameterNames()
        if ("code" in queryParameterNames) {
            return uri.getQueryParameter("code")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        val codeFromFragment = uri.fragment
            ?.substringAfter("code=", missingDelimiterValue = "")
            ?.substringBefore("&")
        return codeFromFragment
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isPromoUri(uri: android.net.Uri?): Boolean {
        uri ?: return false
        return (uri.scheme == "urlsaver" && uri.host == "promo") ||
            (isCanonicalHttpsUri(uri) && uri.encodedPath == "/promo")
    }

    private fun isCanonicalWebInvitePath(uri: android.net.Uri): Boolean {
        val encodedPath = uri.encodedPath ?: return false
        if (encodedPath == "/invite" || encodedPath == "/invite/") return true
        return encodedPath.startsWith("/invite/") &&
            uri.pathSegments.firstOrNull() == "invite" &&
            uri.pathSegments.drop(1).singleOrNull() != null
    }

    internal fun isCanonicalHttpsUri(
        uri: android.net.Uri,
        baseUrl: String = BuildConfig.INVITE_LINK_BASE_URL,
    ): Boolean {
        if (uri.scheme?.equals("https", ignoreCase = true) != true) return false
        val incomingHost = uri.host ?: return false
        if (!isCanonicalHost(incomingHost)) return false
        if (hasExplicitUserInfoOrPort(uri)) return false
        if (hasAmbiguousWebPath(uri)) return false
        val expectedHost = canonicalHttpsHost(baseUrl) ?: return false
        return expectedHost.equals(incomingHost, ignoreCase = true)
    }

    private fun canonicalHttpsHost(baseUrl: String): String? {
        val parsed = runCatching { android.net.Uri.parse(baseUrl) }.getOrNull() ?: return null
        if (parsed.scheme?.equals("https", ignoreCase = true) != true) return null
        if (hasExplicitUserInfoOrPort(parsed)) return null
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
        if (!isCanonicalHost(host)) return null
        return host
    }

    private fun isCanonicalHost(host: String): Boolean {
        if (host.isBlank() || host.endsWith(".")) return false
        return host.split('.').all { label ->
            label.isNotEmpty() &&
                !label.startsWith("-", ignoreCase = true) &&
                !label.endsWith("-", ignoreCase = true) &&
                !label.startsWith("xn--", ignoreCase = true) &&
                label.all { character ->
                    character in 'a'..'z' ||
                        character in 'A'..'Z' ||
                        character in '0'..'9' ||
                        character == '-'
                }
        }
    }

    private fun hasExplicitUserInfoOrPort(uri: android.net.Uri): Boolean {
        if (uri.userInfo != null || uri.port != -1) return true
        val hostAndPort = uri.encodedAuthority
            ?.substringAfterLast('@')
            ?: return false
        return hostAndPort.contains(':')
    }

    private fun hasAmbiguousWebPath(uri: android.net.Uri): Boolean {
        val encodedPath = uri.encodedPath.orEmpty()
        val normalizedPath = encodedPath.lowercase()
        return normalizedPath.contains("%2f") ||
            normalizedPath.contains("%5c") ||
            encodedPath.contains('\\') ||
            uri.path.orEmpty().contains('\\') ||
            encodedPath.contains("//")
    }
}
