package jp.mimac.urlsaver.data

import android.content.Intent
import jp.mimac.urlsaver.domain.SaveResult
import jp.mimac.urlsaver.domain.RecordState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

const val PENDING_DELETE_STAGING_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
const val PENDING_DELETE_UNDO_WINDOW_MILLIS = 5_000L

interface MainListRepository {
    fun observeActiveEntries(): Flow<List<UrlEntryEntity>>
    fun observeLocalTagEntryRefs(): Flow<List<LocalTagEntryRef>> = flowOf(emptyList())

    suspend fun searchEntryIds(query: String, recordState: RecordState): Set<Long> = emptySet()

    suspend fun saveFromManualInput(input: String): SaveResult
    suspend fun saveFromManualInput(
        input: String,
        initialMemo: String?,
    ): SaveResult = saveFromManualInput(input)

    suspend fun archive(entryId: Long): Boolean
    suspend fun archiveEntries(entryIds: Collection<Long>): Set<Long> = buildSet {
        entryIds.distinct().forEach { entryId ->
            if (archive(entryId)) add(entryId)
        }
    }
    suspend fun markPendingDelete(
        entryId: Long,
        gracePeriodMillis: Long = PENDING_DELETE_STAGING_WINDOW_MILLIS,
    ): Long?
    suspend fun markPendingDeleteEntries(
        entryIds: Collection<Long>,
        gracePeriodMillis: Long = PENDING_DELETE_STAGING_WINDOW_MILLIS,
    ): Map<Long, Long> = buildMap {
        entryIds.distinct().forEach { entryId ->
            markPendingDelete(entryId, gracePeriodMillis)?.let { pendingUntil ->
                put(entryId, pendingUntil)
            }
        }
    }
}

interface UrlRepository : MainListRepository {
    fun observeArchiveEntries(): Flow<List<UrlEntryEntity>>
    fun observeEntry(entryId: Long): Flow<UrlEntryEntity?>

    suspend fun saveFromIntent(intent: Intent): SaveResult

    suspend fun unarchive(entryId: Long): Boolean
    suspend fun finalizePendingDelete(entryId: Long)
    suspend fun cleanupExpiredPendingDeletes()
    suspend fun restoreProvisionalPendingDeletes() = Unit
    suspend fun startPendingDeleteUndoWindow(
        entryIds: Collection<Long>,
        gracePeriodMillis: Long = PENDING_DELETE_UNDO_WINDOW_MILLIS,
    ): Map<Long, Long> = emptyMap()
    suspend fun restore(entryId: Long): Boolean

    suspend fun saveUserTitle(entryId: Long, rawTitle: String): SaveTitleResult
    suspend fun restoreUserTitle(entryId: Long, oldTitle: String?): Boolean
    suspend fun saveMemo(entryId: Long, rawMemo: String): SaveMemoResult

    suspend fun applyCanonicalId(entryId: Long, canonicalId: String?)
    suspend fun applyMetadataUpdate(entryId: Long, metadata: MetadataUpdate)
    suspend fun retryMetadata(entryId: Long): Boolean
    suspend fun refreshMetadata(entryId: Long): Boolean = retryMetadata(entryId)
    suspend fun backfillYouTubeAuthorNames(limit: Int = 50): Int = 0

    suspend fun loadEntry(entryId: Long): UrlEntryEntity?
}

data class SaveTitleResult(
    val success: Boolean,
    val oldTitle: String? = null,
    val newTitle: String? = null,
    val tooLong: Boolean = false,
)

data class SaveMemoResult(
    val success: Boolean,
    val tooLong: Boolean = false,
)
