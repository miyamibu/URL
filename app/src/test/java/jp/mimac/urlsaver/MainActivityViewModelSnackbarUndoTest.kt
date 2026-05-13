package jp.mimac.urlsaver

import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import jp.mimac.urlsaver.data.MetadataUpdate
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
class MainActivityViewModelSnackbarUndoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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
    fun titleUndo_eventUsesIndefiniteWithFifteenSeconds() = runTest {
        val repository = FakeRepository()
        val viewModel = MainActivityViewModel(repository)

        viewModel.onDetailEffect(DetailEffect.TitleEdited(entryId = 12L, oldTitle = "before"))

        val event = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.UNDO_TITLE_EDIT, event.kind)
        assertEquals(SnackbarDuration.Indefinite, event.duration)
        assertEquals(15000L, event.customDurationMillis)
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
    fun copyMemoOpenRetryNotifications_areInfoEvents() = runTest {
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

        viewModel.notifyOpenFailed()
        val openFailed = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, openFailed.kind)
        assertEquals("リンクを開けませんでした", openFailed.message)
        assertNull(openFailed.actionLabel)

        viewModel.notifyMetadataRetryUnavailable()
        val retryUnavailable = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, retryUnavailable.kind)
        assertEquals("この状態では再取得できません", retryUnavailable.message)
        assertNull(retryUnavailable.actionLabel)

        viewModel.notifyArchiveFailed()
        val archiveFailed = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, archiveFailed.kind)
        assertEquals("アーカイブできませんでした", archiveFailed.message)
        assertNull(archiveFailed.actionLabel)

        viewModel.notifyDeleteFailed()
        val deleteFailed = viewModel.snackbarEvents.first()
        assertEquals(SnackbarEventKind.INFO, deleteFailed.kind)
        assertEquals("削除できませんでした", deleteFailed.message)
        assertNull(deleteFailed.actionLabel)
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
        override suspend fun saveUserTitle(entryId: Long, rawTitle: String) =
            jp.mimac.urlsaver.data.SaveTitleResult(success = false)
        override suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean {
            restoreTitleCalls += entryId to oldTitle
            return true
        }
        override suspend fun saveMemo(entryId: Long, rawMemo: String) =
            jp.mimac.urlsaver.data.SaveMemoResult(success = false)
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
