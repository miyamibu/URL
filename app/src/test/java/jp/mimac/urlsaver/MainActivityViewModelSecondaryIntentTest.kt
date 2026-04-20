package jp.mimac.urlsaver

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
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SnackbarEventKind
import jp.mimac.urlsaver.ui.MainActivityViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityViewModelSecondaryIntentTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun consumeTagImportResult_showsSummaryAndActionNavigatesToTagDetail() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_TAG_IMPORT_TAG_ID, 9L)
            putExtra(EXTRA_TAG_IMPORT_TAG_NAME, "共有メモ")
            putExtra(EXTRA_TAG_IMPORT_CREATED, 2)
            putExtra(EXTRA_TAG_IMPORT_MERGED, 1)
            putExtra(EXTRA_TAG_IMPORT_SKIPPED, 3)
            putExtra(EXTRA_TAG_IMPORT_FAILED, 1)
        }

        viewModel.consumeTagImportResult(intent)

        val event = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.OPEN_TAG_DETAIL, event.kind)
        assertEquals("「共有メモ」をインポートしました（新規2 / 追加1 / スキップ3 / 失敗1）", event.message)
        assertEquals("見る", event.actionLabel)
        assertEquals(9L, event.tagId)

        viewModel.onSnackbarAction(event)
        val navigation = viewModel.navigationEvents.first()
        assertEquals(MainNavigationEvent.NavigateToTagDetail(9L), navigation)
    }

    @Test
    fun consumeTagImportResult_allDuplicatesShowsExistingMessage() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_TAG_IMPORT_TAG_ID, 11L)
            putExtra(EXTRA_TAG_IMPORT_TAG_NAME, "共有済み")
            putExtra(EXTRA_TAG_IMPORT_CREATED, 0)
            putExtra(EXTRA_TAG_IMPORT_MERGED, 0)
            putExtra(EXTRA_TAG_IMPORT_SKIPPED, 4)
            putExtra(EXTRA_TAG_IMPORT_FAILED, 0)
        }

        viewModel.consumeTagImportResult(intent)

        val event = viewModel.snackbarEvents.first()
        assertEquals("「共有済み」の全URLは既に登録済みです", event.message)
        assertEquals(SnackbarEventKind.OPEN_TAG_DETAIL, event.kind)
    }

    @Test
    fun consumeDeepLinkIntent_withTagIdNavigatesToTagDetail() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_DEEP_LINK_TAG_ID, 25L)
        }

        viewModel.consumeDeepLinkIntent(intent)

        val event = viewModel.navigationEvents.first()
        assertEquals(MainNavigationEvent.NavigateToTagDetail(25L), event)
    }

    @Test
    fun consumeDeepLinkIntent_invalidShowsInfoSnackbar() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_DEEP_LINK_INVALID, true)
        }

        viewModel.consumeDeepLinkIntent(intent)

        val event = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, event.kind)
        assertEquals("共有フォルダリンクを開けませんでした", event.message)
    }

    @Test
    fun consumeTagImportResult_recreatedIntentWithSameEventToken_isConsumedOnlyOnce() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val token = "event-tag-import-1"
        val firstIntent = Intent().apply {
            putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, token)
            putExtra(EXTRA_TAG_IMPORT_TAG_ID, 11L)
            putExtra(EXTRA_TAG_IMPORT_TAG_NAME, "共有済み")
            putExtra(EXTRA_TAG_IMPORT_CREATED, 0)
            putExtra(EXTRA_TAG_IMPORT_MERGED, 0)
            putExtra(EXTRA_TAG_IMPORT_SKIPPED, 4)
            putExtra(EXTRA_TAG_IMPORT_FAILED, 0)
        }
        val recreatedIntent = Intent().apply {
            putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, token)
            putExtra(EXTRA_TAG_IMPORT_TAG_ID, 11L)
            putExtra(EXTRA_TAG_IMPORT_TAG_NAME, "共有済み")
            putExtra(EXTRA_TAG_IMPORT_CREATED, 0)
            putExtra(EXTRA_TAG_IMPORT_MERGED, 0)
            putExtra(EXTRA_TAG_IMPORT_SKIPPED, 4)
            putExtra(EXTRA_TAG_IMPORT_FAILED, 0)
        }

        viewModel.consumeTagImportResult(firstIntent)
        viewModel.consumeTagImportResult(recreatedIntent)

        val firstEvent = viewModel.snackbarEvents.first()
        assertEquals("「共有済み」の全URLは既に登録済みです", firstEvent.message)
        val noSecondEvent = withTimeoutOrNull(100) { viewModel.snackbarEvents.first() }
        assertNull(noSecondEvent)
    }

    @Test
    fun consumeDeepLinkIntent_recreatedIntentWithSameEventToken_isConsumedOnlyOnce() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val token = "event-deep-link-1"
        val firstIntent = Intent().apply {
            putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, token)
            putExtra(EXTRA_DEEP_LINK_TAG_ID, 25L)
        }
        val recreatedIntent = Intent().apply {
            putExtra(EXTRA_MAIN_INTENT_EVENT_TOKEN, token)
            putExtra(EXTRA_DEEP_LINK_TAG_ID, 25L)
        }

        viewModel.consumeDeepLinkIntent(firstIntent)
        viewModel.consumeDeepLinkIntent(recreatedIntent)

        val firstNavigation = viewModel.navigationEvents.first()
        assertEquals(MainNavigationEvent.NavigateToTagDetail(25L), firstNavigation)
        val noSecondNavigation = withTimeoutOrNull(100) { viewModel.navigationEvents.first() }
        assertNull(noSecondNavigation)
    }

    private class FakeRepository : UrlRepository {
        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()
        override fun observeArchiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()
        override fun observeEntry(entryId: Long): Flow<UrlEntryEntity?> = emptyFlow()

        override suspend fun saveFromIntent(intent: Intent): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        override suspend fun saveFromManualInput(input: String): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        override suspend fun archive(entryId: Long): Boolean = false
        override suspend fun unarchive(entryId: Long): Boolean = false
        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? = null
        override suspend fun finalizePendingDelete(entryId: Long) = Unit
        override suspend fun cleanupExpiredPendingDeletes() = Unit
        override suspend fun restore(entryId: Long): Boolean = false
        override suspend fun saveUserTitle(entryId: Long, rawTitle: String) =
            jp.mimac.urlsaver.data.SaveTitleResult(success = false)
        override suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean = false
        override suspend fun saveMemo(entryId: Long, rawMemo: String) =
            jp.mimac.urlsaver.data.SaveMemoResult(success = false)
        override suspend fun applyCanonicalId(entryId: Long, canonicalId: String?) = Unit
        override suspend fun applyMetadataUpdate(entryId: Long, metadata: MetadataUpdate) = Unit
        override suspend fun retryMetadata(entryId: Long): Boolean = false
        override suspend fun loadEntry(entryId: Long): UrlEntryEntity? = null
    }
}
