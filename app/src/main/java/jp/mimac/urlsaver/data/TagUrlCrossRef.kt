package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import jp.mimac.urlsaver.domain.SharedTagScope
import jp.mimac.urlsaver.domain.SharedTagSyncStatus

@Entity(
    tableName = "tag_url_cross_refs",
    primaryKeys = ["tagId", "entryId"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UrlEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entryId"]),
        Index(value = ["authUserId"]),
        Index(value = ["authUserId", "remoteUrlId"], unique = true),
    ],
)
data class TagUrlCrossRef(
    val tagId: Long,
    val entryId: Long,
    val scope: SharedTagScope = SharedTagScope.LOCAL_ONLY,
    val authUserId: String? = null,
    val remoteUrlId: String? = null,
    val rawUrl: String? = null,
    val normalizedUrl: String? = null,
    val normalizationVersion: Int? = null,
    val syncStatus: SharedTagSyncStatus = SharedTagSyncStatus.LOCAL_ONLY,
    val deletedAt: Long? = null,
    val lastSyncedAt: Long? = null,
    val syncErrorMessage: String? = null,
)
