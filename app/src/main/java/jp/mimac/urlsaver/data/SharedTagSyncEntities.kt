package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import jp.mimac.urlsaver.domain.SharedTagMemberStatus
import jp.mimac.urlsaver.domain.SharedTagMemberRole
import jp.mimac.urlsaver.domain.SharedTagSyncOperationType

@Entity(
    tableName = "shared_tag_members",
    primaryKeys = ["tagId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["authUserId"]),
        Index(value = ["userId"]),
    ],
)
data class SharedTagMemberEntity(
    val tagId: Long,
    val authUserId: String,
    val userId: String,
    val role: SharedTagMemberRole,
    val status: SharedTagMemberStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "shared_tag_sync_outbox",
    indices = [
        Index(value = ["authUserId", "createdAt"]),
        Index(value = ["authUserId", "state"]),
    ],
)
data class SharedTagSyncOutboxEntity(
    @PrimaryKey val opId: String,
    val clientId: String,
    val authUserId: String,
    val operationType: SharedTagSyncOperationType,
    val payloadJson: String,
    val state: SharedTagSyncOutboxState = SharedTagSyncOutboxState.PENDING,
    val attemptCount: Int = 0,
    val lastErrorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class SharedTagSyncOutboxState {
    PENDING,
    COMPLETED,
    FAILED,
}

@Entity(
    tableName = "shared_tag_sync_state",
)
data class SharedTagSyncStateEntity(
    @PrimaryKey val authUserId: String,
    val clientId: String,
    val lastPulledAt: Long? = null,
    val lastSyncSucceededAt: Long? = null,
    val lastSyncFailedAt: Long? = null,
    val lastErrorMessage: String? = null,
)
