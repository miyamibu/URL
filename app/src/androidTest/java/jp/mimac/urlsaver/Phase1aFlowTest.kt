package jp.mimac.urlsaver

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Phase1aFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetAppState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            val db = AppDatabase.create(context)
            try {
                db.clearAllTables()
            } finally {
                db.close()
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("保存したURL").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun manualSave_duplicateActive_cardTapDetail_andCollapsibleDetails() {
        val url = uniqueUrl("manual-dup")

        composeRule.onNodeWithText("すべて").assertExists()

        addManualUrl(url)
        waitForText("保存しました")
        val entryId = waitForEntryCard(url)

        addManualUrl(url)
        waitForText("このURLはすでに保存済みです")
        composeRule.onNodeWithText("見る").assertExists()

        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("詳細").assertIsDisplayed()
        composeRule.onNodeWithText("開く").assertExists()
        composeRule.onNode(hasText("保存日時:", substring = true)).assertDoesNotExist()
        composeRule.onNodeWithText("Waybackで確認").assertDoesNotExist()

        composeRule.onNodeWithText("受信したURL (originalUrl)").assertDoesNotExist()
        composeRule.onNodeWithTag("detail_section_toggle").performClick()
        composeRule.onNodeWithText("受信したURL (originalUrl)").assertExists()
        composeRule.onNodeWithText("保存・重複判定・開くURL (normalizedUrl)").assertExists()
        composeRule.onNodeWithText("画面表示用URL (displayUrl)").assertExists()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun archive_duplicateArchived_viewCta_andArchiveHasNoFab() {
        val url = uniqueUrl("archived")

        addManualUrl(url)
        val entryId = waitForEntryCard(url)
        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("アーカイブ").performClick()
        waitForText("アーカイブしました")

        // Wait for archive undo snackbar to close before checking duplicate archived CTA.
        Thread.sleep(5200)

        addManualUrl(url)
        waitForText("このURLはアーカイブ済みです")
        composeRule.onNodeWithText("見る").assertExists().performClick()
        composeRule.onNodeWithText("詳細").assertIsDisplayed()
        composeRule.onNodeWithText("アーカイブ解除").assertExists()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        composeRule.onNodeWithContentDescription("アーカイブ").performClick()
        composeRule.onNodeWithText("アーカイブ").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("手動追加").assertDoesNotExist()

        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("アーカイブ解除").performClick()
        waitForText("復元しました")
        composeRule.onNodeWithText("保存したURL").assertExists()
    }

    @Test
    fun deletePending_restoreThenFinalizeAfterFiveSeconds() {
        val url = uniqueUrl("delete")

        addManualUrl(url)
        waitForText("保存しました")
        val entryId = waitForEntryCard(url)
        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitForText("削除しました")

        addManualUrl(url)
        waitForText("削除を取り消して復元しました")
        waitForEntryCard(url)

        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitForText("削除しました")

        Thread.sleep(6200)
        waitForEntryGone(entryId)
    }

    @Test
    fun mainSwipeRight_archivesAndUndoRestores() {
        val url = uniqueUrl("main-swipe-archive")

        addManualUrl(url)
        val entryId = waitForEntryCard(url)
        swipeEntry(entryId, toArchive = true)
        waitForText("アーカイブしました")
        waitForEntryGone(entryId)

        clickUndoAction()
        waitForEntryCard(url)
    }

    @Test
    fun mainSwipeLeft_marksPendingDeleteAndUndoRestores() {
        val url = uniqueUrl("main-swipe-delete")

        addManualUrl(url)
        val entryId = waitForEntryCard(url)
        swipeEntry(entryId, toArchive = false)
        waitForText("削除しました")
        waitForEntryGone(entryId)

        clickUndoAction()
        waitForEntryCard(url)
    }

    @Test
    fun archiveUndo_andDeleteUndoFromSnackbar() {
        val url = uniqueUrl("undo")

        seedEntry(url)
        val entryId = waitForEntryCard(url)
        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("アーカイブ").performClick()
        waitForText("アーカイブしました")
        clickUndoAction()
        waitForEntryCard(url)

        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitForText("削除しました")
        clickUndoAction()
        waitForEntryCard(url)
    }

    @Test
    fun titleUndo_memoDialog_andCopyNotification() {
        val url = uniqueUrl("title")

        addManualUrl(url)
        waitForText("保存しました")
        applyFetchedTitle(url, "Fetched title for edit")
        val entryId = waitForEntryCard(url)
        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        waitForText("Fetched title for edit")

        composeRule.onNodeWithContentDescription("タイトルを編集").assertExists().performClick()
        composeRule.onNodeWithContentDescription("編集をキャンセル").assertExists()
        composeRule.onNodeWithText("Fetched title for edit").assertDoesNotExist()

        val titleField = composeRule.onNodeWithTag("detail_title_input")
        titleField.assertTextEquals("")
        titleField.performTextClearance()
        titleField.performTextInput("First")
        titleField.performImeAction()
        waitForText("タイトルを保存しました")

        composeRule.onNodeWithContentDescription("タイトルを編集").performClick()
        composeRule.onNodeWithTag("detail_title_input").performTextClearance()
        composeRule.onNodeWithTag("detail_title_input").performTextInput("Second")
        composeRule.onNodeWithTag("detail_title_input").performImeAction()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Second").fetchSemanticsNodes().isNotEmpty()
        }
        clickUndoAction()
        composeRule.onNodeWithText("First").assertExists()

        composeRule.onNodeWithContentDescription("タイトルを編集").performClick()
        composeRule.onNodeWithTag("detail_title_input").performTextClearance()
        composeRule.onNodeWithTag("detail_title_input").performTextInput("   ")
        composeRule.onNodeWithTag("detail_title_input").performImeAction()
        waitForContentDescription("タイトルを編集")
        composeRule.onNodeWithContentDescription("タイトルを編集").performClick()
        composeRule.onNodeWithTag("detail_title_input").assertTextEquals("")
        composeRule.onNodeWithContentDescription("編集をキャンセル").performClick()

        val memoButtonBounds = composeRule.onNodeWithText("メモを編集").fetchSemanticsNode().boundsInRoot
        val memoBodyBounds = composeRule.onNodeWithText("メモなし").fetchSemanticsNode().boundsInRoot
        assertTrue(memoBodyBounds.top > memoButtonBounds.bottom)

        composeRule.onNodeWithText("メモを編集").performClick()
        composeRule.onNodeWithTag("detail_memo_input").performTextClearance()
        composeRule.onNodeWithTag("detail_memo_input").performTextInput("   ")
        composeRule.onNodeWithText("保存").performClick()
        waitForText("メモを保存しました")
        composeRule.onNodeWithText("メモなし").assertExists()

        composeRule.onNodeWithText("コピー").performClick()
        waitForText("リンクをコピーしました")

        composeRule.onNodeWithText("アーカイブ").performClick()
        waitForText("アーカイブしました")
    }

    @Test
    fun metadataFailed_detailShowsRetryAndRetryingState() {
        val url = uniqueUrl("metadata-retry")

        addManualUrl(url)
        val entryId = waitForEntryCard(url)
        applyMetadataState(
            url = url,
            metadataState = MetadataState.FAILED,
            metadataError = MetadataError.NETWORK_IO,
            fetchedTitle = null,
        )

        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        waitForText("一時的に情報を取得できませんでした")
        composeRule.onNodeWithText("再取得").assertExists().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("再取得中…").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("情報を更新中です").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun pendingDelayed_detailShowsDelayMessageAndRetryReason() {
        val url = uniqueUrl("metadata-delayed")

        addManualUrl(url)
        val entryId = waitForEntryCard(url)
        applyMetadataState(
            url = url,
            metadataState = MetadataState.PENDING,
            metadataError = null,
            fetchedTitle = null,
            metadataRequestedAt = System.currentTimeMillis() - (16 * 60 * 1000L),
        )

        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        waitForText("情報の更新に時間がかかっています")
        composeRule.onNodeWithText("再取得").assertExists().performClick()
        waitForText("この状態では再取得できません")
    }

    @Test
    fun detailNotFound_showsRecoveryAction() {
        composeRule.activity.startActivity(
            Intent(composeRule.activity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.DUPLICATE_ACTIVE.name)
                putExtra(EXTRA_SHARE_ENTRY_ID, Long.MAX_VALUE)
            },
        )

        waitForText("このURLはすでに保存済みです")
        composeRule.onNodeWithText("見る").performClick()
        composeRule.onNodeWithTag("detail_not_found").assertExists()
        composeRule.onNodeWithText("一覧に戻る").performClick()
        composeRule.onNodeWithText("保存したURL").assertExists()
    }

    @Test
    fun main_hasPrivacyDisclosureDialog() {
        composeRule.onNodeWithContentDescription("プライバシー情報").assertExists().performClick()
        composeRule.onNodeWithText("データの取り扱い").assertExists()
        composeRule.onNode(hasText("端末内に保存されます", substring = true)).assertExists()
        composeRule.onNodeWithText("閉じる").performClick()
    }

    private fun addManualUrl(url: String) {
        composeRule.onNodeWithContentDescription("手動追加").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("manual_input_field").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("manual_input_field").performTextClearance()
        composeRule.onNodeWithTag("manual_input_field").performTextInput(url)
        composeRule.onNodeWithTag("manual_input_save").performClick()
    }

    private fun seedEntry(url: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        runBlocking {
            app.container.repository.saveFromManualInput(url)
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertExists()
    }

    private fun waitForEntryCard(url: String): Long {
        var entryId: Long? = null
        composeRule.waitUntil(timeoutMillis = 10_000) {
            entryId = findEntryId(url)
            entryId != null
        }
        val resolvedEntryId = checkNotNull(entryId)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(entryCardTag(resolvedEntryId)).fetchSemanticsNodes().isNotEmpty()
        }
        return resolvedEntryId
    }

    private fun waitForEntryGone(entryId: Long) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(entryCardTag(entryId)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun clickUndoAction() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("元に戻す", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("元に戻す", useUnmergedTree = true).performClick()
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(contentDescription).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun swipeEntry(entryId: Long, toArchive: Boolean) {
        val node = composeRule.onNodeWithTag(swipeTag(entryId))
        if (toArchive) {
            node.performTouchInput { swipeRight() }
        } else {
            node.performTouchInput { swipeLeft() }
        }
    }

    private fun findEntryId(url: String): Long? {
        val normalized = UrlRules.normalize(url) ?: return null
        val context = ApplicationProvider.getApplicationContext<Context>()
        return runBlocking {
            val db = AppDatabase.create(context)
            try {
                db.urlEntryDao().findByNormalizedUrl(normalized)?.id
            } finally {
                db.close()
            }
        }
    }

    private fun entryCardTag(entryId: Long): String = "entry_card_$entryId"

    private fun swipeTag(entryId: Long): String = "main_entry_swipe_$entryId"

    private fun uniqueUrl(prefix: String): String {
        val id = System.currentTimeMillis()
        return "https://example.com/$prefix-$id"
    }

    private fun applyFetchedTitle(url: String, fetchedTitle: String) {
        applyMetadataState(
            url = url,
            metadataState = MetadataState.READY,
            metadataError = null,
            fetchedTitle = fetchedTitle,
        )
    }

    private fun applyMetadataState(
        url: String,
        metadataState: MetadataState,
        metadataError: MetadataError?,
        fetchedTitle: String?,
        metadataRequestedAt: Long? = null,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        val normalized = UrlRules.normalize(url) ?: return
        runBlocking {
            val entryId = app.container.repository.observeActiveEntries().first()
                .firstOrNull { it.normalizedUrl == normalized }
                ?.id ?: return@runBlocking

            val db = AppDatabase.create(context)
            try {
                val dao = db.urlEntryDao()
                val entry = dao.findById(entryId) ?: return@runBlocking
                dao.update(
                    entry.copy(
                        fetchedTitle = fetchedTitle,
                        metadataState = metadataState,
                        metadataFetchedAt = if (metadataState == MetadataState.PENDING) null else System.currentTimeMillis(),
                        metadataError = metadataError,
                        metadataRequestedAt = metadataRequestedAt ?: entry.metadataRequestedAt,
                    ),
                )
            } finally {
                db.close()
            }
        }
    }
}
