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
    val displayName: String? = null,
    val role: SharedTagMemberRole,
    val status: SharedTagMemberStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "shared_tag_groups",
    indices = [
        Index(value = ["authUserId"]),
        Index(value = ["authUserId", "remoteGroupId"], unique = true),
    ],
)
data class SharedTagGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authUserId: String,
    val remoteGroupId: String,
    val name: String,
    val currentUserRole: SharedTagMemberRole?,
    val deletedAt: Long?,
    val lastSyncedAt: Long?,
)

@Entity(
    tableName = "shared_tag_group_members",
    primaryKeys = ["groupId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = SharedTagGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["authUserId"]),
        Index(value = ["userId"]),
    ],
)
data class SharedTagGroupMemberEntity(
    val groupId: Long,
    val authUserId: String,
    val userId: String,
    val displayName: String? = null,
    val role: SharedTagMemberRole,
    val status: SharedTagMemberStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "shared_tag_group_tags",
    primaryKeys = ["groupId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = SharedTagGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["authUserId"]),
        Index(value = ["tagId"]),
    ],
)
data class SharedTagGroupTagEntity(
    val groupId: Long,
    val tagId: Long,
    val authUserId: String,
    val remoteGroupId: String,
    val remoteTagId: String,
    val addedBy: String,
    val createdAt: Long,
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
