package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.video.AppMediaStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppMediaStoreTest {
    @Test
    fun deleteForEntryRemovesOnlyTheEntryDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entryId = 9_876_543_210L
        val otherEntryId = entryId + 1
        val target = AppMediaStore.fileFor(context, entryId, "000_video.mp4")
        val other = AppMediaStore.fileFor(context, otherEntryId, "000_video.mp4")
        target.parentFile!!.mkdirs()
        other.parentFile!!.mkdirs()
        target.writeText("target")
        other.writeText("other")

        try {
            AppMediaStore.deleteForEntry(context, entryId)

            assertFalse(target.parentFile!!.exists())
            assertTrue(other.exists())
        } finally {
            AppMediaStore.deleteForEntry(context, entryId)
            AppMediaStore.deleteForEntry(context, otherEntryId)
        }
    }

    @Test
    fun cleanupOrphanedEntryDirectoriesKeepsValidRowsAndNonEntryFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val validEntryId = 9_876_543_211L
        val orphanEntryId = validEntryId + 1
        val valid = AppMediaStore.fileFor(context, validEntryId, "000_image.jpg")
        val orphan = AppMediaStore.fileFor(context, orphanEntryId, "000_image.jpg")
        valid.parentFile!!.mkdirs()
        orphan.parentFile!!.mkdirs()
        valid.writeText("valid")
        orphan.writeText("orphan")
        val nonEntryDirectory = valid.parentFile!!.parentFile!!.resolve("temporary")
        nonEntryDirectory.mkdirs()
        nonEntryDirectory.resolve("keep.txt").writeText("keep")

        try {
            AppMediaStore.cleanupOrphanedEntryDirectories(context, setOf(validEntryId))

            assertTrue(valid.exists())
            assertFalse(orphan.parentFile!!.exists())
            assertTrue(nonEntryDirectory.exists())
        } finally {
            AppMediaStore.deleteForEntry(context, validEntryId)
            AppMediaStore.deleteForEntry(context, orphanEntryId)
            nonEntryDirectory.deleteRecursively()
        }
    }
}
