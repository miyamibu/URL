package jp.mimac.urlsaver.ui

import android.content.Intent
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_INVALID
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_MAIN_INTENT_EVENT_TOKEN
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_CREATED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_FAILED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_MERGED
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
        val signature = buildTagImportSignature(
            intent = intent,
            tagId = tagId,
            tagName = tagName,
            created = created,
            merged = merged,
            skipped = skipped,
            failed = failed,
        )
        if (!consumedTagImportSignatures.add(signature)) return

        val event = if (created == 0 && merged == 0 && failed == 0) {
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
        if (!hasTagId && !isInvalid) return

        val tagId = if (hasTagId) intent.getLongExtra(EXTRA_DEEP_LINK_TAG_ID, 0L) else null
        val signature = buildDeepLinkSignature(
            intent = intent,
            tagId = tagId,
            isInvalid = isInvalid,
        )
        if (!consumedDeepLinkSignatures.add(signature)) return

        if (tagId != null) {
            navigate(MainNavigationEvent.NavigateToTagDetail(tagId))
            return
        }

        enqueueSnackbar(
            SnackbarEvent(
                kind = SnackbarEventKind.INFO,
                message = "共有フォルダリンクを開けませんでした",
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
    ): String {
        val eventToken = intent.getStringExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN)?.takeIf { it.isNotBlank() }
        if (eventToken != null) {
            return "event:$eventToken"
        }
        return "legacy:$tagId:$tagName:$created:$merged:$skipped:$failed"
    }

    private fun buildDeepLinkSignature(
        intent: Intent,
        tagId: Long?,
        isInvalid: Boolean,
    ): String {
        val eventToken = intent.getStringExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN)?.takeIf { it.isNotBlank() }
        if (eventToken != null) {
            return "event:$eventToken"
        }
        return "legacy:${tagId ?: "invalid"}:$isInvalid"
    }
}
