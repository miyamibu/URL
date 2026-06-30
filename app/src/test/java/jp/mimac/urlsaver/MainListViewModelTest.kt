package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.MainListRepository
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.data.LocalTagEntryRef
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.TagWithCount
import jp.mimac.urlsaver.ui.filterEntriesBySearch
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
    fun archiveEntries_returnsOnlySuccessfulIds() = runTest {
        val repository = FakeRepository().apply {
            archiveResultsById[1L] = true
            archiveResultsById[2L] = false
            archiveResultsById[3L] = true
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val archivedIds = viewModel.archiveEntries(listOf(1L, 2L, 3L))

        assertEquals(listOf(1L, 3L), archivedIds)
        assertEquals(listOf(1L, 2L, 3L), repository.archiveCalls)
    }

    @Test
    fun markPendingDeleteEntries_returnsOnlySuccessfulPendingDeletes() = runTest {
        val repository = FakeRepository().apply {
            pendingDeleteResultsById[1L] = 1_001L
            pendingDeleteResultsById[2L] = null
            pendingDeleteResultsById[3L] = 1_003L
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val pendingDeletions = viewModel.markPendingDeleteEntries(listOf(1L, 2L, 3L))

        assertEquals(mapOf(1L to 1_001L, 3L to 1_003L), pendingDeletions)
        assertEquals(listOf(1L, 2L, 3L), repository.pendingDeleteCalls)
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

        assertEquals(ShareSaveResult.CREATED, result.saveResult)
        assertEquals(33L, result.entryId)
        assertEquals(0, result.failedTagAssignmentCount)
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

    @Test
    fun filterEntriesBySearch_matchesOnlyEntryAssignedTagName() {
        val entries = listOf(
            entry(id = 1L, serviceType = ServiceType.WEB, collectionId = 1L),
            entry(id = 2L, serviceType = ServiceType.WEB, collectionId = 1L),
        )

        val result = filterEntriesBySearch(
            entries = entries,
            query = "旅行",
            collections = emptyList(),
            tags = listOf(TagWithCount(id = 10L, name = "旅行", urlCount = 1)),
            localTagEntryRefs = listOf(LocalTagEntryRef(tagId = 10L, entryId = 2L)),
        )

        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun filterEntriesBySearch_matchesOwnCollectionName() {
        val entries = listOf(
            entry(id = 1L, serviceType = ServiceType.WEB, collectionId = 10L),
            entry(id = 2L, serviceType = ServiceType.WEB, collectionId = 11L),
        )

        val result = filterEntriesBySearch(
            entries = entries,
            query = "資料",
            collections = listOf(
                CollectionEntity(id = 10L, name = "仕事資料", sortOrder = 0, createdAt = 0L, updatedAt = 0L),
                CollectionEntity(id = 11L, name = "料理", sortOrder = 1, createdAt = 0L, updatedAt = 0L),
            ),
            tags = emptyList(),
            localTagEntryRefs = emptyList(),
        )

        assertEquals(listOf(1L), result.map { it.id })
    }

    private class FakeRepository : MainListRepository {
        val archiveCalls = mutableListOf<Long>()
        val pendingDeleteCalls = mutableListOf<Long>()
        val manualInputCalls = mutableListOf<Pair<String, Long?>>()
        val activeEntries = MutableStateFlow<List<UrlEntryEntity>>(emptyList())
        val localTagEntryRefs = MutableStateFlow<List<LocalTagEntryRef>>(emptyList())
        val archiveResultsById = mutableMapOf<Long, Boolean>()
        val pendingDeleteResultsById = mutableMapOf<Long, Long?>()
        var archiveResult: Boolean = false
        var pendingDeleteResult: Long? = null
        var manualSaveResult: SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = activeEntries
        override fun observeLocalTagEntryRefs(): Flow<List<LocalTagEntryRef>> = localTagEntryRefs

        override suspend fun saveFromManualInput(input: String): SaveResult = saveFromManualInput(input, collectionId = null)

        override suspend fun saveFromManualInput(input: String, collectionId: Long?): SaveResult {
            manualInputCalls += input to collectionId
            return manualSaveResult
        }

        override suspend fun archive(entryId: Long): Boolean {
            archiveCalls += entryId
            return archiveResultsById[entryId] ?: archiveResult
        }

        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? {
            pendingDeleteCalls += entryId
            return if (pendingDeleteResultsById.containsKey(entryId)) {
                pendingDeleteResultsById[entryId]
            } else {
                pendingDeleteResult
            }
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
