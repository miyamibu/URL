package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import jp.mimac.urlsaver.domain.SharedTagGroupMemberRecord
import jp.mimac.urlsaver.domain.SharedTagGroupRecord
import jp.mimac.urlsaver.domain.SharedTagGroupTagRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedTagSyncDao {
    @Query(
        """
        SELECT * FROM shared_tag_members
        WHERE authUserId = :authUserId
        ORDER BY tagId ASC, userId ASC
        """
    )
    suspend fun getMembersForUser(authUserId: String): List<SharedTagMemberEntity>

    @Query("DELETE FROM shared_tag_members WHERE authUserId = :authUserId")
    suspend fun deleteMembersForUser(authUserId: String)

    @Upsert
    suspend fun upsertMembers(members: List<SharedTagMemberEntity>)

    @Query("DELETE FROM shared_tag_groups WHERE authUserId = :authUserId")
    suspend fun deleteGroupsForUser(authUserId: String)

    @Query("DELETE FROM shared_tag_group_members WHERE authUserId = :authUserId")
    suspend fun deleteGroupMembersForUser(authUserId: String)

    @Query("DELETE FROM shared_tag_group_tags WHERE authUserId = :authUserId")
    suspend fun deleteGroupTagsForUser(authUserId: String)

    @Upsert
    suspend fun upsertGroups(groups: List<SharedTagGroupEntity>)

    @Upsert
    suspend fun upsertGroupMembers(members: List<SharedTagGroupMemberEntity>)

    @Upsert
    suspend fun upsertGroupTags(tags: List<SharedTagGroupTagEntity>)

    @Query(
        """
        SELECT * FROM shared_tag_groups
        WHERE authUserId = :authUserId
          AND remoteGroupId = :remoteGroupId
        LIMIT 1
        """
    )
    suspend fun findGroupByRemoteId(authUserId: String, remoteGroupId: String): SharedTagGroupEntity?

    @Query(
        """
        SELECT * FROM shared_tag_groups
        WHERE authUserId = :authUserId
          AND remoteGroupId = :remoteGroupId
        LIMIT 1
        """
    )
    fun observeGroupByRemoteId(authUserId: String, remoteGroupId: String): Flow<SharedTagGroupEntity?>

    @Query(
        """
        SELECT * FROM shared_tag_groups
        WHERE authUserId = :authUserId
          AND id = :groupId
        LIMIT 1
        """
    )
    suspend fun findLocalGroupById(authUserId: String, groupId: Long): SharedTagGroupEntity?

    @Query(
        """
        SELECT
            id AS id,
            remoteGroupId AS remoteGroupId,
            name AS name,
            currentUserRole AS currentUserRole,
            (
                SELECT COUNT(*)
                FROM shared_tag_group_tags
                WHERE shared_tag_group_tags.groupId = shared_tag_groups.id
                  AND shared_tag_group_tags.authUserId = :authUserId
            ) AS tagCount,
            (
                SELECT COUNT(*)
                FROM shared_tag_group_members
                WHERE shared_tag_group_members.groupId = shared_tag_groups.id
                  AND shared_tag_group_members.authUserId = :authUserId
                  AND shared_tag_group_members.status = 'ACTIVE'
            ) AS memberCount,
            lastSyncedAt AS lastSyncedAt
        FROM shared_tag_groups
        WHERE authUserId = :authUserId
          AND deletedAt IS NULL
        ORDER BY name ASC, id ASC
        """
    )
    fun observeGroups(authUserId: String): Flow<List<SharedTagGroupRecord>>

    @Query(
        """
        SELECT
            groupId AS groupId,
            userId AS userId,
            displayName AS displayName,
            role AS role,
            status AS status,
            CASE WHEN userId = :authUserId THEN 1 ELSE 0 END AS isCurrentUser
        FROM shared_tag_group_members
        WHERE authUserId = :authUserId
          AND groupId = :groupId
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
    fun observeGroupMembers(authUserId: String, groupId: Long): Flow<List<SharedTagGroupMemberRecord>>

    @Query(
        """
        SELECT
            group_tag.groupId AS groupId,
            tag.id AS tagId,
            tag.name AS tagName,
            tag_member.role AS currentUserRole
        FROM shared_tag_group_tags AS group_tag
        INNER JOIN tags AS tag
            ON tag.id = group_tag.tagId
           AND tag.deletedAt IS NULL
        LEFT JOIN shared_tag_members AS tag_member
            ON tag_member.tagId = tag.id
           AND tag_member.authUserId = :authUserId
           AND tag_member.userId = :authUserId
           AND tag_member.status = 'ACTIVE'
        WHERE group_tag.authUserId = :authUserId
          AND group_tag.groupId = :groupId
        ORDER BY tag.name ASC, tag.id ASC
        """
    )
    fun observeGroupTags(authUserId: String, groupId: Long): Flow<List<SharedTagGroupTagRecord>>

    @Query(
        """
        SELECT COUNT(*)
        FROM shared_tag_groups
        WHERE authUserId = :authUserId
          AND deletedAt IS NULL
        """
    )
    suspend fun countGroups(authUserId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM shared_tag_groups
        WHERE authUserId = :authUserId
          AND deletedAt IS NULL
        """
    )
    fun observeGroupCount(authUserId: String): Flow<Int>

    @Query(
        """
        SELECT * FROM shared_tag_sync_outbox
        WHERE authUserId = :authUserId
          AND state = 'PENDING'
        ORDER BY createdAt ASC
        """
    )
    suspend fun getPendingOutbox(authUserId: String): List<SharedTagSyncOutboxEntity>

    @Query(
        """
        SELECT * FROM shared_tag_sync_outbox
        WHERE authUserId = :authUserId
        ORDER BY createdAt ASC
        """
    )
    suspend fun getOutboxForUser(authUserId: String): List<SharedTagSyncOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(entity: SharedTagSyncOutboxEntity)

    @Query(
        """
        UPDATE shared_tag_sync_outbox
        SET state = :state,
            attemptCount = :attemptCount,
            lastErrorMessage = :lastErrorMessage,
            updatedAt = :updatedAt
        WHERE opId = :opId
        """
    )
    suspend fun updateOutboxState(
        opId: String,
        state: SharedTagSyncOutboxState,
        attemptCount: Int,
        lastErrorMessage: String?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE shared_tag_sync_outbox
        SET attemptCount = :attemptCount,
            lastErrorMessage = :lastErrorMessage,
            updatedAt = :updatedAt
        WHERE opId = :opId
          AND state = 'PENDING'
        """
    )
    suspend fun updatePendingOutboxAttempt(
        opId: String,
        attemptCount: Int,
        lastErrorMessage: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM shared_tag_sync_outbox WHERE state = 'COMPLETED' AND authUserId = :authUserId")
    suspend fun deleteCompletedOutbox(authUserId: String)

    @Query("SELECT * FROM shared_tag_sync_state WHERE authUserId = :authUserId LIMIT 1")
    suspend fun findSyncState(authUserId: String): SharedTagSyncStateEntity?

    @Upsert
    suspend fun upsertSyncState(entity: SharedTagSyncStateEntity)

    @Query("SELECT * FROM shared_tag_sync_state WHERE authUserId = :authUserId LIMIT 1")
    fun observeSyncState(authUserId: String): Flow<SharedTagSyncStateEntity?>
}
