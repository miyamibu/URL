package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataError
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType

@Entity(
    tableName = "url_entries",
    indices = [
        Index(value = ["normalizedUrl"], unique = true),
        Index(value = ["recordState"]),
        Index(value = ["serviceType"]),
    ],
)
data class UrlEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUrl: String,
    val normalizedUrl: String,
    val displayUrl: String,
    val openUrl: String,
    val normalizedHost: String,
    val rawSourceHost: String,
    val serviceType: ServiceType,
    val contentContext: ContentContext,
    val userTitle: String? = null,
    val fetchedTitle: String? = null,
    val memo: String = "",
    val thumbnailUrl: String? = null,
    val canonicalId: String? = null,
    val metadataState: MetadataState = MetadataState.PENDING,
    val metadataError: MetadataError? = null,
    val metadataFetchedAt: Long? = null,
    val recordState: RecordState = RecordState.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    val pendingDeletionUntil: Long? = null,
)

data class MetadataUpdate(
    val fetchedTitle: String?,
    val thumbnailUrl: String?,
    val metadataState: MetadataState,
    val metadataFetchedAt: Long?,
    val metadataError: MetadataError?,
    val canonicalId: String?,
    val normalizedHost: String?,
    val rawSourceHost: String?,
)
