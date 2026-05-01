package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.MetadataBodyKind
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.flow.Flow

@Dao
interface UrlEntryDao {
    @Query("SELECT * FROM url_entries ORDER BY createdAt DESC")
    suspend fun loadAllEntries(): List<UrlEntryEntity>

    @Query("SELECT * FROM url_entries WHERE localProvenanceCount > 0 AND recordState = 'ACTIVE' ORDER BY createdAt DESC")
    fun observeActiveEntries(): Flow<List<UrlEntryEntity>>

    @Query("SELECT * FROM url_entries WHERE localProvenanceCount > 0 AND recordState = 'ARCHIVED' ORDER BY archivedAt DESC")
    fun observeArchiveEntries(): Flow<List<UrlEntryEntity>>

    @Query("SELECT * FROM url_entries WHERE id = :entryId")
    fun observeEntry(entryId: Long): Flow<UrlEntryEntity?>

    @Query("SELECT * FROM url_entries WHERE id = :entryId")
    suspend fun findById(entryId: Long): UrlEntryEntity?

    @Query("SELECT * FROM url_entries WHERE normalizedUrl = :normalizedUrl LIMIT 1")
    suspend fun findByNormalizedUrl(normalizedUrl: String): UrlEntryEntity?

    @Query(
        """
        SELECT COUNT(*)
        FROM url_entries
        WHERE localProvenanceCount > 0
          AND recordState IN ('ACTIVE', 'ARCHIVED')
        """
    )
    suspend fun countPersonalSavedEntries(): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM url_entries
        WHERE localProvenanceCount > 0
          AND recordState IN ('ACTIVE', 'ARCHIVED')
        """
    )
    fun observePersonalSavedEntriesCount(): Flow<Int>

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
            fetchedAuthorName = :fetchedAuthorName,
            fetchedBody = :fetchedBody,
            fetchedBodyKind = :fetchedBodyKind,
            bodySummary = :bodySummary,
            description = :description,
            thumbnailUrl = :thumbnailUrl,
            badgeImageUrl = :badgeImageUrl,
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
        fetchedAuthorName: String?,
        fetchedBody: String?,
        fetchedBodyKind: MetadataBodyKind?,
        bodySummary: String?,
        description: String?,
        thumbnailUrl: String?,
        badgeImageUrl: String?,
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
        SET userLabelId = :userLabelId
        WHERE id = :entryId
        """
    )
    suspend fun updateUserLabel(entryId: Long, userLabelId: Long?)

    @Query(
        """
        UPDATE url_entries
        SET userLabelId = NULL
        WHERE userLabelId = :labelId
        """
    )
    suspend fun clearUserLabel(labelId: Long)

    @Query(
        """
        UPDATE url_entries
        SET collectionId = :targetCollectionId
        WHERE collectionId = :sourceCollectionId
        """
    )
    suspend fun moveCollectionEntries(sourceCollectionId: Long, targetCollectionId: Long)

    @Query(
        """
        UPDATE url_entries
        SET collectionId = :collectionId,
            updatedAt = :updatedAt
        WHERE id = :entryId
        """
    )
    suspend fun updateCollection(entryId: Long, collectionId: Long, updatedAt: Long)

    @Query(
        """
        UPDATE url_entries
        SET metadataState = 'PENDING',
            metadataError = NULL,
            metadataRequestedAt = :requestedAt
        WHERE id = :entryId
        """
    )
    suspend fun markMetadataPending(entryId: Long, requestedAt: Long)

    @Query(
        """
        SELECT id
        FROM url_entries
        WHERE localProvenanceCount > 0
          AND recordState != 'PENDING_DELETE'
          AND serviceType = 'YOUTUBE'
          AND (fetchedAuthorName IS NULL OR TRIM(fetchedAuthorName) = '')
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun findYouTubeEntriesMissingAuthorName(limit: Int): List<Long>

    @Query(
        """
        UPDATE url_entries
        SET canonicalId = :canonicalId
        WHERE id = :entryId
        """
    )
    suspend fun updateCanonicalId(entryId: Long, canonicalId: String?)

    @Query("SELECT COUNT(*) FROM url_entries WHERE localProvenanceCount > 0 AND serviceType = :serviceType AND recordState = :recordState")
    suspend fun countByService(serviceType: ServiceType, recordState: RecordState): Int

    @Query(
        """
        UPDATE url_entries
        SET sharedReferenceCount = 0
        WHERE sharedReferenceCount != 0
        """
    )
    suspend fun resetSharedReferenceCounts()

    @Query(
        """
        UPDATE url_entries
        SET sharedReferenceCount = :count
        WHERE id = :entryId
        """
    )
    suspend fun updateSharedReferenceCount(entryId: Long, count: Int)

    @Query("DELETE FROM url_entries WHERE localProvenanceCount = 0 AND sharedReferenceCount = 0")
    suspend fun deleteUnreferencedSharedOnlyEntries()

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
