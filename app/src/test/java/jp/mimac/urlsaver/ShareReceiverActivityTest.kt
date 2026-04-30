package jp.mimac.urlsaver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.app.AppContainer
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_CREATED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_DUPLICATE_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_FAILED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_RESTORED_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_BATCH_TOTAL_COUNT
import jp.mimac.urlsaver.data.EXTRA_SHARE_DEGRADATION_NOTICE
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL
import jp.mimac.urlsaver.data.SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class ShareReceiverActivityTest {
    @Before
    fun resetDatabase() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val field = AppContainer::class.java.getDeclaredField("database").apply { isAccessible = true }
        val database = field.get(context.container) as AppDatabase
        runBlocking {
            withContext(Dispatchers.IO) {
                database.clearAllTables()
            }
        }
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
        val activity = controller.get()
        var startedIntent: Intent? = null
        for (attempt in 0 until 20) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            startedIntent = shadowOf(activity).nextStartedActivity
            if (startedIntent != null) {
                break
            }
            if (attempt < 19) {
                Thread.sleep(20)
            }
        }
        val started = startedIntent ?: error("MainActivity intent should be started for single-share truncation.")
        assertTrue(activity.isFinishing)
        assertEquals(ShareSaveResult.CREATED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertEquals(
            SHARE_DEGRADATION_TRUNCATED_TO_FIRST_URL,
            started.getStringExtra(EXTRA_SHARE_DEGRADATION_NOTICE),
        )
    }

    @Test
    fun actionSend_oversizedInput_returnsInputTooLarge() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val embeddedUrl = "https://example.com/oversized-share-should-not-save"
        val oversized = "a".repeat(UrlRules.MAX_INPUT_TEXT_BYTES + 1) + embeddedUrl

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, oversized)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals(ShareSaveResult.INPUT_TOO_LARGE.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        val activeEntries = runBlocking { context.container.repository.observeActiveEntries().first() }
        assertFalse(activeEntries.any { it.normalizedUrl == UrlRules.normalize(embeddedUrl) })
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
        repeat(3) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }

        val activity = controller.get()
        var started: Intent? = null
        for (attempt in 0 until 200) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            started = shadowOf(activity).nextStartedActivity
            if (started != null) {
                break
            }
            if (attempt < 199) {
                Thread.sleep(20)
            }
        }
        assertTrue(started != null)
        val startedIntent = started ?: error("MainActivity intent should be started for batch share.")
        assertEquals(ShareSaveResult.BATCH_PROCESSED.name, startedIntent.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertEquals(2, startedIntent.getIntExtra(EXTRA_SHARE_BATCH_TOTAL_COUNT, -1))
        val created = startedIntent.getIntExtra(EXTRA_SHARE_BATCH_CREATED_COUNT, -1)
        val duplicate = startedIntent.getIntExtra(EXTRA_SHARE_BATCH_DUPLICATE_COUNT, -1)
        val restored = startedIntent.getIntExtra(EXTRA_SHARE_BATCH_RESTORED_COUNT, -1)
        assertEquals(0, startedIntent.getIntExtra(EXTRA_SHARE_BATCH_FAILED_COUNT, -1))
        assertEquals(2, created + duplicate + restored)
    }

    @Test
    fun actionSendMultiple_overMaxUrls_truncatesBatchAndSetsNotice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val payload = ArrayList<String>().apply {
            repeat(UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE + 5) { index ->
                add("https://example.com/multi-cap-$index")
            }
        }

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "text/plain"
            putStringArrayListExtra(Intent.EXTRA_TEXT, payload)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        repeat(3) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }

        val activity = controller.get()
        var startedIntent: Intent? = null
        for (attempt in 0 until 300) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            startedIntent = shadowOf(activity).nextStartedActivity
            if (startedIntent != null) {
                break
            }
            if (attempt < 299) {
                Thread.sleep(20)
            }
        }

        val startedIntentValue = startedIntent ?: error("MainActivity intent should be started for capped batch share.")
        assertEquals(ShareSaveResult.BATCH_PROCESSED.name, startedIntentValue.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertEquals(UrlRules.MAX_BATCH_SAVE_URLS_PER_INTAKE, startedIntentValue.getIntExtra(EXTRA_SHARE_BATCH_TOTAL_COUNT, -1))
        assertEquals(
            SHARE_DEGRADATION_TRUNCATED_TO_MAX_URLS,
            startedIntentValue.getStringExtra(EXTRA_SHARE_DEGRADATION_NOTICE),
        )
    }

    @Test
    fun actionSend_singleExtraStreamUrl_isSaved() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val url = "https://example.com/share-stream-${System.currentTimeMillis()}"
        val normalizedUrl = UrlRules.normalize(url)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/uri-list"
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(url))
        }

        Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        repeat(50) { attempt ->
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (attempt < 49) ShadowLooper.idleMainLooper(20)
        }

        val activeEntries = runBlocking { context.container.repository.observeActiveEntries().first() }
        assertTrue(activeEntries.any { it.normalizedUrl == normalizedUrl })
    }
}
