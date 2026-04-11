package jp.mimac.urlsaver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.ui.tryOpenExternalUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalActionsTest {

    @Test
    fun tryOpenExternalUrl_returnsFalseWhenNoHandler() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent?) {
                throw ActivityNotFoundException("No browser")
            }
        }

        val opened = context.tryOpenExternalUrl("https://example.com")
        assertFalse(opened)
    }

    @Test
    fun tryOpenExternalUrl_returnsTrueWhenStartSucceeds() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            var called = false
            override fun startActivity(intent: Intent?) {
                called = true
            }
        }

        val opened = context.tryOpenExternalUrl("https://example.com")
        assertTrue(opened)
    }
}
