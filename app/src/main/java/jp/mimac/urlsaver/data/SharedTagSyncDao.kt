package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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
