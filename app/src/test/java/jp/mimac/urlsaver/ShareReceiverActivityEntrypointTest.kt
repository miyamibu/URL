package jp.mimac.urlsaver

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.app.AppContainer
import jp.mimac.urlsaver.data.AppDatabase
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_INVALID
import jp.mimac.urlsaver.data.EXTRA_DEEP_LINK_TAG_ID
import jp.mimac.urlsaver.data.EXTRA_PROMO_CODE
import jp.mimac.urlsaver.data.EXTRA_PROMO_CODE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARE_ENTRY_ID
import jp.mimac.urlsaver.data.EXTRA_SHARE_NORMALIZED_URL
import jp.mimac.urlsaver.data.EXTRA_SHARE_SAVE_RESULT
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_INVALID
import jp.mimac.urlsaver.data.EXTRA_SHARED_TAG_INVITE_TOKEN
import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.domain.UrlRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            data = Uri.parse("https://miyamibu.xyz/invite/invite-token-https-123")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("invite-token-https-123", started.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionView_httpInviteLink_isRejected() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("http://example.test/invite/invite-token-http-123")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
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
    fun actionView_promoLink_routesToMainWithPromoCodeExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://promo?code=RNBM%20TEST%20CODE%201234")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("RNBM TEST CODE 1234", started.getStringExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
    }

    @Test
    fun actionView_httpsPromoFragment_routesToMainWithPromoCodeExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/promo#code=RNBM%20TEST%20CODE%205678")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("RNBM TEST CODE 5678", started.getStringExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
    }

    @Test
    fun actionView_missingPromoCode_routesToMainSafelyWithPromoInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("urlsaver://promo")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_PROMO_CODE))
        assertTrue(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
    }

    @Test
    fun actionSend_created_showsTagSelectionBeforeSaving() {
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
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activeEntries.any { it.normalizedUrl == normalizedUrl })
    }

    @Test
    fun actionSend_restoredFromPending_waitsForUserConfirmation() = runBlocking {
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
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(UrlRules.normalize(url), repository.loadEntry(entryId)?.normalizedUrl)
    }

    @Test
    fun actionSend_duplicateActive_waitsForUserConfirmation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val repository = context.container.repository
        val url = "https://example.com/share-dup-active-${System.currentTimeMillis()}"
        repository.saveFromManualInput(url)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun actionSend_duplicateArchived_waitsForUserConfirmation() = runBlocking {
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
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun actionSend_validTagJson_waitsForImportConfirmation() = runBlocking {
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
        for (attempt in 0 until 20) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (attempt < 19) {
                Thread.sleep(20)
            }
        }
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(context.container.tagRepository.findLocalTagIdByName(tagName))
    }

    @Test
    fun actionSend_malformedJson_fallsBackToTagSelectionFlow() = runBlocking {
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
        for (attempt in 0 until 20) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (attempt < 19) {
                Thread.sleep(20)
            }
        }
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        val activeEntries = context.container.repository.observeActiveEntries().first()
        assertFalse(activeEntries.any { it.normalizedUrl == UrlRules.normalize(url) })
    }

    @Test
    fun actionSend_unsupportedTagJsonVersion_fallsBackToTagSelectionFlow() = runBlocking {
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
        for (attempt in 0 until 20) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (attempt < 19) {
                Thread.sleep(20)
            }
        }
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        val activeEntries = context.container.repository.observeActiveEntries().first()
        assertFalse(activeEntries.any { it.normalizedUrl == UrlRules.normalize(url) })
    }

    @Test
    fun actionSend_singleExtraStreamUrl_showsTagSelectionBeforeSaving() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val url = "https://example.com/share-stream-${System.currentTimeMillis()}"
        val normalizedUrl = UrlRules.normalize(url)

        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/uri-list"
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(url))
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        repeat(50) { attempt ->
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            if (attempt < 49) ShadowLooper.idleMainLooper(20)
        }

        val activeEntries = runBlocking { context.container.repository.observeActiveEntries().first() }
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activeEntries.any { it.normalizedUrl == normalizedUrl })
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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://miyamibu.xyz/invite/test-token")).apply {
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
