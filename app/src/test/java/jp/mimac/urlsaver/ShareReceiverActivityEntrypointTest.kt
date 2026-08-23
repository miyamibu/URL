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
    fun actionView_productionSitesInviteLink_routesToMainWithInviteTokenExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://rinbam-app.miyamibu.chatgpt.site/invite/production-token")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("production-token", started.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionView_httpInviteLink_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("http://example.test/invite/invite-token-http-123")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsInviteLink_hostComparisonIsCaseInsensitive() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://MIYAMIBU.XYZ/invite/invite-token-uppercase-host")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("invite-token-uppercase-host", started.getStringExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
    }

    @Test
    fun actionView_httpsForeignInviteLink_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://example.test/invite/foreign-token")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsSubdomainInviteLink_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://sub.miyamibu.xyz/invite/subdomain-token")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsTrailingDotInviteLink_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz./invite/trailing-dot-token")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpCanonicalInviteLink_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("http://miyamibu.xyz/invite/http-token")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun canonicalHttpsBaseConfiguration_rejectsMalformedBaseUrl() {
        val uri = Uri.parse("https://miyamibu.xyz/invite/configuration-token")

        assertFalse(
            ShareReceiverEntrypointRouter.isCanonicalHttpsUri(
                uri,
                baseUrl = "https://[invalid",
            )
        )
    }

    @Test
    fun canonicalHttpsBaseConfiguration_rejectsHttpBaseUrl() {
        val uri = Uri.parse("https://miyamibu.xyz/invite/configuration-token")

        assertFalse(
            ShareReceiverEntrypointRouter.isCanonicalHttpsUri(
                uri,
                baseUrl = "http://miyamibu.xyz",
            )
        )
    }

    @Test
    fun canonicalHttpsBaseConfiguration_rejectsHostlessBaseUrl() {
        val uri = Uri.parse("https://miyamibu.xyz/invite/configuration-token")

        assertFalse(
            ShareReceiverEntrypointRouter.isCanonicalHttpsUri(
                uri,
                baseUrl = "https:///invite",
            )
        )
    }

    @Test
    fun canonicalHttpsBaseConfiguration_rejectsEmptyBaseUrl() {
        val uri = Uri.parse("https://miyamibu.xyz/invite/configuration-token")

        assertFalse(
            ShareReceiverEntrypointRouter.isCanonicalHttpsUri(
                uri,
                baseUrl = "",
            )
        )
    }

    @Test
    fun canonicalHttpsBaseConfiguration_rejectsTerminalDotHost() {
        val uri = Uri.parse("https://miyamibu.xyz/invite/configuration-token")

        assertFalse(
            ShareReceiverEntrypointRouter.isCanonicalHttpsUri(
                uri,
                baseUrl = "https://miyamibu.xyz.",
            )
        )
    }

    @Test
    fun canonicalHttpsAdversarialMatrix_rejectsNonCanonicalAuthorityAndPath() {
        val rejectedUrls = listOf(
            "https://miyamibu.xyz.evil/invite/suffix-token",
            "https://evil-miyamibu.xyz/invite/prefix-token",
            "https://canonical.example.evil/invite/canonical-example-token",
            "https://user:pass@miyamibu.xyz/invite/userinfo-token",
            "https://@miyamibu.xyz/invite/empty-userinfo-token",
            "https://miyamibu.xyz:443/invite/default-port-token",
            "https://miyamibu.xyz:8443/invite/explicit-port-token",
            "http://miyamibu.xyz/invite/http-token",
            "https:///invite/hostless-token",
            "https://[invalid/invite/malformed-token",
            "https://miyamibu.xyz/invite/token%2Fpart",
            "https://miyamibu.xyz/invite/token%2fpart",
            "https://miyamibu.xyz/invite/token%5Cpart",
            "https://miyamibu.xyz/invite/token%5cpart",
            "https://miyamibu.xyz/invite\\token",
            "https://miyamibu.xyz/invite//token",
            "https://m\u0456yamibu.xyz/invite/unicode-lookalike-token",
            "https://miyamibu.xn--p1ai/invite/foreign-punycode-token",
        )

        for (rawUrl in rejectedUrls) {
            assertFalse(
                "URL must fail closed: $rawUrl",
                ShareReceiverEntrypointRouter.isCanonicalHttpsUri(Uri.parse(rawUrl)),
            )
        }
    }

    @Test
    fun canonicalHttpsBaseConfiguration_rejectsUserInfoAndExplicitPorts() {
        val uri = Uri.parse("https://miyamibu.xyz/invite/configuration-token")
        val rejectedBaseUrls = listOf(
            "https://user:pass@miyamibu.xyz",
            "https://@miyamibu.xyz",
            "https://miyamibu.xyz:443",
            "https://miyamibu.xyz:8443",
            "https://m\u0456yamibu.xyz",
            "https://miyamibu.xn--p1ai",
        )

        for (baseUrl in rejectedBaseUrls) {
            assertFalse(
                "Base URL must fail closed: $baseUrl",
                ShareReceiverEntrypointRouter.isCanonicalHttpsUri(uri, baseUrl = baseUrl),
            )
        }
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
    fun actionView_httpsPromoQuery_routesToMainWithPromoCodeExtra() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/promo?code=RNBM%20QUERY%20CODE%209012")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("RNBM QUERY CODE 9012", started.getStringExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
    }

    @Test
    fun actionView_httpsForeignPromoLink_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://example.test/promo?code=FOREIGN-CODE")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsInviteWithoutToken_routesToMainWithInviteInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/invite")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertTrue(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
    }

    @Test
    fun actionView_httpsInviteWithExtraSegments_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/invite/token/extra")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsUppercaseInvite_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/INVITE/token")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_SHARED_TAG_INVITE_TOKEN))
        assertFalse(started.getBooleanExtra(EXTRA_SHARED_TAG_INVITE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsPromoWithExtraSegments_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/promo/extra?code=X")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsUppercasePromo_routesToMainWithGenericInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/PROMO?code=X")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
        assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
    }

    @Test
    fun actionView_httpsPromoQueryCodeKeyWinsOverFragmentEvenWhenEmpty() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/promo?code=#code=FRAGMENT")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_PROMO_CODE))
        assertTrue(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
    }

    @Test
    fun actionView_httpsPromoDuplicateQueryCodeUsesFirstValue() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/promo?code=FIRST&code=SECOND")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertEquals("FIRST", started.getStringExtra(EXTRA_PROMO_CODE))
        assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
    }

    @Test
    fun actionView_httpsPromoWithAmbiguousPath_routesToGenericInvalid() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val ambiguousUrls = listOf(
            "https://miyamibu.xyz/promo/%2F?code=encoded-slash-upper",
            "https://miyamibu.xyz/promo/%2f?code=encoded-slash-lower",
            "https://miyamibu.xyz/promo/%5C?code=encoded-backslash-upper",
            "https://miyamibu.xyz/promo/%5c?code=encoded-backslash-lower",
            "https://miyamibu.xyz/promo\\token?code=raw-backslash",
            "https://miyamibu.xyz/promo//token?code=double-slash",
        )

        for (rawUrl in ambiguousUrls) {
            val intent = Intent(context, ShareReceiverActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(rawUrl)
            }
            val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            val started = shadowOf(controller.get()).nextStartedActivity
            assertFalse("promo must not be accepted: $rawUrl", started.hasExtra(EXTRA_PROMO_CODE))
            assertFalse(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
            assertTrue(started.getBooleanExtra(EXTRA_DEEP_LINK_INVALID, false))
        }
    }

    @Test
    fun actionView_httpsPromoWithoutCode_routesToMainWithPromoInvalidFlag() {
        val context = ApplicationProvider.getApplicationContext<UrlSaverApp>()
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://miyamibu.xyz/promo")
        }

        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertFalse(started.hasExtra(EXTRA_PROMO_CODE))
        assertTrue(started.getBooleanExtra(EXTRA_PROMO_CODE_INVALID, false))
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
