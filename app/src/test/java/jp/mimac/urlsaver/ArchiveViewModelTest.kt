package jp.mimac.urlsaver

import android.content.Intent
import jp.mimac.urlsaver.data.CollectionEntity
import jp.mimac.urlsaver.data.CreateCollectionResult
import jp.mimac.urlsaver.data.EntryCardDisplayModeStore
import jp.mimac.urlsaver.data.SaveMemoResult
import jp.mimac.urlsaver.data.SaveTitleResult
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.UrlRepository
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.ui.ArchiveViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

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
        override fun observeActiveEntries(): Flow<List<UrlEntryEntity>> = emptyFlow()

        override fun observeCollections(): Flow<List<CollectionEntity>> = emptyFlow()

        override suspend fun saveFromManualInput(input: String): SaveResult = SaveResult(ShareSaveResult.SAVE_FAILED)

        override suspend fun createCollection(name: String): CreateCollectionResult {
            return CreateCollectionResult(success = false, invalidName = true)
        }

        override suspend fun archive(entryId: Long): Boolean = false

        override suspend fun markPendingDelete(entryId: Long, gracePeriodMillis: Long): Long? = null

        override fun observeArchiveEntries(): Flow<List<UrlEntryEntity>> = flowOf(emptyList())

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
