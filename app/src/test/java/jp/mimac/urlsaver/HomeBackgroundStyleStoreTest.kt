package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.DataStoreHomeBackgroundStyleStore
import jp.mimac.urlsaver.domain.HomeBackgroundStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeBackgroundStyleStoreTest {
    @Test
    fun missingInvalidAndOldValuesFallBackToExistingWarmBeigeDefault() {
        assertEquals(HomeBackgroundStyle.WARM_BEIGE, HomeBackgroundStyle.DEFAULT)
        assertEquals(HomeBackgroundStyle.WARM_BEIGE, HomeBackgroundStyle.fromStorageValue(null))
        assertEquals(HomeBackgroundStyle.WARM_BEIGE, HomeBackgroundStyle.fromStorageValue(""))
        assertEquals(HomeBackgroundStyle.WARM_BEIGE, HomeBackgroundStyle.fromStorageValue("legacy_blue"))
        assertEquals(HomeBackgroundStyle.WARM_BEIGE, HomeBackgroundStyle.fromStorageValue("PASTEL_MINT"))
        assertEquals(HomeBackgroundStyle.WARM_BEIGE, HomeBackgroundStyle.fromStorageValue("warm_beige"))
    }

    @Test
    fun everySupportedStyleHasAStableRoundTripStorageValue() {
        HomeBackgroundStyle.entries.forEach { style ->
            assertEquals(style, HomeBackgroundStyle.fromStorageValue(style.storageValue))
        }
    }

    @Test
    fun selectedStylePersistsAcrossStoreRecreation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstStore = DataStoreHomeBackgroundStyleStore(context)

        HomeBackgroundStyle.entries.forEach { style ->
            firstStore.setStyle(style)
            assertEquals(style, DataStoreHomeBackgroundStyleStore(context).observeStyle().first())
        }

        firstStore.setStyle(HomeBackgroundStyle.DEFAULT)
    }
}
