package jp.mimac.urlsaver.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoAssetDao {
    @Query("SELECT * FROM video_assets WHERE entryId = :entryId ORDER BY isPreferred DESC, mediaType ASC, id ASC")
    fun observeAssetsForEntry(entryId: Long): Flow<List<VideoAssetEntity>>

    @Query("SELECT * FROM video_assets WHERE entryId = :entryId AND isPreferred = 1 ORDER BY id ASC LIMIT 1")
    fun observePreferredAssetForEntry(entryId: Long): Flow<VideoAssetEntity?>

    @Query("SELECT * FROM video_assets WHERE entryId IN (:entryIds) ORDER BY entryId ASC, isPreferred DESC, mediaType ASC, id ASC")
    suspend fun loadAssetsForEntries(entryIds: List<Long>): List<VideoAssetEntity>

    @Query("SELECT * FROM video_assets WHERE id = :assetId LIMIT 1")
    suspend fun findById(assetId: Long): VideoAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssets(assets: List<VideoAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: VideoAssetEntity): Long

    @Query("DELETE FROM video_assets WHERE entryId = :entryId")
    suspend fun deleteAssetsForEntry(entryId: Long)
}

@Dao
interface VideoDownloadDao {
    @Query(
        """
        SELECT * FROM video_downloads
        WHERE entryId = :entryId
        ORDER BY
            CASE status
                WHEN 'SAVED' THEN 0
                WHEN 'DOWNLOADING' THEN 1
                WHEN 'QUEUED' THEN 2
                WHEN 'FAILED' THEN 3
                ELSE 4
            END ASC,
            COALESCE(savedAt, startedAt, id) DESC,
            id DESC
        LIMIT 1
        """,
    )
    fun observeLatestDownloadForEntry(entryId: Long): Flow<VideoDownloadEntity?>

    @Query(
        """
        SELECT * FROM video_downloads
        WHERE id IN (
            SELECT MIN(id)
            FROM video_downloads
            WHERE entryId = :entryId
                AND status = 'SAVED'
                AND localUri LIKE 'app-media://media/%'
            GROUP BY localUri
        )
        ORDER BY COALESCE(savedAt, startedAt, id) ASC, id ASC
        """,
    )
    fun observeSavedDownloadsForEntry(entryId: Long): Flow<List<VideoDownloadEntity>>

    @Query("SELECT * FROM video_downloads WHERE entryId IN (:entryIds) ORDER BY entryId ASC, COALESCE(savedAt, startedAt, id) DESC")
    suspend fun loadDownloadsForEntries(entryIds: List<Long>): List<VideoDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDownload(download: VideoDownloadEntity): Long

    @Query(
        """
        UPDATE video_downloads
        SET progress = :progress,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes
        WHERE id = :downloadId
        """,
    )
    suspend fun updateProgress(downloadId: Long, progress: Int, bytesDownloaded: Long?, totalBytes: Long?)

    @Query(
        """
        UPDATE video_downloads
        SET status = 'SAVED',
            progress = 100,
            localUri = :localUri,
            fileName = :fileName,
            savedAt = :savedAt,
            errorMessage = NULL
        WHERE id = :downloadId
        """,
    )
    suspend fun markSaved(downloadId: Long, localUri: String?, fileName: String?, savedAt: Long)

    @Query(
        """
        UPDATE video_downloads
        SET status = 'FAILED',
            errorMessage = :errorMessage
        WHERE id = :downloadId
        """,
    )
    suspend fun markFailed(downloadId: Long, errorMessage: String?)
}
