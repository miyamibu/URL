package jp.mimac.urlsaver

import android.content.Intent
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_CREATED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_DUPLICATE_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_FAILED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_RESTORED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_TOTAL_COUNT
import androidx.compose.material3.SnackbarDuration
import jp.mimac.urlsaver.data.EXTRA_SHARE_DEGRADATION_NOTICE
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.MetadataUpdate
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.DetailEffect
import jp.mimac.urlsaver.domain.MainNavigationEvent
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.SnackbarEvent
import jp.mimac.urlsaver.domain.SnackbarEventKind
import jp.mimac.urlsaver.domain.SnackbarTargetRoute
import jp.mimac.urlsaver.ui.MainActivityViewModel
import jp.mimac.urlsaver.ui.Routes
import jp.mimac.urlsaver.util.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun consumeShareResult_duplicateArchived_showsOpenExistingActionOnMainRoute() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.DUPLICATE_ARCHIVED.name)
            putExtra(EXTRA_SHARE_ENTRY_ID, 19L)
        }

        viewModel.consumeShareResult(intent, Routes.MAIN)

        val event = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.OPEN_EXISTING, event.kind)
        assertEquals("このURLはアーカイブ済みです", event.message)
        assertEquals("見る", event.actionLabel)
        assertEquals(19L, event.entryId)
    }

    @Test
    fun consumeShareResult_duplicateArchived_onArchiveRouteShowsInfoOnly() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.DUPLICATE_ARCHIVED.name)
        }

        viewModel.consumeShareResult(intent, Routes.ARCHIVE)

        val event = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, event.kind)
        assertEquals("このURLはアーカイブ済みです", event.message)
        assertNull(event.actionLabel)
    }

    @Test
    fun consumeShareResult_createdDuplicateAndErrors_emitExpectedMessages() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)

        val createdIntent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.CREATED.name)
        }
        viewModel.consumeShareResult(createdIntent, Routes.MAIN)
        val created = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, created.kind)
        assertEquals("保存しました", created.message)

        val dupIntent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.DUPLICATE_ACTIVE.name)
        }
        viewModel.consumeShareResult(dupIntent, Routes.MAIN)
        val dup = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.OPEN_EXISTING, dup.kind)
        assertEquals("このURLはすでに保存済みです", dup.message)
        assertEquals("見る", dup.actionLabel)

        val invalidIntent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.INVALID_URL.name)
        }
        viewModel.consumeShareResult(invalidIntent, Routes.MAIN)
        val invalid = viewModel.snackbarEvents.first()
        assertEquals("有効なURLではありませんでした", invalid.message)

        val noUrlIntent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.NO_URL_FOUND.name)
        }
        viewModel.consumeShareResult(noUrlIntent, Routes.MAIN)
        val noUrl = viewModel.snackbarEvents.first()
        assertEquals("URLが見つかりませんでした", noUrl.message)

        val saveFailedIntent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.SAVE_FAILED.name)
        }
        viewModel.consumeShareResult(saveFailedIntent, Routes.MAIN)
        val saveFailed = viewModel.snackbarEvents.first()
        assertEquals("保存できませんでした", saveFailed.message)
    }

    @Test
    fun consumeShareResult_truncatedNotice_showsExplicitDegradationMessage() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.CREATED.name)
            putExtra(EXTRA_SHARE_DEGRADATION_NOTICE, SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL)
        }

        viewModel.consumeShareResult(intent, Routes.MAIN)

        val created = viewModel.snackbarEvents.first()
        assertEquals("保存しました", created.message)
        val degradation = viewModel.snackbarEvents.first()
        assertEquals("共有内容に複数URLが含まれていたため、1件目のみ保存しました", degradation.message)
    }

    @Test
    fun consumeShareResult_batchProcessed_showsSummaryMessage() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        val intent = Intent().apply {
            putExtra(EXTRA_SHARE_SAVE_RESULT, ShareSaveResult.BATCH_PROCESSED.name)
            putExtra(EXTRA_SHARE_BATCH_TOTAL_COUNT, 4)
            putExtra(EXTRA_SHARE_BATCH_CREATED_COUNT, 2)
            putExtra(EXTRA_SHARE_BATCH_DUPLICATE_COUNT, 1)
            putExtra(EXTRA_SHARE_BATCH_RESTORED_COUNT, 1)
            putExtra(EXTRA_SHARE_BATCH_FAILED_COUNT, 0)
        }

        viewModel.consumeShareResult(intent, Routes.MAIN)

        val event = viewModel.snackbarEvents.first()
        assertEquals(
            "複数URLの共有を処理しました（4件） 新規2件 / 既存1件 / 復元1件 / 失敗0件",
            event.message,
        )
    }

    @Test
    fun startDeleteTimer_usesInjectedClockAsDelaySource() = runTest {
        val repository = FakeRepository()
        val fakeClock = FakeClock(now = 1_000L)
        val viewModel = MainActivityViewModel(repository, fakeClock)

        viewModel.startDeleteTimer(entryId = 55L, pendingDeletionUntil = 1_000L)
        advanceUntilIdle()

        assertEquals(listOf(55L), repository.finalizeDeleteCalls)
    }

    @Test
    fun onSnackbarAction_openExisting_withEntryIdNavigatesToDetail() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)

        viewModel.onSnackbarAction(
            SnackbarEvent(
                kind = SnackbarEventKind.OPEN_EXISTING,
                message = "duplicate",
                actionLabel = "見る",
                entryId = 42L,
                targetRoute = SnackbarTargetRoute.ARCHIVE,
            ),
        )

        val navEvent = viewModel.navigationEvents.first()
        assertEquals(MainNavigationEvent.NavigateToDetail(42L), navEvent)
    }

    @Test
    fun onSnackbarAction_openExisting_withoutEntryIdUsesTargetRoute() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)

        viewModel.onSnackbarAction(
            SnackbarEvent(
                kind = SnackbarEventKind.OPEN_EXISTING,
                message = "duplicate",
                actionLabel = "見る",
                targetRoute = SnackbarTargetRoute.ARCHIVE,
            ),
        )

        val navEvent = viewModel.navigationEvents.first()
        assertEquals(MainNavigationEvent.NavigateToArchive, navEvent)
    }

    @Test
    fun titleUndo_eventUsesIndefiniteWithFiveSeconds() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)

        viewModel.onDetailEffect(DetailEffect.TitleEdited(entryId = 12L, oldTitle = "before"))

        val event = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.UNDO_TITLE_EDIT, event.kind)
        assertEquals(SnackbarDuration.Indefinite, event.duration)
        assertEquals(5000L, event.customDurationMillis)
        assertEquals("元に戻す", event.actionLabel)
    }

    @Test
    fun titleUndo_survivesBackNavigationToMain() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        viewModel.onDetailEffect(DetailEffect.TitleEdited(entryId = 77L, oldTitle = "old"))
        val undoEvent = viewModel.snackbarEvents.first()

        viewModel.onRouteChanged(Routes.MAIN)
        viewModel.onSnackbarAction(undoEvent)

        assertEquals(listOf(77L to "old"), repository.restoreTitleCalls)
    }

    @Test
    fun titleUndo_isInvalidatedWhenNavigatingToDifferentDetail() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        viewModel.onDetailEffect(DetailEffect.TitleEdited(entryId = 77L, oldTitle = "old"))
        val undoEvent = viewModel.snackbarEvents.first()

        viewModel.onRouteChanged("detail/88")
        viewModel.onSnackbarAction(undoEvent)

        assertTrue(repository.restoreTitleCalls.isEmpty())
    }

    @Test
    fun titleUndo_isInvalidatedByArchiveEffect() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        viewModel.onDetailEffect(DetailEffect.TitleEdited(entryId = 77L, oldTitle = "old"))
        val oldUndoEvent = viewModel.snackbarEvents.first()

        viewModel.onDetailEffect(DetailEffect.NavigateBackAfterArchive(entryId = 77L))
        viewModel.onSnackbarAction(oldUndoEvent)

        assertTrue(repository.restoreTitleCalls.isEmpty())
    }

    @Test
    fun titleUndo_isInvalidatedByPendingDeleteEffect() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)
        viewModel.onDetailEffect(DetailEffect.TitleEdited(entryId = 77L, oldTitle = "old"))
        val oldUndoEvent = viewModel.snackbarEvents.first()

        viewModel.onDetailEffect(DetailEffect.NavigateBackAfterPendingDelete(entryId = 77L))
        viewModel.onSnackbarAction(oldUndoEvent)

        assertTrue(repository.restoreTitleCalls.isEmpty())
    }

    @Test
    fun copyAndMemoNotifications_areInfoEvents() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)

        viewModel.notifyCopySuccess()
        val copy = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, copy.kind)
        assertEquals("リンクをコピーしました", copy.message)
        assertNull(copy.actionLabel)

        viewModel.notifyMemoSaved()
        val memo = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, memo.kind)
        assertEquals("メモを保存しました", memo.message)
        assertNull(memo.actionLabel)
    }

    private class FakeRepository : UrlRepository {
        val restoreTitleCalls = mutableListOf<Pair<Long, String?>>()
        val finalizeDeleteCalls = mutableListOf<Long>()

        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()
        override fun observeArchiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()
        override fun observeEntry(entryId: Long): Flow<UrlEntryEntity?> = emptyFlow()

        override suspend fun saveFromIntent(intent: Intent): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        override suspend fun saveFromManualInput(input: String): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        override suspend fun archive(entryId: Long): Boolean = false
        override suspend fun unarchive(entryId: Long): Boolean = false
        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? = null
        override suspend fun finalizePendingDelete(entryId: Long) {
            finalizeDeleteCalls += entryId
        }
        override suspend fun cleanupExpiredPendingDeletes() = Unit
        override suspend fun restore(entryId: Long): Boolean = false

        override suspend fun saveUserTitle(
            entryId: Long,
            rawTitle: String,
        ) = jp.mimac.urlsaver.data.SaveTitleResult(success = false)

        override suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean {
            restoreTitleCalls += entryId to oldTitle
            return true
        }

        override suspend fun saveMemo(
            entryId: Long,
            rawMemo: String,
        ) = jp.mimac.urlsaver.data.SaveMemoResult(success = false)

        override suspend fun applyCanonicalId(entryId: Long, canonicalId: String?) = Unit
        override suspend fun applyMetadataUpdate(entryId: Long, metadata: MetadataUpdate) = Unit
        override suspend fun retryMetadata(entryId: Long): Boolean = false
        override suspend fun loadEntry(entryId: Long): UrlEntryEntity? = null
    }

    private class FakeClock(
        private val now: Long,
    ) : AppClock {
        override fun nowEpochMillis(): Long = now
    }
}
