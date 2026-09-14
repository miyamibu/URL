package jp.mimac.urlsaver

import androidx.lifecycle.SavedStateHandle
import jp.mimac.urlsaver.data.MainListRepository
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
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
import jp.mimac.urlsaver.ui.ListFilterLoadState
import jp.mimac.urlsaver.ui.MainListViewModel
import jp.mimac.urlsaver.ui.ManualInputUiState
import jp.mimac.urlsaver.ui.restoreManualInputUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    fun submitManualInput_delegatesToRepository() = runTest {
        val repository = FakeRepository().apply {
            manualSaveResult = SaveResult(ShareSaveResult.CREATED, entryId = 33L)
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val result = viewModel.submitManualInput(" https://example.com ")

        assertEquals(ShareSaveResult.CREATED, result.saveResult)
        assertEquals(33L, result.entryId)
        assertEquals(0, result.failedTagAssignmentCount)
        assertEquals(listOf(" https://example.com "), repository.manualInputCalls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun manualInput_failedSaveRestoresSheetInputTagsAndErrorFromSavedState() = runTest {
        val savedStateHandle = SavedStateHandle()
        val repository = FakeRepository().apply {
            manualSaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)
        }
        val first = MainListViewModel(
            repository = repository,
            displayModeStore = FakeDisplayModeStore(),
            savedStateHandle = savedStateHandle,
        )

        first.openManualInput()
        first.updateManualInputText("https://example.com/restored-manual")
        first.selectManualInputTag(17L)
        first.selectManualInputTag(23L)
        first.submitCurrentManualInput()
        advanceUntilIdle()

        val restored = MainListViewModel(
            repository = repository,
            displayModeStore = FakeDisplayModeStore(),
            savedStateHandle = savedStateHandle,
        ).manualInputState.value

        assertTrue(restored.visible)
        assertEquals("https://example.com/restored-manual", restored.inputText)
        assertEquals(setOf(17L, 23L), restored.selectedLocalTagIds)
        assertEquals(ShareSaveResult.SAVE_FAILED, restored.inputError)
        assertTrue(!restored.isSaving)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun manualInput_successClearsDurableDraftOnlyAfterRepositorySucceeds() = runTest {
        val savedStateHandle = SavedStateHandle()
        val repository = FakeRepository().apply {
            manualSaveResult = SaveResult(ShareSaveResult.CREATED, entryId = 44L)
        }
        val viewModel = MainListViewModel(
            repository = repository,
            displayModeStore = FakeDisplayModeStore(),
            savedStateHandle = savedStateHandle,
        )

        viewModel.openManualInput()
        viewModel.updateManualInputText("https://example.com/clear-on-success")
        viewModel.selectManualInputTag(31L)
        viewModel.submitCurrentManualInput()
        advanceUntilIdle()

        assertEquals(ManualInputUiState(), viewModel.manualInputState.value)
        assertEquals(ManualInputUiState(), restoreManualInputUiState(savedStateHandle))
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
            tags = listOf(TagWithCount(id = 10L, name = "旅行", urlCount = 1)),
            localTagEntryRefs = listOf(LocalTagEntryRef(tagId = 10L, entryId = 2L)),
        )

        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun filterEntriesBySearch_matchesCanonicalContentFields() {
        val bodyMatch = entry(
            id = 1L,
            serviceType = ServiceType.INSTAGRAM,
            collectionId = 1L,
            fetchedBody = "投稿内容に沖縄旅行の記録があります",
        )
        val memoMatch = entry(
            id = 2L,
            serviceType = ServiceType.WEB,
            collectionId = 1L,
            memo = "あとで精算する",
        )
        val authorMatch = entry(
            id = 3L,
            serviceType = ServiceType.TIKTOK,
            collectionId = 1L,
            fetchedAuthorName = "OpenAI Research",
        )
        val serviceMatch = entry(
            id = 4L,
            serviceType = ServiceType.YOUTUBE,
            collectionId = 1L,
        )
        val entries = listOf(bodyMatch, memoMatch, authorMatch, serviceMatch)

        assertEquals(
            listOf(1L),
            filterEntriesBySearch(entries, "沖縄旅行", emptyList(), emptyList()).map { it.id },
        )
        assertEquals(
            listOf(2L),
            filterEntriesBySearch(entries, "精算", emptyList(), emptyList()).map { it.id },
        )
        assertEquals(
            listOf(3L),
            filterEntriesBySearch(entries, "research", emptyList(), emptyList()).map { it.id },
        )
        assertEquals(
            listOf(4L),
            filterEntriesBySearch(entries, "youtube", emptyList(), emptyList()).map { it.id },
        )
    }

    @Test
    fun filterEntriesBySearch_matchesAssignedVisibleSharedTagName() {
        val entries = listOf(
            entry(id = 1L, serviceType = ServiceType.WEB, collectionId = 1L),
            entry(id = 2L, serviceType = ServiceType.WEB, collectionId = 1L),
        )

        val result = filterEntriesBySearch(
            entries = entries,
            query = "共有旅行",
            tags = listOf(TagWithCount(id = 20L, name = "共有旅行", urlCount = 1)),
            localTagEntryRefs = listOf(LocalTagEntryRef(tagId = 20L, entryId = 2L)),
        )

        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun activeEntries_errorPreservesCachedEntries_andRetryRecovers() = runTest {
        val cached = entry(id = 41L, serviceType = ServiceType.WEB, collectionId = 1L)
        val recovered = entry(id = 42L, serviceType = ServiceType.YOUTUBE, collectionId = 1L)
        val repository = FakeRepository().apply {
            activeEntries.value = listOf(cached)
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())
        advanceUntilIdle()

        assertEquals(ListFilterLoadState.Content, viewModel.uiState.value.loadState)
        assertEquals(listOf(cached.id), viewModel.uiState.value.entries.map { it.id })

        repository.activeEntriesFlow = flow {
            emit(listOf(cached))
            throw IllegalStateException("test failure")
        }
        viewModel.retryLoading()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loadState is ListFilterLoadState.Error)
        assertEquals(listOf(cached.id), viewModel.uiState.value.entries.map { it.id })

        repository.activeEntriesFlow = flowOf(listOf(recovered))
        viewModel.retryLoading()
        advanceUntilIdle()

        assertEquals(ListFilterLoadState.Content, viewModel.uiState.value.loadState)
        assertEquals(listOf(recovered.id), viewModel.uiState.value.entries.map { it.id })
    }

    private class FakeRepository : MainListRepository {
        val archiveCalls = mutableListOf<Long>()
        val pendingDeleteCalls = mutableListOf<Long>()
        val manualInputCalls = mutableListOf<String>()
        val activeEntries = MutableStateFlow<List<UrlEntryEntity>>(emptyList())
        var activeEntriesFlow: Flow<List<UrlEntryEntity>> = activeEntries
        val localTagEntryRefs = MutableStateFlow<List<LocalTagEntryRef>>(emptyList())
        val archiveResultsById = mutableMapOf<Long, Boolean>()
        val pendingDeleteResultsById = mutableMapOf<Long, Long?>()
        var archiveResult: Boolean = false
        var pendingDeleteResult: Long? = null
        var manualSaveResult: SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = activeEntriesFlow
        override fun observeLocalTagEntryRefs(): Flow<List<LocalTagEntryRef>> = localTagEntryRefs

        override suspend fun saveFromManualInput(input: String): SaveResult {
            manualInputCalls += input
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
        fetchedAuthorName: String? = null,
        fetchedBody: String? = null,
        bodySummary: String? = null,
        description: String? = null,
        memo: String = "",
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
            fetchedAuthorName = fetchedAuthorName,
            fetchedBody = fetchedBody,
            bodySummary = bodySummary,
            description = description,
            memo = memo,
            metadataState = MetadataState.READY,
            recordState = RecordState.ACTIVE,
            createdAt = id,
            updatedAt = id,
        )
    }
}
