package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.video.AndroidEntryMediaFileStore
import jp.mimac.urlsaver.video.AndroidPendingDeleteMediaCleanup
import jp.mimac.urlsaver.video.AppMediaStore
import jp.mimac.urlsaver.video.EntryWorkCanceller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingDeleteMediaCleanupTest {
    @Test
    fun cleanupDeletesEntryMediaAndTemporaryDownloadAndCancelsKnownWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entryId = 91_001L
        val mediaFile = AppMediaStore.fileFor(context, entryId, "clip.mp4")
        mediaFile.parentFile!!.mkdirs()
        mediaFile.writeBytes(byteArrayOf(1, 2, 3))
        val temporaryFile = mediaFile.resolveSibling("${mediaFile.name}.download")
        temporaryFile.writeBytes(byteArrayOf(4, 5, 6))
        val workCanceller = RecordingEntryWorkCanceller()

        AndroidPendingDeleteMediaCleanup(
            workCanceller = workCanceller,
            fileStore = AndroidEntryMediaFileStore(context),
        ).cleanup(entryId, listOf(31L, 32L))

        assertFalse(mediaFile.exists())
        assertFalse(temporaryFile.exists())
        assertEquals(listOf(RecordingEntryWorkCanceller.Call(entryId, listOf(31L, 32L))), workCanceller.calls)
    }

    private class RecordingEntryWorkCanceller : EntryWorkCanceller {
        data class Call(val entryId: Long, val downloadAssetIds: List<Long>)

        val calls = mutableListOf<Call>()

        override suspend fun cancel(entryId: Long, downloadAssetIds: List<Long>) {
            calls += Call(entryId, downloadAssetIds)
        }
    }
}
