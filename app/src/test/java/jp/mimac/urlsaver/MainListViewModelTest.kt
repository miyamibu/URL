package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.MainListRepository
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.ui.MainListViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun archiveEntry_delegatesToRepository() = runTest {
        val repository = FakeRepository().apply {
            archiveResult = true
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val result = viewModel.archiveEntry(42L)

        assertTrue(result)
        assertEquals(listOf(42L), repository.archiveCalls)
    }

    @Test
    fun markPendingDelete_delegatesToRepository() = runTest {
        val repository = FakeRepository().apply {
            pendingDeleteResult = 5_000L
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val pendingUntil = viewModel.markPendingDelete(99L)

        assertEquals(5_000L, pendingUntil)
        assertEquals(listOf(99L), repository.pendingDeleteCalls)
    }

    @Test
    fun markPendingDelete_whenRepositoryRejects_returnsNull() = runTest {
        val repository = FakeRepository().apply {
            pendingDeleteResult = null
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val pendingUntil = viewModel.markPendingDelete(7L)

        assertNull(pendingUntil)
        assertEquals(listOf(7L), repository.pendingDeleteCalls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun uiState_filtersBySelectedCollection() = runTest {
        val repository = FakeRepository().apply {
            activeEntries.value = listOf(
                entry(id = 1L, serviceType = ServiceType.WEB, collectionId = 1L),
                entry(id = 2L, serviceType = ServiceType.WEB, collectionId = 10L),
                entry(id = 3L, serviceType = ServiceType.X, collectionId = 11L),
            )
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())
        val job = backgroundScope.launch { viewModel.uiState.collect { } }

        viewModel.selectCollection(1L)
        advanceUntilIdle()
        assertEquals(listOf(1L), viewModel.uiState.value.entries.map { it.id })

        viewModel.selectCollection(10L)
        advanceUntilIdle()
        assertEquals(listOf(2L), viewModel.uiState.value.entries.map { it.id })
        job.cancel()
    }

    @Test
    fun submitManualInput_delegatesToRepositoryWithCollection() = runTest {
        val repository = FakeRepository().apply {
            manualSaveResult = SaveResult(ShareSaveResult.CREATED, entryId = 33L)
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val result = viewModel.submitManualInput(" https://example.com ", collectionId = 12L)

        assertEquals(ShareSaveResult.CREATED to 33L, result)
        assertEquals(listOf(" https://example.com " to 12L), repository.manualInputCalls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleEntryCardDisplayMode_updatesStore() = runTest {
        val store = FakeDisplayModeStore()
        val viewModel = MainListViewModel(FakeRepository(), store)

        viewModel.toggleEntryCardDisplayMode()
        advanceUntilIdle()

        assertEquals(EntryCardDisplayMode.COMPACT, viewModel.entryCardDisplayMode.value)
        assertEquals(listOf(EntryCardDisplayMode.COMPACT), store.setCalls)
    }

    private class FakeRepository : MainListRepository {
        val archiveCalls = mutableListOf<Long>()
        val pendingDeleteCalls = mutableListOf<Long>()
        val manualInputCalls = mutableListOf<Pair<String, Long?>>()
        val activeEntries = MutableStateFlow<List<UrlEntryEntity>>(emptyList())
        var archiveResult: Boolean = false
        var pendingDeleteResult: Long? = null
        var manualSaveResult: SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = activeEntries

        override suspend fun saveFromManualInput(input: String): SaveResult = saveFromManualInput(input, collectionId = null)

        override suspend fun saveFromManualInput(input: String, collectionId: Long?): SaveResult {
            manualInputCalls += input to collectionId
            return manualSaveResult
        }

        override suspend fun archive(entryId: Long): Boolean {
            archiveCalls += entryId
            return archiveResult
        }

        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? {
            pendingDeleteCalls += entryId
            return pendingDeleteResult
        }
    }

    private class FakeDisplayModeStore : EntryCardDisplayModeStore {
        private val displayMode = MutableStateFlow(EntryCardDisplayMode.RICH)
        val setCalls = mutableListOf<EntryCardDisplayMode>()

        override fun observeDisplayMode(): Flow<EntryCardDisplayMode> = displayMode

        override suspend fun setDisplayMode(mode: EntryCardDisplayMode) {
            setCalls += mode
            displayMode.value = mode
        }
    }

    private fun entry(
        id: Long,
        serviceType: ServiceType,
        collectionId: Long,
    ): UrlEntryEntity {
        return UrlEntryEntity(
            id = id,
            originalUrl = "https://example.com/$id",
            normalizedUrl = "https://example.com/$id",
            displayUrl = "example.com/$id",
            openUrl = "https://example.com/$id",
            normalizedHost = "example.com",
            rawSourceHost = "example.com",
            collectionId = collectionId,
            serviceType = serviceType,
            contentContext = ContentContext.STANDARD,
            metadataState = MetadataState.READY,
            recordState = RecordState.ACTIVE,
            createdAt = id,
            updatedAt = id,
        )
    }
}
