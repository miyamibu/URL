package jp.mimac.urlsaver

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class Phase1aFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manualSave_duplicateActive_cardTapDetail_andCollapsibleDetails() {
        val url = uniqueUrl("manual-dup")
        val display = displayOf(url)

        composeRule.onNodeWithText("すべて").assertExists()

        addManualUrl(url)
        waitForText("保存しました")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Webサイトのアイコン").fetchSemanticsNodes().isNotEmpty()
        }

        addManualUrl(url)
        waitForText("このURLはすでに保存済みです")
        composeRule.onNodeWithText("見る").assertExists()

        composeRule.onNodeWithText(display).performClick()
        composeRule.onNodeWithText("詳細").assertIsDisplayed()
        composeRule.onNodeWithText("開く").assertExists()

        composeRule.onNodeWithText("originalUrl").assertDoesNotExist()
        composeRule.onNodeWithTag("detail_section_toggle").performClick()
        composeRule.onNodeWithText("originalUrl").assertExists()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(url).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun archive_duplicateArchived_viewCta_andArchiveHasNoFab() {
        val url = uniqueUrl("archived")
        val display = displayOf(url)

        addManualUrl(url)
        waitForDisplay(display)
        composeRule.onNodeWithText(display).performClick()
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

        composeRule.onNodeWithText(display).performClick()
        composeRule.onNodeWithText("アーカイブ解除").performClick()
        waitForText("復元しました")
        composeRule.onNodeWithText("保存したURL").assertExists()
    }

    @Test
    fun deletePending_restoreThenFinalizeAfterFiveSeconds() {
        val url = uniqueUrl("delete")
        val display = displayOf(url)

        addManualUrl(url)
        waitForText("保存しました")
        composeRule.onNodeWithText(display).performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitForText("削除しました")

        addManualUrl(url)
        waitForText("削除を取り消して復元しました")

        composeRule.onNodeWithText(display).performClick()
        composeRule.onNodeWithText("削除").performClick()
        waitForText("削除しました")

        Thread.sleep(6200)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(display).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun archiveUndo_andDeleteUndoFromSnackbar() {
        val url = uniqueUrl("undo")
        val display = displayOf(url)

        seedEntry(url)
        waitForDisplay(display)
        composeRule.onNodeWithText(display).performClick()
        composeRule.onNodeWithText("アーカイブ").performClick()
        clickUndoAction()
        waitForDisplay(display)

        composeRule.onNodeWithText(display).performClick()
        composeRule.onNodeWithText("削除").performClick()
        clickUndoAction()
        waitForDisplay(display)
    }

    @Test
    fun titleUndo_memoDialog_andCopyNotification() {
        val url = uniqueUrl("title")
        val display = displayOf(url)

        addManualUrl(url)
        waitForText("保存しました")
        applyFetchedTitle(url, "Fetched title for edit")
        composeRule.onNodeWithText(display).performClick()
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
        waitForText("元に戻す")

        composeRule.onNodeWithText("元に戻す").performClick()
        composeRule.onNodeWithText("First").assertExists()

        composeRule.onNodeWithContentDescription("タイトルを編集").performClick()
        composeRule.onNodeWithTag("detail_title_input").performTextClearance()
        composeRule.onNodeWithTag("detail_title_input").performTextInput("   ")
        composeRule.onNodeWithTag("detail_title_input").performImeAction()
        waitForContentDescription("タイトルを編集")
        composeRule.onNodeWithContentDescription("タイトルを編集").performClick()
        composeRule.onNodeWithTag("detail_title_input").assertTextEquals("")
        composeRule.onNodeWithContentDescription("編集をキャンセル").performClick()

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
        val display = displayOf(url)

        addManualUrl(url)
        waitForDisplay(display)
        applyMetadataState(
            url = url,
            metadataState = MetadataState.FAILED,
            metadataError = MetadataError.NETWORK_IO,
            fetchedTitle = null,
        )

        composeRule.onNodeWithText(display).performClick()
        waitForText("情報を更新できませんでした")
        composeRule.onNodeWithText("再取得").assertExists().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("再取得中…").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("情報を更新中です").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun addManualUrl(url: String) {
        composeRule.onNodeWithContentDescription("手動追加").performClick()
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

    private fun waitForDisplay(display: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(display).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickUndoAction() {
        waitForText("元に戻す")
        composeRule.onNodeWithText("元に戻す").performClick()
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(contentDescription).fetchSemanticsNodes().isNotEmpty()
        }
    }

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
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as UrlSaverApp
        val normalized = UrlRules.normalize(url) ?: return
        runBlocking {
            val entry = app.container.repository.observeActiveEntries().first()
                .firstOrNull { it.normalizedUrl == normalized } ?: return@runBlocking
            app.container.repository.applyMetadataUpdate(
                entryId = entry.id,
                metadata = MetadataUpdate(
                    fetchedTitle = fetchedTitle,
                    thumbnailUrl = entry.thumbnailUrl,
                    metadataState = metadataState,
                    metadataFetchedAt = if (metadataState == MetadataState.PENDING) null else System.currentTimeMillis(),
                    metadataError = metadataError,
                    canonicalId = entry.canonicalId,
                    normalizedHost = entry.normalizedHost,
                    rawSourceHost = entry.rawSourceHost,
                ),
            )
        }
    }

    private fun displayOf(url: String): String = url.removePrefix("https://")
}
