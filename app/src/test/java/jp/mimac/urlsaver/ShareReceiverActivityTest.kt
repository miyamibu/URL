package jp.mimac.urlsaver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_CREATED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_DUPLICATE_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_FAILED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_RESTORED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_TOTAL_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_DEGRADATION_NOTICE
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_NORMALIZED_URL
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class ShareReceiverActivityTest {

    @Test
    fun actionSend_created_putsOnlyRequiredExtrasAndFinishes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val url = "https://example.com/share-created-${System.currentTimeMillis()}"

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(activity.isFinishing)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(ShareSaveResult.CREATED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertFalse(started.hasExtra(EXTRA_SHARE_ENTRY_ID))
        assertFalse(started.hasExtra(EXTRA_SHARE_NORMALIZED_URL))
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            started.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun actionSend_restoredFromPending_requiresEntryIdExtra() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val repository = context.container.repository
        val url = "https://example.com/share-restore-${System.currentTimeMillis()}"
        val created = repository.saveFromManualInput(url)
        val entryId = created.entryId!!
        repository.markPendingDelete(entryId)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(activity.isFinishing)
        assertEquals(ShareSaveResult.RESTORED_FROM_PENDING_DELETE.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertTrue(started.hasExtra(EXTRA_SHARE_ENTRY_ID))
        assertEquals(entryId, started.getLongExtra(EXTRA_SHARE_ENTRY_ID, -1L))
        assertEquals(UrlRules.normalize(url), repository.loadEntry(entryId)?.normalizedUrl)
    }

    @Test
    fun actionSend_duplicateActive_passesEntryIdForOpenExisting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val repository = context.container.repository
        val url = "https://example.com/share-dup-active-${System.currentTimeMillis()}"
        val created = repository.saveFromManualInput(url)
        val entryId = created.entryId!!

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ShareSaveResult.DUPLICATE_ACTIVE.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertTrue(started.hasExtra(EXTRA_SHARE_ENTRY_ID))
        assertEquals(entryId, started.getLongExtra(EXTRA_SHARE_ENTRY_ID, -1L))
    }

    @Test
    fun actionSend_duplicateArchived_passesEntryIdForOpenExisting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val repository = context.container.repository
        val url = "https://example.com/share-dup-archived-${System.currentTimeMillis()}"
        val created = repository.saveFromManualInput(url)
        val entryId = created.entryId!!
        repository.archive(entryId)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ShareSaveResult.DUPLICATE_ARCHIVED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertTrue(started.hasExtra(EXTRA_SHARE_ENTRY_ID))
        assertEquals(entryId, started.getLongExtra(EXTRA_SHARE_ENTRY_ID, -1L))
    }

    @Test
    fun actionSend_multipleUrls_setsTruncationNoticeExtra() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val firstUrl = "https://example.com/share-multi-first-${System.currentTimeMillis()}"
        val secondUrl = "https://example.com/share-multi-second-${System.currentTimeMillis()}"

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$firstUrl\n$secondUrl")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(activity.isFinishing)
        assertEquals(ShareSaveResult.CREATED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertEquals(
            SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL,
            started.getStringExtra(EXTRA_SHARE_DEGRADATION_NOTICE),
        )
    }

    @Test
    fun actionSendMultiple_processesAllUrls_andReturnsBatchSummary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val firstUrl = "https://example.com/send-multiple-first-${System.currentTimeMillis()}"
        val secondUrl = "https://example.com/send-multiple-second-${System.currentTimeMillis()}"

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "text/plain"
            putStringArrayListExtra(Intent.EXTRA_TEXT, arrayListOf(firstUrl, secondUrl))
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(activity.isFinishing)
        assertEquals(ShareSaveResult.BATCH_PROCESSED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertEquals(2, started.getIntExtra(EXTRA_SHARE_BATCH_TOTAL_COUNT, -1))
        assertEquals(2, started.getIntExtra(EXTRA_SHARE_BATCH_CREATED_COUNT, -1))
        assertEquals(0, started.getIntExtra(EXTRA_SHARE_BATCH_DUPLICATE_COUNT, -1))
        assertEquals(0, started.getIntExtra(EXTRA_SHARE_BATCH_RESTORED_COUNT, -1))
        assertEquals(0, started.getIntExtra(EXTRA_SHARE_BATCH_FAILED_COUNT, -1))
    }
}
