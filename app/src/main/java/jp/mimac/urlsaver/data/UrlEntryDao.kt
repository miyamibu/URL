package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.flow.Flow

@Dao
interface UrlEntryDao {
    @Query("SELECT * FROM url_entries WHERE recordState = 'ACTIVE' ORDER BY createdAt DESC")
    fun observeActiveEntries(): Flow<List<UrlEntryEntity>>

    @Query("SELECT * FROM url_entries WHERE recordState = 'ARCHIVED' ORDER BY archivedAt DESC")
    fun observeArchiveEntries(): Flow<List<UrlEntryEntity>>

    @Query("SELECT * FROM url_entries WHERE id = :entryId")
    fun observeEntry(entryId: Long): Flow<UrlEntryEntity?>

    @Query("SELECT * FROM url_entries WHERE id = :entryId")
    suspend fun findById(entryId: Long): UrlEntryEntity?

    @Query("SELECT * FROM url_entries WHERE normalizedUrl = :normalizedUrl LIMIT 1")
    suspend fun findByNormalizedUrl(normalizedUrl: String): UrlEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: UrlEntryEntity): Long

    @Update
    suspend fun update(entry: UrlEntryEntity)

    @Query("DELETE FROM url_entries WHERE id = :entryId")
    suspend fun deleteById(entryId: Long)

    @Query("DELETE FROM url_entries WHERE recordState = 'PENDING_DELETE' AND pendingDeletionUntil IS NOT NULL AND pendingDeletionUntil <= :now")
    suspend fun cleanupExpiredPending(now: Long)

    @Query("SELECT * FROM url_entries WHERE recordState = 'PENDING_DELETE' AND pendingDeletionUntil IS NOT NULL")
    suspend fun findPendingDeleteEntries(): List<UrlEntryEntity>

    @Query(
        """
        UPDATE url_entries
        SET fetchedTitle = :fetchedTitle,
            thumbnailUrl = :thumbnailUrl,
            metadataState = :metadataState,
            metadataFetchedAt = :metadataFetchedAt,
            metadataError = :metadataError,
            canonicalId = :canonicalId,
            normalizedHost = COALESCE(:normalizedHost, normalizedHost),
            rawSourceHost = COALESCE(:rawSourceHost, rawSourceHost)
        WHERE id = :entryId
        """
    )
    suspend fun updateMetadata(
        entryId: Long,
        fetchedTitle: String?,
        thumbnailUrl: String?,
        metadataState: MetadataState,
        metadataFetchedAt: Long?,
        metadataError: MetadataError?,
        canonicalId: String?,
        normalizedHost: String?,
        rawSourceHost: String?,
    )

    @Query(
        """
        UPDATE url_entries
        SET metadataState = 'PENDING',
            metadataError = NULL
        WHERE id = :entryId
        """
    )
    suspend fun markMetadataPending(entryId: Long)

    @Query(
        """
        UPDATE url_entries
        SET canonicalId = :canonicalId
        WHERE id = :entryId
        """
    )
    suspend fun updateCanonicalId(entryId: Long, canonicalId: String?)

    @Query("SELECT COUNT(*) FROM url_entries WHERE serviceType = :serviceType AND recordState = :recordState")
    suspend fun countByService(serviceType: ServiceType, recordState: RecordState): Int

    @Transaction
    suspend fun restoreFromPending(entry: UrlEntryEntity, now: Long): UrlEntryEntity {
        val updated = entry.copy(
            recordState = RecordState.ACTIVE,
            pendingDeletionUntil = null,
            archivedAt = null,
            updatedAt = now,
        )
        update(updated)
        return updated
    }
}
