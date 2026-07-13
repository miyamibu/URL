package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.CreateCollectionResult
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.MainListRepository
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.ui.MainListViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainListViewModelCollectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun createCollection_delegatesToRepository() = runTest {
        val repository = FakeRepository().apply {
            createCollectionResult = CreateCollectionResult(success = true, collectionId = 10L)
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())

        val result = viewModel.createCollection("読む")

        assertEquals(CreateCollectionResult(success = true, collectionId = 10L), result)
        assertEquals(listOf("読む"), repository.createCollectionCalls)
    }

    @Test
    fun deleteCollection_returnsFalse_whenRepositoryRejects() = runTest {
        val repository = FakeRepository().apply {
            deleteCollectionResult = false
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())
        viewModel.selectCollection(21L)

        val deleted = viewModel.deleteCollection(21L)

        assertTrue(!deleted)
        assertEquals(listOf(21L), repository.deleteCollectionCalls)
        assertEquals(21L, viewModel.selectedCollectionIdFlow.value)
    }

    @Test
    fun deleteCollection_clearsSelectedCollectionWhenItMatches() = runTest {
        val repository = FakeRepository().apply {
            deleteCollectionResult = true
        }
        val viewModel = MainListViewModel(repository, FakeDisplayModeStore())
        viewModel.selectCollection(21L)

        val deleted = viewModel.deleteCollection(21L)

        assertTrue(deleted)
        assertEquals(listOf(21L), repository.deleteCollectionCalls)
        assertNull(viewModel.selectedCollectionIdFlow.value)
    }

    private class FakeRepository : MainListRepository {
        val createCollectionCalls = mutableListOf<String>()
        val deleteCollectionCalls = mutableListOf<Long>()
        var createCollectionResult: CreateCollectionResult =
            CreateCollectionResult(success = false, invalidName = true)
        var deleteCollectionResult: Boolean = false

        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()

        override suspend fun saveFromManualInput(input: String): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override suspend fun createCollection(name: String): CreateCollectionResult {
            createCollectionCalls += name
            return createCollectionResult
        }

        override suspend fun deleteCollection(collectionId: Long): Boolean {
            deleteCollectionCalls += collectionId
            return deleteCollectionResult
        }

        override suspend fun archive(entryId: Long): Boolean = false

        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? = null
    }

    private class FakeDisplayModeStore : EntryCardDisplayModeStore {
        private val displayMode = MutableStateFlow(EntryCardDisplayMode.RICH)

        override fun observeDisplayMode(): Flow<EntryCardDisplayMode> = displayMode

        override suspend fun setDisplayMode(mode: EntryCardDisplayMode) {
            displayMode.value = mode
        }
    }
}
