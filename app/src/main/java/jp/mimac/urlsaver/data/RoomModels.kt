package jp.mimac.urlsaver.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataBodyKind
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
        Index(value = ["collectionId"]),
        Index(value = ["userLabelId"]),
        Index(value = ["localProvenanceCount", "recordState"]),
        Index(value = ["sharedReferenceCount"]),
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
    val collectionId: Long = DEFAULT_COLLECTION_ID,
    val serviceType: ServiceType,
    val contentContext: ContentContext,
    val userTitle: String? = null,
    val fetchedTitle: String? = null,
    val fetchedAuthorName: String? = null,
    val fetchedBody: String? = null,
    val fetchedBodyKind: MetadataBodyKind? = null,
    val bodySummary: String? = null,
    val description: String? = null,
    val memo: String = "",
    val thumbnailUrl: String? = null,
    val badgeImageUrl: String? = null,
    val canonicalId: String? = null,
    val userLabelId: Long? = null,
    val localProvenanceCount: Int = 1,
    val sharedReferenceCount: Int = 0,
    val metadataState: MetadataState = MetadataState.PENDING,
    val metadataError: MetadataError? = null,
    val metadataRequestedAt: Long? = null,
    val metadataFetchedAt: Long? = null,
    val recordState: RecordState = RecordState.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
    val pendingDeletionUntil: Long? = null,
)

/**
 * Fields needed by list cards and filters. Long metadata bodies are deliberately
 * selected as bounded previews so list observers do not retain the full body.
 */
data class UrlEntryListProjection(
    val id: Long,
    val originalUrl: String,
    val normalizedUrl: String,
    val displayUrl: String,
    val openUrl: String,
    val normalizedHost: String,
    val rawSourceHost: String,
    val collectionId: Long,
    val serviceType: ServiceType,
    val contentContext: ContentContext,
    val userTitle: String?,
    val fetchedTitle: String?,
    val fetchedAuthorName: String?,
    val fetchedBodyPreview: String?,
    val fetchedBodyKind: MetadataBodyKind?,
    val bodySummaryPreview: String?,
    val descriptionPreview: String?,
    val memoPreview: String,
    val thumbnailUrl: String?,
    val badgeImageUrl: String?,
    val canonicalId: String?,
    val userLabelId: Long?,
    val localProvenanceCount: Int,
    val sharedReferenceCount: Int,
    val metadataState: MetadataState,
    val metadataError: MetadataError?,
    val metadataRequestedAt: Long?,
    val metadataFetchedAt: Long?,
    val recordState: RecordState,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val pendingDeletionUntil: Long?,
) {
    fun asListEntity(): UrlEntryEntity = UrlEntryEntity(
        id = id,
        originalUrl = originalUrl,
        normalizedUrl = normalizedUrl,
        displayUrl = displayUrl,
        openUrl = openUrl,
        normalizedHost = normalizedHost,
        rawSourceHost = rawSourceHost,
        collectionId = collectionId,
        serviceType = serviceType,
        contentContext = contentContext,
        userTitle = userTitle,
        fetchedTitle = fetchedTitle,
        fetchedAuthorName = fetchedAuthorName,
        fetchedBody = fetchedBodyPreview,
        fetchedBodyKind = fetchedBodyKind,
        bodySummary = bodySummaryPreview,
        description = descriptionPreview,
        memo = memoPreview,
        thumbnailUrl = thumbnailUrl,
        badgeImageUrl = badgeImageUrl,
        canonicalId = canonicalId,
        userLabelId = userLabelId,
        localProvenanceCount = localProvenanceCount,
        sharedReferenceCount = sharedReferenceCount,
        metadataState = metadataState,
        metadataError = metadataError,
        metadataRequestedAt = metadataRequestedAt,
        metadataFetchedAt = metadataFetchedAt,
        recordState = recordState,
        createdAt = createdAt,
        updatedAt = updatedAt,
        archivedAt = archivedAt,
        pendingDeletionUntil = pendingDeletionUntil,
    )
}

data class EntryVisibleTagRecord(
    val entryId: Long,
    val id: Long,
    val name: String,
    val scope: jp.mimac.urlsaver.domain.SharedTagScope,
    val authUserId: String?,
    val remoteTagId: String?,
    val syncStatus: jp.mimac.urlsaver.domain.SharedTagSyncStatus,
    val currentUserRole: jp.mimac.urlsaver.domain.SharedTagMemberRole?,
) {
    fun asSharedTagRecord() = jp.mimac.urlsaver.domain.SharedTagRecord(
        id = id,
        name = name,
        scope = scope,
        authUserId = authUserId,
        remoteTagId = remoteTagId,
        syncStatus = syncStatus,
        currentUserRole = currentUserRole,
    )
}

const val DEFAULT_COLLECTION_ID = 1L

@Entity(
    tableName = "user_labels",
    indices = [
        Index(value = ["name"], unique = true),
    ],
)
data class UserLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

data class MetadataUpdate(
    val fetchedTitle: String?,
    val fetchedAuthorName: String? = null,
    val fetchedBody: String?,
    val fetchedBodyKind: MetadataBodyKind? = null,
    val bodySummary: String?,
    val description: String? = null,
    val thumbnailUrl: String?,
    val badgeImageUrl: String? = null,
    val metadataState: MetadataState,
    val metadataFetchedAt: Long?,
    val metadataError: MetadataError?,
    val canonicalId: String?,
    val normalizedHost: String?,
    val rawSourceHost: String?,
)

@Entity(
    tableName = "video_assets",
    indices = [
        Index(value = ["entryId"]),
        Index(value = ["provider"]),
        Index(value = ["resolveStatus"]),
        Index(value = ["entryId", "provider", "providerAssetId"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = UrlEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val provider: String,
    val providerAssetId: String,
    val sourceUrl: String,
    val canonicalPostUrl: String?,
    val authorName: String?,
    val title: String?,
    val bodyText: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val mediaType: String,
    val hasVideo: String,
    val resolveStatus: String,
    val downloadUrl: String?,
    val requestHeadersJson: String?,
    val mimeType: String?,
    val qualityLabel: String?,
    val width: Int?,
    val height: Int?,
    val bitrate: Int?,
    val sortIndex: Int,
    val isPreferred: Boolean,
    val checkedAt: Long,
    val expiresAt: Long?,
    val errorReason: String?,
)

@Entity(
    tableName = "video_downloads",
    indices = [
        Index(value = ["entryId"]),
        Index(value = ["videoAssetId"]),
        Index(value = ["status"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = UrlEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VideoAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoAssetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class VideoDownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val videoAssetId: Long,
    val status: String,
    val progress: Int,
    val bytesDownloaded: Long?,
    val totalBytes: Long?,
    val localUri: String?,
    val fileName: String?,
    val startedAt: Long?,
    val savedAt: Long?,
    val errorMessage: String?,
)
