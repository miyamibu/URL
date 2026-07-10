package jp.mimac.urlsaver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.ui.OpenUrlResult
import jp.mimac.urlsaver.ui.tryOpenExternalUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalActionsTest {

    @Test
    fun tryOpenExternalUrl_returnsNoHandlerWhenNoHandler() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent?) {
                throw ActivityNotFoundException("No browser")
            }
        }

        val result = context.tryOpenExternalUrl("https://example.com")
        assertEquals(OpenUrlResult.NoHandler, result)
    }

    @Test
    fun tryOpenExternalUrl_returnsSuccessWhenStartSucceeds() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            var called = false
            override fun startActivity(intent: Intent?) {
                called = true
            }
        }

        val result = context.tryOpenExternalUrl("https://example.com")
        assertEquals(OpenUrlResult.Success, result)
        assertEquals(true, context.called)
    }

    @Test
    fun tryOpenExternalUrl_returnsFailedOnUnexpectedException() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent?) {
                throw SecurityException("blocked")
            }
        }

        val result = context.tryOpenExternalUrl("https://example.com")
        assertEquals(OpenUrlResult.Failed, result)
    }
}
