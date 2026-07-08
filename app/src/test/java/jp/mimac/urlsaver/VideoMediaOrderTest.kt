package jp.mimac.urlsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.UrlEntryEntity
import jp.mimac.urlsaver.data.VideoAssetEntity
import jp.mimac.urlsaver.data.VideoDownloadEntity
import jp.mimac.urlsaver.domain.ContentContext
import jp.mimac.urlsaver.domain.MetadataState
import jp.mimac.urlsaver.domain.RecordState
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoMediaOrderTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun videoAssetsObserveInSortIndexOrder() = runBlocking {
        val entryId = insertEntry()
        insertAsset(entryId, providerAssetId = "third-image", mediaType = "IMAGE", sortIndex = 2, preferred = false)
        insertAsset(entryId, providerAssetId = "first-image", mediaType = "IMAGE", sortIndex = 0, preferred = true)
        insertAsset(entryId, providerAssetId = "second-video", mediaType = "VIDEO", sortIndex = 1, preferred = false)

        val assets = db.videoAssetDao().observeAssetsForEntry(entryId).first()

        assertEquals(listOf("first-image", "second-video", "third-image"), assets.map { it.providerAssetId })
    }

    @Test
    fun savedDownloadsObserveInAssetSortIndexOrder() = runBlocking {
        val entryId = insertEntry()
        val thirdId = insertAsset(entryId, providerAssetId = "third-image", mediaType = "IMAGE", sortIndex = 2, preferred = false)
        val firstId = insertAsset(entryId, providerAssetId = "first-image", mediaType = "IMAGE", sortIndex = 0, preferred = true)
        val secondId = insertAsset(entryId, providerAssetId = "second-video", mediaType = "VIDEO", sortIndex = 1, preferred = false)
        insertDownload(entryId, thirdId, "app-media://media/$entryId/third.jpg", savedAt = 100)
        insertDownload(entryId, firstId, "app-media://media/$entryId/first.jpg", savedAt = 300)
        insertDownload(entryId, secondId, "app-media://media/$entryId/second.mp4", savedAt = 200)

        val downloads = db.videoDownloadDao().observeSavedDownloadsForEntry(entryId).first()

        assertEquals(
            listOf("app-media://media/$entryId/first.jpg", "app-media://media/$entryId/second.mp4", "app-media://media/$entryId/third.jpg"),
            downloads.map { it.localUri },
        )
    }

    private suspend fun insertEntry(): Long {
        return db.urlEntryDao().insert(
            UrlEntryEntity(
                originalUrl = "https://www.instagram.com/p/test/",
                normalizedUrl = "https://www.instagram.com/p/test/",
                displayUrl = "instagram.com/p/test",
                openUrl = "https://www.instagram.com/p/test/",
                normalizedHost = "instagram.com",
                rawSourceHost = "www.instagram.com",
                serviceType = ServiceType.INSTAGRAM,
                contentContext = ContentContext.POST,
                metadataState = MetadataState.READY,
                recordState = RecordState.ACTIVE,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private suspend fun insertAsset(
        entryId: Long,
        providerAssetId: String,
        mediaType: String,
        sortIndex: Int,
        preferred: Boolean,
    ): Long {
        return db.videoAssetDao().insertAsset(
            VideoAssetEntity(
                entryId = entryId,
                provider = "instagram",
                providerAssetId = providerAssetId,
                sourceUrl = "https://www.instagram.com/p/test/",
                canonicalPostUrl = "https://www.instagram.com/p/test/",
                authorName = null,
                title = null,
                bodyText = null,
                thumbnailUrl = null,
                durationMs = null,
                mediaType = mediaType,
                hasVideo = "YES",
                resolveStatus = "AVAILABLE",
                downloadUrl = "https://scontent.cdninstagram.com/$providerAssetId",
                requestHeadersJson = null,
                mimeType = if (mediaType == "VIDEO") "video/mp4" else "image/jpeg",
                qualityLabel = null,
                width = null,
                height = null,
                bitrate = null,
                sortIndex = sortIndex,
                isPreferred = preferred,
                checkedAt = 1,
                expiresAt = null,
                errorReason = null,
            ),
        )
    }

    private suspend fun insertDownload(
        entryId: Long,
        assetId: Long,
        localUri: String,
        savedAt: Long,
    ) {
        db.videoDownloadDao().insertOrUpdateDownload(
            VideoDownloadEntity(
                entryId = entryId,
                videoAssetId = assetId,
                status = "SAVED",
                progress = 100,
                bytesDownloaded = 100,
                totalBytes = 100,
                localUri = localUri,
                fileName = localUri.substringAfterLast('/'),
                startedAt = savedAt,
                savedAt = savedAt,
                errorMessage = null,
            ),
        )
    }
}
