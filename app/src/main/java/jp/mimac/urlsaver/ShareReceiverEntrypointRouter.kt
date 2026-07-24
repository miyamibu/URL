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
            uri.scheme == "https" && uri.host == "miyamibu.xyz" -> {
                uri.pathSegments.takeIf { it.firstOrNull() == "invite" }?.drop(1)?.singleOrNull()
            }
            else -> null
        }
        return token?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf(::isSafeInviteToken)
    }

    private fun isInviteUri(uri: android.net.Uri?): Boolean {
        uri ?: return false
        return (uri.scheme == "urlsaver" && uri.host == "invite") ||
            (uri.scheme == "https" && uri.host == "miyamibu.xyz" && uri.pathSegments.firstOrNull() == "invite")
    }

    private fun parsePromoCode(uri: android.net.Uri?): String? {
        uri ?: return null
        if (!isPromoUri(uri)) return null
        val codeFromQuery = uri.getQueryParameter("code")
        val codeFromFragment = uri.fragment
            ?.substringAfter("code=", missingDelimiterValue = "")
            ?.substringBefore("&")
        return (codeFromQuery ?: codeFromFragment)
            ?.trim()
            ?.takeIf { it.length in 4..128 && it.all { char -> char.isLetterOrDigit() || char in " _-" } }
    }

    private fun isPromoUri(uri: android.net.Uri?): Boolean {
        uri ?: return false
        return (uri.scheme == "urlsaver" && uri.host == "promo") ||
            (uri.scheme == "https" && uri.host == "miyamibu.xyz" && uri.pathSegments.firstOrNull() == "promo")
    }

    private fun isSafeInviteToken(value: String): Boolean {
        return value.length in 8..256 && value.all { it.isLetterOrDigit() || it in "._~-" }
    }
}
