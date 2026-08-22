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

    @Query(
        """
        SELECT
            id, originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
            collectionId, serviceType, contentContext, userTitle, fetchedTitle, fetchedAuthorName,
            SUBSTR(fetchedBody, 1, 512) AS fetchedBodyPreview, fetchedBodyKind,
            SUBSTR(bodySummary, 1, 512) AS bodySummaryPreview,
            SUBSTR(description, 1, 512) AS descriptionPreview,
            SUBSTR(memo, 1, 512) AS memoPreview,
            thumbnailUrl, badgeImageUrl, canonicalId, userLabelId,
            localProvenanceCount, sharedReferenceCount, metadataState, metadataError,
            metadataRequestedAt, metadataFetchedAt, recordState, createdAt, updatedAt,
            archivedAt, pendingDeletionUntil
        FROM url_entries
        WHERE localProvenanceCount > 0 AND recordState = 'ACTIVE'
        ORDER BY createdAt DESC
        """
    )
    fun observeActiveEntries(): Flow<List<UrlEntryListProjection>>

    @Query(
        """
        SELECT
            id, originalUrl, normalizedUrl, displayUrl, openUrl, normalizedHost, rawSourceHost,
            collectionId, serviceType, contentContext, userTitle, fetchedTitle, fetchedAuthorName,
            SUBSTR(fetchedBody, 1, 512) AS fetchedBodyPreview, fetchedBodyKind,
            SUBSTR(bodySummary, 1, 512) AS bodySummaryPreview,
            SUBSTR(description, 1, 512) AS descriptionPreview,
            SUBSTR(memo, 1, 512) AS memoPreview,
            thumbnailUrl, badgeImageUrl, canonicalId, userLabelId,
            localProvenanceCount, sharedReferenceCount, metadataState, metadataError,
            metadataRequestedAt, metadataFetchedAt, recordState, createdAt, updatedAt,
            archivedAt, pendingDeletionUntil
        FROM url_entries
        WHERE localProvenanceCount > 0 AND recordState = 'ARCHIVED'
        ORDER BY archivedAt DESC
        """
    )
    fun observeArchiveEntries(): Flow<List<UrlEntryListProjection>>

    @Query(
        """
        SELECT id
        FROM url_entries
        WHERE localProvenanceCount > 0
          AND recordState = :recordState
          AND (
            LOWER(COALESCE(originalUrl, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(normalizedUrl, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(displayUrl, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(openUrl, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(normalizedHost, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(userTitle, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(fetchedTitle, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(fetchedAuthorName, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(fetchedBody, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(bodySummary, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(description, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(memo, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
            OR LOWER(COALESCE(serviceType, '')) LIKE '%' || LOWER(:query) || '%' ESCAPE '\'
          )
        """
    )
    suspend fun searchEntryIds(query: String, recordState: RecordState): List<Long>

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

    @Query(
        """
        SELECT id
        FROM url_entries
        WHERE id IN (:entryIds)
          AND recordState IN (:recordStates)
        """
    )
    suspend fun findIdsInStates(
        entryIds: List<Long>,
        recordStates: List<RecordState>,
    ): List<Long>

    @Query(
        """
        UPDATE url_entries
        SET recordState = 'ARCHIVED',
            archivedAt = :archivedAt,
            pendingDeletionUntil = NULL,
            updatedAt = :updatedAt
        WHERE id IN (:entryIds)
          AND recordState = 'ACTIVE'
        """
    )
    suspend fun archiveEntries(
        entryIds: List<Long>,
        archivedAt: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE url_entries
        SET recordState = 'PENDING_DELETE',
            pendingDeletionUntil = :pendingUntil,
            archivedAt = CASE
                WHEN recordState = 'ARCHIVED' THEN COALESCE(archivedAt, :updatedAt)
                ELSE NULL
            END,
            updatedAt = :updatedAt
        WHERE id IN (:entryIds)
          AND recordState IN ('ACTIVE', 'ARCHIVED')
        """
    )
    suspend fun markPendingDeleteEntries(
        entryIds: List<Long>,
        pendingUntil: Long?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE url_entries
        SET recordState = CASE WHEN archivedAt IS NULL THEN 'ACTIVE' ELSE 'ARCHIVED' END,
            pendingDeletionUntil = NULL,
            updatedAt = :updatedAt
        WHERE recordState = 'PENDING_DELETE'
          AND pendingDeletionUntil IS NULL
        """
    )
    suspend fun restoreProvisionalPendingDeletes(updatedAt: Long)

    @Query(
        """
        UPDATE url_entries
        SET pendingDeletionUntil = :pendingUntil,
            updatedAt = :updatedAt
        WHERE id IN (:entryIds)
          AND recordState = 'PENDING_DELETE'
        """
    )
    suspend fun updatePendingDeleteDeadlines(
        entryIds: List<Long>,
        pendingUntil: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE url_entries
        SET recordState = CASE WHEN archivedAt IS NULL THEN 'ACTIVE' ELSE 'ARCHIVED' END,
            pendingDeletionUntil = NULL,
            updatedAt = :updatedAt
        WHERE id = :entryId
          AND recordState = 'PENDING_DELETE'
        """
    )
    suspend fun restorePendingDelete(entryId: Long, updatedAt: Long): Int

    @Query(
        """
        UPDATE url_entries
        SET recordState = 'ACTIVE',
            pendingDeletionUntil = NULL,
            archivedAt = NULL,
            updatedAt = :updatedAt
        WHERE id = :entryId
          AND recordState = 'ARCHIVED'
        """
    )
    suspend fun restoreArchivedEntry(entryId: Long, updatedAt: Long): Int

    @Query("DELETE FROM url_entries WHERE id = :entryId")
    suspend fun deleteById(entryId: Long)

    @Query(
        """
        DELETE FROM url_entries
        WHERE id = :entryId
          AND recordState = 'PENDING_DELETE'
          AND pendingDeletionUntil IS NOT NULL
          AND pendingDeletionUntil <= :now
        """
    )
    suspend fun deleteExpiredPendingById(entryId: Long, now: Long): Int

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
        SET sharedReferenceCount = (
            SELECT COUNT(*)
            FROM tag_url_cross_refs AS ref
            INNER JOIN tags AS tag ON tag.id = ref.tagId
            WHERE ref.entryId = url_entries.id
              AND ref.scope = 'SYNCED'
              AND ref.deletedAt IS NULL
              AND tag.scope = 'SYNCED'
              AND tag.deletedAt IS NULL
        )
        """
    )
    suspend fun recomputeSharedReferenceCounts()

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
        val restoreAsArchived = entry.archivedAt != null
        val updated = entry.copy(
            recordState = if (restoreAsArchived) RecordState.ARCHIVED else RecordState.ACTIVE,
            pendingDeletionUntil = null,
            archivedAt = if (restoreAsArchived) entry.archivedAt else null,
            updatedAt = now,
        )
        update(updated)
        return updated
    }
}
