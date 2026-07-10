package jp.mimac.urlsaver.ui

import android.content.Intent
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_INVALID
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_MAIN_INTENT_EVENT_TOKEN
import jp.mimac.urlsaver.data.EXTRA_PROMO_CODE
import jp.mimac.urlsaver.data.EXTRA_PROMO_CODE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_TOKEN
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_CREATED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_FAILED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_CANCELLED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_MERGED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_MESSAGE
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_SKIPPED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_TAG_NAME
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.SnackbarEvent
import jp.mimac.urlsaver.domain.SnackbarEventKind

internal class MainActivitySecondaryIntentHandler(
    private val enqueueSnackbar: (SnackbarEvent) -> Unit,
    private val navigate: (MainNavigationEvent) -> Unit,
) {
    private val consumedTagImportSignatures = mutableSetOf<String>()
    private val consumedDeepLinkSignatures = mutableSetOf<String>()

    fun consumeTagImportResult(intent: Intent) {
        val tagId = if (intent.hasExtra(EXTRA_TAG_IMPORT_TAG_ID)) {
            intent.getLongExtra(EXTRA_TAG_IMPORT_TAG_ID, -1L)
        } else {
            return
        }
        val tagName = intent.getStringExtra(EXTRA_TAG_IMPORT_TAG_NAME) ?: return
        val created = intent.getIntExtra(EXTRA_TAG_IMPORT_CREATED, 0)
        val merged = intent.getIntExtra(EXTRA_TAG_IMPORT_MERGED, 0)
        val skipped = intent.getIntExtra(EXTRA_TAG_IMPORT_SKIPPED, 0)
        val failed = intent.getIntExtra(EXTRA_TAG_IMPORT_FAILED, 0)
        val cancelled = intent.getBooleanExtra(EXTRA_TAG_IMPORT_CANCELLED, false)
        val message = intent.getStringExtra(EXTRA_TAG_IMPORT_MESSAGE)
        val signature = buildTagImportSignature(
            intent = intent,
            tagId = tagId,
            tagName = tagName,
            created = created,
            merged = merged,
            skipped = skipped,
            failed = failed,
            cancelled = cancelled,
            message = message,
        )
        if (!consumedTagImportSignatures.add(signature)) return

        val event = if (cancelled) {
            SnackbarEvent(
                kind = SnackbarEventKind.INFO,
                message = message ?: "上限によりインポートをキャンセルしました",
            )
        } else if (created == 0 && merged == 0 && failed == 0) {
            SnackbarEvent(
                kind = SnackbarEventKind.OPEN_TAG_DETAIL,
                message = "「$tagName」の全URLは既に登録済みです",
                actionLabel = "見る",
                tagId = tagId,
            )
        } else {
            SnackbarEvent(
                kind = SnackbarEventKind.OPEN_TAG_DETAIL,
                message = "「$tagName」をインポートしました（新規$created / 追加$merged / スキップ$skipped / 失敗$failed）",
                actionLabel = "見る",
                tagId = tagId,
            )
        }
        enqueueSnackbar(event)
    }

    fun consumeDeepLinkIntent(intent: Intent) {
        val hasTagId = intent.hasExtra(EXTRA_DEEP_LINK_TAG_ID)
        val isInvalid = intent.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false)
        val inviteToken = intent.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN)?.takeIf { it.isNotBlank() }
        val isInviteInvalid = intent.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false)
        val promoCode = intent.getStringExtra(EXTRA_PROMO_CODE)?.takeIf { it.isNotBlank() }
        val isPromoInvalid = intent.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false)
        if (!hasTagId && !isInvalid && inviteToken == null && !isInviteInvalid && promoCode == null && !isPromoInvalid) return

        val tagId = if (hasTagId) intent.getLongExtra(EXTRA_DEEP_LINK_TAG_ID, 0L) else null
        val signature = buildDeepLinkSignature(
            intent = intent,
            tagId = tagId,
            isInvalid = isInvalid,
            inviteToken = inviteToken,
            isInviteInvalid = isInviteInvalid,
            promoCode = promoCode,
            isPromoInvalid = isPromoInvalid,
        )
        if (!consumedDeepLinkSignatures.add(signature)) return

        if (promoCode != null) {
            navigate(MainNavigationEvent.NavigateToPromoCode(promoCode))
            return
        }

        if (inviteToken != null) {
            navigate(MainNavigationEvent.NavigateToInvite(inviteToken))
            return
        }

        if (tagId != null) {
            navigate(MainNavigationEvent.NavigateToTagDetail(tagId))
            return
        }

        enqueueSnackbar(
            SnackbarEvent(
                kind = SnackbarEventKind.INFO,
                message = when {
                    isInviteInvalid -> "共有招待リンクを開けませんでした"
                    isPromoInvalid -> "優待コードリンクを開けませんでした"
                    else -> "共有フォルダリンクを開けませんでした"
                },
            ),
        )
    }

    private fun buildTagImportSignature(
        intent: Intent,
        tagId: Long,
        tagName: String,
        created: Int,
        merged: Int,
        skipped: Int,
        failed: Int,
        cancelled: Boolean,
        message: String?,
    ): String {
        val eventToken = intent.getStringExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN)?.takeIf { it.isNotBlank() }
        if (eventToken != null) {
            return "event:$eventToken"
        }
        return "legacy:$tagId:$tagName:$created:$merged:$skipped:$failed:$cancelled:${message.orEmpty()}"
    }

    private fun buildDeepLinkSignature(
        intent: Intent,
        tagId: Long?,
        isInvalid: Boolean,
        inviteToken: String?,
        isInviteInvalid: Boolean,
        promoCode: String?,
        isPromoInvalid: Boolean,
    ): String {
        val eventToken = intent.getStringExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN)?.takeIf { it.isNotBlank() }
        if (eventToken != null) {
            return "event:$eventToken"
        }
        return "legacy:${tagId ?: "none"}:${inviteToken ?: "none"}:${promoCode ?: "none"}:$isInvalid:$isInviteInvalid:$isPromoInvalid"
    }
}
