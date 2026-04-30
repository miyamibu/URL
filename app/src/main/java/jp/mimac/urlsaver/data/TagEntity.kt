package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus

@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["name"]),
        Index(value = ["scope"]),
        Index(value = ["authUserId"]),
        Index(value = ["authUserId", "remoteTagId"], unique = true),
    ],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val scope: SharedTagScope = SharedTagScope.LOCAL_ONLY,
    val authUserId: String? = null,
    val remoteTagId: String? = null,
    val syncStatus: SharedTagSyncStatus = SharedTagSyncStatus.LOCAL_ONLY,
    val remoteVersion: Long? = null,
    val deletedAt: Long? = null,
    val lastSyncedAt: Long? = null,
    val syncErrorMessage: String? = null,
)
