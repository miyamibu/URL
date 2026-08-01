package jp.mimac.urlsaver

import android.content.Intent
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.SaveMemoResult
import jp.mimac.urlsaver.data.SaveTitleResult
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.ArchiveViewModel
import jp.mimac.urlsaver.ui.ListFilterLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun entryCardDisplayMode_reflectsStoredPreference() = runTest {
        val store = FakeDisplayModeStore(initialMode = EntryCardDisplayMode.COMPACT)
        val viewModel = ArchiveViewModel(
            repository = FakeUrlRepository(),
            displayModeStore = store,
        )

        assertEquals(EntryCardDisplayMode.COMPACT, viewModel.entryCardDisplayMode.value)
    }

    @Test
    fun archiveEntries_errorPreservesCachedEntries_andRetryRecovers() = runTest {
        val cached = archiveEntry(id = 71L)
        val recovered = archiveEntry(id = 72L)
        val repository = FakeUrlRepository().apply {
            archiveEntries.value = listOf(cached)
        }
        val viewModel = ArchiveViewModel(repository)
        advanceUntilIdle()

        assertEquals(ListFilterLoadState.Content, viewModel.uiState.value.loadState)
        assertEquals(listOf(cached.id), viewModel.uiState.value.entries.map { it.id })

        repository.archiveEntriesFlow = flow {
            emit(listOf(cached))
            throw IllegalStateException("test failure")
        }
        viewModel.retryLoading()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadState is ListFilterLoadState.Error)
        assertEquals(listOf(cached.id), viewModel.uiState.value.entries.map { it.id })

        repository.archiveEntriesFlow = flowOf(listOf(recovered))
        viewModel.retryLoading()
        advanceUntilIdle()

        assertEquals(ListFilterLoadState.Content, viewModel.uiState.value.loadState)
        assertEquals(listOf(recovered.id), viewModel.uiState.value.entries.map { it.id })
    }

    private fun archiveEntry(id: Long): UrlEntryEntity {
        return UrlEntryEntity(
            id = id,
            originalUrl = "https://example.com/archive/$id",
            normalizedUrl = "https://example.com/archive/$id",
            displayUrl = "example.com/archive/$id",
            openUrl = "https://example.com/archive/$id",
            normalizedHost = "example.com",
            rawSourceHost = "example.com",
            serviceType = ServiceType.WEB,
            contentContext = ContentContext.STANDARD,
            metadataState = MetadataState.READY,
            recordState = RecordState.ARCHIVED,
            createdAt = id,
            updatedAt = id,
            archivedAt = id,
        )
    }

    private class FakeDisplayModeStore(
        initialMode: EntryCardDisplayMode,
    ) : EntryCardDisplayModeStore {
        private val displayMode = MutableStateFlow(initialMode)

        override fun observeDisplayMode(): Flow<EntryCardDisplayMode> = displayMode

        override suspend fun setDisplayMode(mode: EntryCardDisplayMode) {
            displayMode.value = mode
        }
    }

    private class FakeUrlRepository : UrlRepository {
        val archiveEntries = MutableStateFlow<List<UrlEntryEntity>>(emptyList())
        var archiveEntriesFlow: Flow<List<UrlEntryEntity>> = archiveEntries

        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()

        override suspend fun saveFromManualInput(input: String): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override suspend fun archive(entryId: Long): Boolean = false

        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? = null

        override fun observeArchiveEntries(): Flow<List<UrlEntryEntity>> = archiveEntriesFlow

        override fun observeEntry(entryId: Long): Flow<UrlEntryEntity?> = flowOf(null)

        override suspend fun saveFromIntent(intent: Intent): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override suspend fun unarchive(entryId: Long): Boolean = false

        override suspend fun finalizePendingDelete(entryId: Long) = Unit

        override suspend fun cleanupExpiredPendingDeletes() = Unit

        override suspend fun restore(entryId: Long): Boolean = false

        override suspend fun saveUserTitle(entryId: Long, rawTitle: String): SaveTitleResult {
            return SaveTitleResult(success = false)
        }

        override suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean = false

        override suspend fun saveMemo(entryId: Long, rawMemo: String): SaveMemoResult {
            return SaveMemoResult(success = false)
        }

        override suspend fun applyCanonicalId(entryId: Long, canonicalId: String?) = Unit

        override suspend fun applyMetadataUpdate(entryId: Long, metadata: jp.mimac.urlsaver.data.MetadataUpdate) = Unit

        override suspend fun retryMetadata(entryId: Long): Boolean = false

        override suspend fun loadEntry(entryId: Long): UrlEntryEntity? = null
    }
}
