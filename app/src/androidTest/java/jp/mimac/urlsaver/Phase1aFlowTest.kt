package jp.mimac.urlsaver

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.data.DEFAULT_COLLECTION_ID
import jp.mimac.urlsaver.data.DEFAULT_COLLECTION_NAME
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.CreateTagResult
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class Phase1aFlowTest {
    private companion object {
        const val UI_TIMEOUT_MS = 30_000L
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetAppState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        app.container.sharedTagAuthSessionProvider.updateSession(null)
        runBlocking {
            val db = app.container.database
            db.clearAllTables()
            val now = System.currentTimeMillis()
            db.collectionDao().insert(
                CollectionEntity(
                    id = DEFAULT_COLLECTION_ID,
                    name = DEFAULT_COLLECTION_NAME,
                    sortOrder = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
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

        composeRule.onNodeWithTag("detail_section_toggle").assertExists()
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
        swipeEntry(entryId, toArchive = false)
        waitForEntryGone(entryId)

        addManualUrl(url)
        waitForText("削除を取り消して復元しました")
        waitForEntryCard(url)

        swipeEntry(entryId, toArchive = false)
        waitForEntryGone(entryId)

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
        swipeEntry(entryId, toArchive = true)
        clickUndoAction()
        waitForEntryCard(url)

        swipeEntry(entryId, toArchive = false)
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
        composeRule.onNodeWithTag("detail_title_save").performClick()
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
        composeRule.onNodeWithTag("detail_memo_save").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("detail_memo_input").fetchSemanticsNodes().isEmpty()
        }
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
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
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
    }

    @Test
    fun detailNotFound_showsRecoveryAction() {
        dispatchShareSaveResult(ShareSaveResult.DUPLICATE_ACTIVE, Long.MAX_VALUE)

        waitForText("このURLはすでに保存済みです")
        composeRule.onNodeWithText("見る").performClick()
        composeRule.onNodeWithTag("detail_not_found").assertExists()
        composeRule.onNodeWithText("一覧に戻る").performClick()
        composeRule.onNodeWithText("保存したURL").assertExists()
    }

    @Test
    fun detailLocalTags_addCandidateDoesNotReplaceCurrentTag_andCurrentTagCanBeRemoved() {
        val suffix = System.currentTimeMillis()
        val firstTagName = "first-$suffix"
        val secondTagName = "second-$suffix"
        val orphanTagName = "orphan-$suffix"
        val url = uniqueUrl("detail-tags")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        val authUserId = "00000000-0000-0000-0000-000000000333"
        app.container.sharedTagAuthSessionProvider.updateSession(
            SharedTagAuthSession(
                authUserId = authUserId,
                accessToken = "token",
            ),
        )
        val (_, secondTagId, entryId, sameNameSharedTagId, orphanLocalTagId) = runBlocking {
            val first = app.container.repository.createCollection(firstTagName).collectionId!!
            val second = app.container.repository.createCollection(secondTagName).collectionId!!
            val saved = app.container.repository.saveFromManualInput(url, first)
            val sharedResult = app.container.tagRepository.createSyncedTagWithResult(secondTagName)
            check(sharedResult is CreateTagResult.Success)
            val orphanResult = app.container.tagRepository.createLocalTagWithResult(orphanTagName)
            check(orphanResult is CreateTagResult.Success)
            DetailLocalTagFixture(
                firstTagId = first,
                secondTagId = second,
                entryId = saved.entryId!!,
                sameNameSharedTagId = sharedResult.tagId,
                orphanLocalTagId = orphanResult.tagId,
            )
        }

        waitForEntryCard(url)
        composeRule.onNodeWithTag(entryCardTag(entryId)).performClick()
        waitForText(firstTagName)
        composeRule.onNodeWithTag("detail_local_tags_edit").performClick()
        waitForText("現在のタグ")
        composeRule.onNodeWithTag("detail_local_tag_add_tag_$orphanLocalTagId").assertDoesNotExist()

        composeRule.onNodeWithTag("detail_local_tag_add_collection_$secondTagId").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            val state = loadEntryTagState(entryId, authUserId)
            state.collectionId == secondTagId &&
                state.scopesFor(firstTagName) == listOf(SharedTagScope.LOCAL_ONLY) &&
                state.scopesFor(secondTagName) == listOf(SharedTagScope.LOCAL_ONLY)
        }
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(secondTagName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val afterAdd = loadEntryTagState(entryId, authUserId)
        assertEquals(secondTagId, afterAdd.collectionId)
        assertEquals(listOf(SharedTagScope.LOCAL_ONLY), afterAdd.scopesFor(firstTagName))
        assertEquals(listOf(SharedTagScope.LOCAL_ONLY), afterAdd.scopesFor(secondTagName))
        val secondLocalTagId = afterAdd.tagIdsFor(secondTagName).single()
        composeRule.onNodeWithTag("detail_local_tag_add_tag_$secondLocalTagId").assertDoesNotExist()

        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("detail_local_tag_add_collection_$secondTagId", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithTag("detail_shared_tags_edit").performClick()
        waitForText("共有タグを選ぶ / 作る")
        composeRule.onNodeWithTag("detail_shared_tag_add_$sameNameSharedTagId").assertDoesNotExist()
        composeRule.onNodeWithTag("detail_shared_tag_name_input").performTextInput(secondTagName)
        composeRule.onNodeWithTag("detail_shared_tag_create").performClick()
        waitForText("同じ名前の通常タグがあります。通常タグとして追加してください")
        val afterSharedBlocked = loadEntryTagState(entryId, authUserId)
        assertEquals(listOf(SharedTagScope.LOCAL_ONLY), afterSharedBlocked.scopesFor(secondTagName))
        composeRule.onNodeWithTag("detail_shared_tags_close").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("共有タグを選ぶ / 作る", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithTag("detail_local_tags_edit").performClick()
        waitForText("現在のタグ")

        val firstLocalTagId = afterAdd.tagIdsFor(firstTagName).single()
        composeRule.onNodeWithTag("detail_local_tag_remove_tag_$firstLocalTagId").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            val state = loadEntryTagState(entryId, authUserId)
            state.collectionId == secondTagId &&
                state.scopesFor(firstTagName).isEmpty() &&
                state.scopesFor(secondTagName) == listOf(SharedTagScope.LOCAL_ONLY)
        }

        val afterRemoveFirst = loadEntryTagState(entryId, authUserId)
        assertEquals(secondTagId, afterRemoveFirst.collectionId)
        assertTrue(afterRemoveFirst.scopesFor(firstTagName).isEmpty())
        assertEquals(listOf(SharedTagScope.LOCAL_ONLY), afterRemoveFirst.scopesFor(secondTagName))

        val secondRemoveTag = "detail_local_tag_remove_collection_$secondTagId"
        composeRule.onNodeWithTag(secondRemoveTag).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(secondRemoveTag).assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            val state = loadEntryTagState(entryId, authUserId)
            state.collectionId == DEFAULT_COLLECTION_ID &&
                state.scopesFor(secondTagName).isEmpty()
        }

        val afterRemoveSecond = loadEntryTagState(entryId, authUserId)
        assertEquals(DEFAULT_COLLECTION_ID, afterRemoveSecond.collectionId)
        assertTrue(afterRemoveSecond.scopesFor(secondTagName).isEmpty())
    }

    @Test
    fun main_doesNotShowPrivacyDisclosureActionInTopBar() {
        composeRule.onNodeWithContentDescription("プライバシー情報").assertDoesNotExist()
    }

    private fun addManualUrl(url: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        val saveResult = runBlocking {
            app.container.repository.saveFromManualInput(url)
        }
        composeRule.activity.runOnUiThread {
            composeRule.activity.consumeIncomingIntentForTest(
                Intent(composeRule.activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_SHARE_SAVE_RESULT, saveResult.result.name)
                    saveResult.entryId?.let { putExtra(EXTRA_SHARE_ENTRY_ID, it) }
                },
            )
        }
        composeRule.waitForIdle()
    }

    private fun dispatchShareSaveResult(result: ShareSaveResult, entryId: Long?) {
        composeRule.activity.runOnUiThread {
            composeRule.activity.consumeIncomingIntentForTest(
                Intent(composeRule.activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_SHARE_SAVE_RESULT, result.name)
                    entryId?.let { putExtra(EXTRA_SHARE_ENTRY_ID, it) }
                },
            )
        }
        composeRule.waitForIdle()
    }

    private fun seedEntry(url: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        runBlocking {
            app.container.repository.saveFromManualInput(url)
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertExists()
    }

    private fun waitForEntryCard(url: String): Long {
        var entryId: Long? = null
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            entryId = findEntryId(url)
            entryId != null
        }
        val resolvedEntryId = checkNotNull(entryId)
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(entryCardTag(resolvedEntryId)).fetchSemanticsNodes().isNotEmpty()
        }
        return resolvedEntryId
    }

    private fun waitForEntryGone(entryId: Long) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(entryCardTag(entryId)).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun clickUndoAction() {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("元に戻す", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("元に戻す", useUnmergedTree = true).performClick()
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
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
            (context as UrlSaverApp).container.database.urlEntryDao().findByNormalizedUrl(normalized)?.id
        }
    }

    private fun loadEntryTagState(entryId: Long, authUserId: String?): EntryTagState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return runBlocking {
            val db = (context as UrlSaverApp).container.database
            val entry = checkNotNull(db.urlEntryDao().findById(entryId))
            val tags = db.tagDao().getVisibleTagsForEntry(entryId, authUserId = authUserId)
            EntryTagState(
                collectionId = entry.collectionId,
                tagScopes = tags
                    .groupBy { it.name }
                    .mapValues { (_, values) -> values.map { it.scope }.sortedBy { it.name } },
                tagIds = tags
                    .groupBy { it.name }
                    .mapValues { (_, values) -> values.map { it.id }.sorted() },
            )
        }
    }

    private data class EntryTagState(
        val collectionId: Long,
        val tagScopes: Map<String, List<SharedTagScope>>,
        val tagIds: Map<String, List<Long>>,
    ) {
        fun scopesFor(name: String): List<SharedTagScope> = tagScopes[name].orEmpty()
        fun tagIdsFor(name: String): List<Long> = tagIds[name].orEmpty()
    }

    private data class DetailLocalTagFixture(
        val firstTagId: Long,
        val secondTagId: Long,
        val entryId: Long,
        val sameNameSharedTagId: Long,
        val orphanLocalTagId: Long,
    )

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

            WorkManager.getInstance(context).cancelUniqueWork("metadata:$entryId")
            delay(250)
            val dao = app.container.database.urlEntryDao()
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
        }
    }
}
