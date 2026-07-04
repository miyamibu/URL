package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import jp.mimac.urlsaver.domain.SharedTagMemberRecord
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagRecord
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.TagWithCount
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    fun observeAllTagsWithCount(): Flow<List<TagWithCount>> = observeVisibleTagsWithCount(null)

    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            COUNT(r.entryId) AS urlCount,
            t.scope AS scope,
            t.authUserId AS authUserId,
            t.remoteTagId AS remoteTagId,
            t.syncStatus AS syncStatus,
            me.role AS currentUserRole
        FROM tags AS t
        LEFT JOIN tag_url_cross_refs AS r
            ON r.tagId = t.id
           AND r.deletedAt IS NULL
        LEFT JOIN shared_tag_members AS me
            ON me.tagId = t.id
           AND me.authUserId = :authUserId
           AND me.userId = :authUserId
           AND me.status = 'ACTIVE'
        WHERE t.deletedAt IS NULL
          AND (
            t.scope = 'LOCAL_ONLY'
            OR (:authUserId IS NOT NULL AND t.scope = 'SYNCED' AND t.authUserId = :authUserId)
          )
        GROUP BY t.id, t.name, t.scope, t.authUserId, t.remoteTagId, t.syncStatus, me.role, t.createdAt
        ORDER BY t.name ASC, t.id ASC
        """
    )
    suspend fun getVisibleTagsWithCount(authUserId: String?): List<TagWithCount>

    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            COUNT(r.entryId) AS urlCount,
            t.scope AS scope,
            t.authUserId AS authUserId,
            t.remoteTagId AS remoteTagId,
            t.syncStatus AS syncStatus,
            me.role AS currentUserRole
        FROM tags AS t
        LEFT JOIN tag_url_cross_refs AS r
            ON r.tagId = t.id
           AND r.deletedAt IS NULL
        LEFT JOIN shared_tag_members AS me
            ON me.tagId = t.id
           AND me.authUserId = :authUserId
           AND me.userId = :authUserId
           AND me.status = 'ACTIVE'
        WHERE t.deletedAt IS NULL
          AND (
            t.scope = 'LOCAL_ONLY'
            OR (:authUserId IS NOT NULL AND t.scope = 'SYNCED' AND t.authUserId = :authUserId)
          )
        GROUP BY t.id, t.name, t.scope, t.authUserId, t.remoteTagId, t.syncStatus, me.role, t.createdAt
        ORDER BY t.name ASC, t.id ASC
        """
    )
    fun observeVisibleTagsWithCount(authUserId: String?): Flow<List<TagWithCount>>

    @Query(
        """
        SELECT
            c.id AS collectionId,
            r.entryId AS entryId
        FROM collections AS c
        INNER JOIN tags AS t
            ON t.name = c.name
           AND t.scope = 'LOCAL_ONLY'
           AND t.deletedAt IS NULL
        INNER JOIN tag_url_cross_refs AS r
            ON r.tagId = t.id
           AND r.deletedAt IS NULL
        WHERE c.id != :defaultCollectionId
        ORDER BY c.sortOrder ASC, c.id ASC, r.entryId ASC
        """
    )
    fun observeLocalTagCollectionEntryRefs(
        defaultCollectionId: Long = DEFAULT_COLLECTION_ID,
    ): Flow<List<LocalTagCollectionEntryRef>>

    @Query(
        """
        SELECT
            r.tagId AS tagId,
            r.entryId AS entryId
        FROM tag_url_cross_refs AS r
        INNER JOIN tags AS t
            ON t.id = r.tagId
           AND t.deletedAt IS NULL
        WHERE r.deletedAt IS NULL
        ORDER BY r.tagId ASC, r.entryId ASC
        """
    )
    fun observeLocalTagEntryRefs(): Flow<List<LocalTagEntryRef>>

    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            t.scope AS scope,
            t.authUserId AS authUserId,
            t.remoteTagId AS remoteTagId,
            t.syncStatus AS syncStatus,
            me.role AS currentUserRole
        FROM tags AS t
        LEFT JOIN shared_tag_members AS me
            ON me.tagId = t.id
           AND me.authUserId = :authUserId
           AND me.userId = :authUserId
           AND me.status = 'ACTIVE'
        WHERE t.id = :tagId
          AND t.deletedAt IS NULL
          AND (
            t.scope = 'LOCAL_ONLY'
            OR (:authUserId IS NOT NULL AND t.scope = 'SYNCED' AND t.authUserId = :authUserId)
          )
        LIMIT 1
        """
    )
    fun observeVisibleTag(tagId: Long, authUserId: String?): Flow<SharedTagRecord?>

    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            t.scope AS scope,
            t.authUserId AS authUserId,
            t.remoteTagId AS remoteTagId,
            t.syncStatus AS syncStatus,
            me.role AS currentUserRole
        FROM tags AS t
        LEFT JOIN shared_tag_members AS me
            ON me.tagId = t.id
           AND me.authUserId = :authUserId
           AND me.userId = :authUserId
           AND me.status = 'ACTIVE'
        WHERE t.remoteTagId = :remoteTagId
          AND t.deletedAt IS NULL
          AND :authUserId IS NOT NULL
          AND t.scope = 'SYNCED'
          AND t.authUserId = :authUserId
        LIMIT 1
        """
    )
    fun observeVisibleTagByRemoteId(remoteTagId: String, authUserId: String?): Flow<SharedTagRecord?>

    fun observeTag(tagId: Long): Flow<SharedTagRecord?> = observeVisibleTag(tagId, null)

    @Query(
        """
        SELECT * FROM tags
        WHERE id = :tagId
        LIMIT 1
        """
    )
    suspend fun findTagById(tagId: Long): TagEntity?

    @Query(
        """
        SELECT * FROM tags
        WHERE deletedAt IS NULL
          AND scope = 'LOCAL_ONLY'
          AND name = :name
        LIMIT 1
        """
    )
    suspend fun findLocalTagByName(name: String): TagEntity?

    @Query(
        """
        SELECT * FROM tags
        WHERE authUserId = :authUserId
          AND remoteTagId = :remoteTagId
        LIMIT 1
        """
    )
    suspend fun findSyncedTagByRemoteId(authUserId: String, remoteTagId: String): TagEntity?

    @Query(
        """
        SELECT * FROM tags
        WHERE deletedAt IS NULL
          AND scope = 'SYNCED'
          AND authUserId = :authUserId
          AND name = :name
        LIMIT 1
        """
    )
    suspend fun findActiveSyncedTagByName(authUserId: String, name: String): TagEntity?

    @Query(
        """
        SELECT * FROM tags
        WHERE authUserId = :authUserId
          AND scope = 'SYNCED'
        ORDER BY id ASC
        """
    )
    suspend fun getSyncedTagsForUser(authUserId: String): List<TagEntity>

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Upsert
    suspend fun upsertTags(tags: List<TagEntity>)

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    @Query(
        """
        SELECT e.*
        FROM url_entries AS e
        INNER JOIN tag_url_cross_refs AS r ON r.entryId = e.id
        INNER JOIN tags AS t ON t.id = r.tagId
        WHERE r.tagId = :tagId
          AND r.deletedAt IS NULL
          AND t.deletedAt IS NULL
          AND (
            t.scope = 'LOCAL_ONLY'
            OR (:authUserId IS NOT NULL AND t.scope = 'SYNCED' AND t.authUserId = :authUserId)
          )
        ORDER BY r.createdAt DESC, e.createdAt DESC
        """
    )
    fun observeEntriesForVisibleTag(tagId: Long, authUserId: String?): Flow<List<UrlEntryEntity>>

    fun observeEntriesForTag(tagId: Long): Flow<List<UrlEntryEntity>> = observeEntriesForVisibleTag(tagId, null)

    @Query(
        """
        SELECT e.*
        FROM url_entries AS e
        INNER JOIN tag_url_cross_refs AS r ON r.entryId = e.id
        WHERE r.tagId = :tagId
          AND r.deletedAt IS NULL
        ORDER BY r.createdAt DESC, e.createdAt DESC
        """
    )
    suspend fun getEntriesForTag(tagId: Long): List<UrlEntryEntity>

    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            t.scope AS scope,
            t.authUserId AS authUserId,
            t.remoteTagId AS remoteTagId,
            t.syncStatus AS syncStatus,
            me.role AS currentUserRole
        FROM tags AS t
        INNER JOIN tag_url_cross_refs AS r ON r.tagId = t.id
        LEFT JOIN shared_tag_members AS me
            ON me.tagId = t.id
           AND me.authUserId = :authUserId
           AND me.userId = :authUserId
           AND me.status = 'ACTIVE'
        WHERE r.entryId = :entryId
          AND r.deletedAt IS NULL
          AND t.deletedAt IS NULL
          AND (
            t.scope = 'LOCAL_ONLY'
            OR (:authUserId IS NOT NULL AND t.scope = 'SYNCED' AND t.authUserId = :authUserId)
          )
        ORDER BY t.name ASC
        """
    )
    fun observeVisibleTagsForEntry(entryId: Long, authUserId: String?): Flow<List<SharedTagRecord>>

    fun observeTagsForEntry(entryId: Long): Flow<List<SharedTagRecord>> = observeVisibleTagsForEntry(entryId, null)

    @Query(
        """
        SELECT
            t.id AS id,
            t.name AS name,
            t.scope AS scope,
            t.authUserId AS authUserId,
            t.remoteTagId AS remoteTagId,
            t.syncStatus AS syncStatus,
            me.role AS currentUserRole
        FROM tags AS t
        INNER JOIN tag_url_cross_refs AS r ON r.tagId = t.id
        LEFT JOIN shared_tag_members AS me
            ON me.tagId = t.id
           AND me.authUserId = :authUserId
           AND me.userId = :authUserId
           AND me.status = 'ACTIVE'
        WHERE r.entryId = :entryId
          AND r.deletedAt IS NULL
          AND t.deletedAt IS NULL
          AND (
            t.scope = 'LOCAL_ONLY'
            OR (:authUserId IS NOT NULL AND t.scope = 'SYNCED' AND t.authUserId = :authUserId)
          )
        ORDER BY t.name ASC
        """
    )
    suspend fun getVisibleTagsForEntry(entryId: Long, authUserId: String?): List<SharedTagRecord>

    @Query(
        """
        SELECT * FROM tag_url_cross_refs
        WHERE tagId = :tagId
          AND entryId = :entryId
        LIMIT 1
        """
    )
    suspend fun findCrossRef(tagId: Long, entryId: Long): TagUrlCrossRef?

    @Query(
        """
        SELECT * FROM tag_url_cross_refs
        WHERE authUserId = :authUserId
          AND remoteUrlId = :remoteUrlId
        LIMIT 1
        """
    )
    suspend fun findSyncedCrossRefByRemoteUrlId(authUserId: String, remoteUrlId: String): TagUrlCrossRef?

    @Query(
        """
        SELECT * FROM tag_url_cross_refs
        WHERE authUserId = :authUserId
          AND tagId = :tagId
          AND normalizedUrl = :normalizedUrl
        LIMIT 1
        """
    )
    suspend fun findSyncedCrossRefByNormalizedUrl(
        authUserId: String,
        tagId: Long,
        normalizedUrl: String,
    ): TagUrlCrossRef?

    @Query(
        """
        SELECT * FROM tag_url_cross_refs
        WHERE authUserId = :authUserId
          AND deletedAt IS NULL
        """
    )
    suspend fun getSyncedCrossRefsForUser(authUserId: String): List<TagUrlCrossRef>

    @Query("DELETE FROM tag_url_cross_refs WHERE authUserId = :authUserId")
    suspend fun deleteSyncedCrossRefsForUser(authUserId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: TagUrlCrossRef): Long

    @Upsert
    suspend fun upsertCrossRefs(refs: List<TagUrlCrossRef>)

    @Query("DELETE FROM tag_url_cross_refs WHERE tagId = :tagId AND entryId = :entryId")
    suspend fun deleteCrossRef(tagId: Long, entryId: Long)

    @Query(
        """
        DELETE FROM tag_url_cross_refs
        WHERE authUserId = :authUserId
          AND tagId NOT IN (:tagIds)
        """
    )
    suspend fun deleteSyncedCrossRefsMissingTags(authUserId: String, tagIds: List<Long>)

    @Query(
        """
        DELETE FROM shared_tag_members
        WHERE authUserId = :authUserId
          AND tagId NOT IN (:tagIds)
        """
    )
    suspend fun deleteSyncedMembersMissingTags(authUserId: String, tagIds: List<Long>)

    @Query(
        """
        UPDATE tags
        SET deletedAt = :deletedAt,
            syncStatus = :syncStatus,
            syncErrorMessage = :syncErrorMessage
        WHERE authUserId = :authUserId
          AND scope = 'SYNCED'
          AND remoteTagId NOT IN (:remoteTagIds)
        """
    )
    suspend fun markMissingSyncedTagsDeleted(
        authUserId: String,
        remoteTagIds: List<String>,
        deletedAt: Long,
        syncStatus: jp.mimac.urlsaver.domain.SharedTagSyncStatus,
        syncErrorMessage: String?,
    )

    @Query(
        """
        UPDATE tags
        SET deletedAt = :deletedAt,
            syncStatus = :syncStatus
        WHERE authUserId = :authUserId
          AND scope = 'SYNCED'
          AND remoteTagId = :remoteTagId
        """
    )
    suspend fun updateSyncedTagDeletion(
        authUserId: String,
        remoteTagId: String,
        deletedAt: Long?,
        syncStatus: jp.mimac.urlsaver.domain.SharedTagSyncStatus,
    )

    @Query(
        """
        UPDATE tag_url_cross_refs
        SET deletedAt = :deletedAt,
            syncStatus = :syncStatus
        WHERE tagId = :tagId
          AND entryId = :entryId
        """
    )
    suspend fun updateCrossRefDeletion(
        tagId: Long,
        entryId: Long,
        deletedAt: Long?,
        syncStatus: jp.mimac.urlsaver.domain.SharedTagSyncStatus,
    )

    @Query(
        """
        SELECT * FROM shared_tag_members
        WHERE tagId = :tagId
          AND authUserId = :authUserId
          AND userId = :authUserId
        LIMIT 1
        """
    )
    suspend fun findCurrentUserMember(tagId: Long, authUserId: String): SharedTagMemberEntity?

    @Query(
        """
        SELECT * FROM shared_tag_members
        WHERE tagId = :tagId
          AND authUserId = :authUserId
          AND userId = :userId
        LIMIT 1
        """
    )
    suspend fun findMember(tagId: Long, authUserId: String, userId: String): SharedTagMemberEntity?

    @Query(
        """
        SELECT
            tagId AS tagId,
            userId AS userId,
            role AS role,
            status AS status,
            CASE WHEN userId = :authUserId THEN 1 ELSE 0 END AS isCurrentUser
        FROM shared_tag_members
        WHERE tagId = :tagId
          AND authUserId = :authUserId
          AND status = 'ACTIVE'
        ORDER BY
            CASE role
                WHEN 'OWNER' THEN 0
                WHEN 'EDITOR' THEN 1
                ELSE 2
            END ASC,
            CASE WHEN userId = :authUserId THEN 0 ELSE 1 END ASC,
            userId ASC
        """
    )
    fun observeActiveMembersForTag(tagId: Long, authUserId: String): Flow<List<SharedTagMemberRecord>>

    @Query(
        """
        SELECT COUNT(*)
        FROM tags
        WHERE scope = 'LOCAL_ONLY'
          AND deletedAt IS NULL
        """
    )
    suspend fun countLocalOnlyTags(): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM tags
        WHERE scope = 'LOCAL_ONLY'
          AND deletedAt IS NULL
        """
    )
    fun observeLocalOnlyTagCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*)
        FROM tags
        WHERE deletedAt IS NULL
          AND :authUserId IS NOT NULL
          AND scope = 'SYNCED'
          AND authUserId = :authUserId
        """
    )
    suspend fun countVisibleSyncedTags(authUserId: String?): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM tags
        WHERE deletedAt IS NULL
          AND :authUserId IS NOT NULL
          AND scope = 'SYNCED'
          AND authUserId = :authUserId
        """
    )
    fun observeVisibleSyncedTagCount(authUserId: String?): Flow<Int>

    @Query(
        """
        SELECT
            t.id AS tagId,
            t.name AS tagName,
            COUNT(r.entryId) AS urlCount
        FROM tags AS t
        LEFT JOIN tag_url_cross_refs AS r
            ON r.tagId = t.id
           AND r.deletedAt IS NULL
        WHERE t.deletedAt IS NULL
          AND :authUserId IS NOT NULL
          AND t.scope = 'SYNCED'
          AND t.authUserId = :authUserId
        GROUP BY t.id, t.name
        ORDER BY t.name ASC, t.id ASC
        """
    )
    suspend fun getVisibleSyncedTagUrlCounts(authUserId: String?): List<SharedTagUrlCountRecord>

    @Query(
        """
        SELECT
            t.id AS tagId,
            t.name AS tagName,
            COUNT(r.entryId) AS urlCount
        FROM tags AS t
        LEFT JOIN tag_url_cross_refs AS r
            ON r.tagId = t.id
           AND r.deletedAt IS NULL
        WHERE t.deletedAt IS NULL
          AND :authUserId IS NOT NULL
          AND t.scope = 'SYNCED'
          AND t.authUserId = :authUserId
        GROUP BY t.id, t.name
        ORDER BY t.name ASC, t.id ASC
        """
    )
    fun observeVisibleSyncedTagUrlCounts(authUserId: String?): Flow<List<SharedTagUrlCountRecord>>
}
