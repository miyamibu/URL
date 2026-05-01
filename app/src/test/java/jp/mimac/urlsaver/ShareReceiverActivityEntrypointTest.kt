package jp.mimac.urlsaver

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.app.AppContainer
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_INVALID
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_NORMALIZED_URL
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_TOKEN
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_CREATED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_FAILED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_MERGED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_SKIPPED
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_TAG_IMPORT_TAG_NAME
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
class ShareReceiverActivityEntrypointTest {

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
    fun actionView_validTagDeepLink_routesToMainWithTagIdExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://tag/42")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(activity.isFinishing)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(42L, started.getLongExtra(EXTRA_DEEP_LINK_TAG_ID, Long.MIN_VALUE))
        assertFalse(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_missingTagId_routesToMainSafelyWithInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://tag")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        val started = shadowOf(activity).nextStartedActivity
        assertTrue(activity.isFinishing)
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertFalse(started.hasExtra(EXTRA_DEEP_LINK_TAG_ID))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_nonNumericTagId_routesToMainSafelyWithInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://tag/not-a-number")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_DEEP_LINK_TAG_ID))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_inviteLink_routesToMainWithInviteTokenExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://invite/invite-token-123")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("invite-token-123", started.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionView_httpsInviteLink_routesToMainWithInviteTokenExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://urlsaver.app/invite/invite-token-https-123")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("invite-token-https-123", started.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionView_httpInviteLink_routesToMainWithInviteTokenExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("http://example.test/invite/invite-token-http-123")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("invite-token-http-123", started.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionView_missingInviteToken_routesToMainSafelyWithInviteInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://invite")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertTrue(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionSend_created_finishesAndSavesUrl() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val url = "https://example.com/share-created-${System.currentTimeMillis()}"
        val normalizedUrl = UrlRules.normalize(url)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        val activity = controller.get()
        repeat(50) { attempt ->
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (attempt < 49) ShadowLooper.idleMainLooper(20)
        }

        val activeEntries = runBlocking { context.container.repository.observeActiveEntries().first() }
        assertTrue(activity.isFinishing)
        assertTrue(activeEntries.any { it.normalizedUrl == normalizedUrl })
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
    fun actionSend_validTagJson_usesImportPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val tagName = "共有タグ-${System.currentTimeMillis()}"
        val json = """
            {
              "urlsaver_version": 1,
              "tag": "$tagName",
              "exported_at": 12345,
              "urls": [
                {"url": "https://example.com/imported-${System.currentTimeMillis()}", "title": "imported"},
                {"url": "not-a-url"}
              ]
            }
        """.trimIndent()

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
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
        val started = startedIntent ?: error("MainActivity intent should be started for tag import.")
        assertTrue(activity.isFinishing)
        assertEquals(tagName, started.getStringExtra(EXTRA_TAG_IMPORT_TAG_NAME))
        assertTrue(started.hasExtra(EXTRA_TAG_IMPORT_TAG_ID))
        assertEquals(1, started.getIntExtra(EXTRA_TAG_IMPORT_CREATED, -1))
        assertEquals(0, started.getIntExtra(EXTRA_TAG_IMPORT_MERGED, -1))
        assertEquals(0, started.getIntExtra(EXTRA_TAG_IMPORT_SKIPPED, -1))
        assertEquals(1, started.getIntExtra(EXTRA_TAG_IMPORT_FAILED, -1))
        assertFalse(started.hasExtra(EXTRA_SHARE_SAVE_RESULT))
    }

    @Test
    fun actionSend_malformedJson_fallsBackToExistingUrlFlow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val url = "https://example.com/malformed-json-${System.currentTimeMillis()}"
        val malformed = """{"urlsaver_version":1,"tag":"broken","urls":[{"url":"$url"}]"""

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, malformed)
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
        val started = startedIntent ?: error("MainActivity intent should be started for malformed tag payload.")
        assertEquals(ShareSaveResult.CREATED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertFalse(started.hasExtra(EXTRA_TAG_IMPORT_TAG_ID))
    }

    @Test
    fun actionSend_unsupportedTagJsonVersion_fallsBackWithoutCrash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val url = "https://example.com/unsupported-json-${System.currentTimeMillis()}"
        val unsupported = """
            {
              "urlsaver_version": 2,
              "tag": "future",
              "exported_at": 12345,
              "urls": [{"url": "$url"}]
            }
        """.trimIndent()

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, unsupported)
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
        val started = startedIntent ?: error("MainActivity intent should be started for unsupported tag payload.")
        assertEquals(ShareSaveResult.CREATED.name, started.getStringExtra(EXTRA_SHARE_SAVE_RESULT))
        assertFalse(started.hasExtra(EXTRA_TAG_IMPORT_TAG_ID))
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

    @Test
    fun manifest_allowsActionSendWithoutMimeType() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(Intent.ACTION_SEND).setPackage(context.packageName)

        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            resolved.any { it.activityInfo?.name == ShareReceiverActivity::class.java.name },
        )
    }

    @Test
    fun manifest_allowsSharedTagDeepLinks() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("urlsaver://tag/123")).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            resolved.any { it.activityInfo?.name == ShareReceiverActivity::class.java.name },
        )
    }

    @Test
    fun manifest_allowsSharedTagInviteLinks() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("urlsaver://invite/test-token")).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            resolved.any { it.activityInfo?.name == ShareReceiverActivity::class.java.name },
        )
    }

    @Test
    fun manifest_allowsHttpsSharedTagInviteLinks() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://urlsaver.app/invite/test-token")).apply {
            setPackage(context.packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolved = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            resolved.any { it.activityInfo?.name == ShareReceiverActivity::class.java.name },
        )
    }
}
