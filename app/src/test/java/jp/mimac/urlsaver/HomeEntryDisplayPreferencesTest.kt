package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.DataStoreEntryCardDisplayModeStore
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import jp.mimac.urlsaver.domain.EntryListSortKey
import jp.mimac.urlsaver.domain.EntryListSortOrder
import jp.mimac.urlsaver.domain.HomeEntryDisplayPreferences
import jp.mimac.urlsaver.domain.HomeEntryLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeEntryDisplayPreferencesTest {
    @Test
    fun persistedHomeGridSettingsDoNotOverwriteExistingListAndSortPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DataStoreEntryCardDisplayModeStore(context)
        store.setDisplayMode(EntryCardDisplayMode.COMPACT)
        val sort = EntryListSortOrder(EntryListSortKey.TITLE, ascending = true)
        store.setSortOrder(sort)
        val grid = HomeEntryDisplayPreferences(HomeEntryLayout.THUMBNAILS, gridShowsInfo = false)
        store.setHomeDisplayPreferences(grid)
        val recreated = DataStoreEntryCardDisplayModeStore(context)
        assertEquals(grid, recreated.observeHomeDisplayPreferences().first())
        assertEquals(EntryCardDisplayMode.COMPACT, recreated.observeDisplayMode().first())
        assertEquals(sort, recreated.observeSortOrder().first())
        recreated.setHomeDisplayPreferences(grid.copy(layout = HomeEntryLayout.LIST))
        assertEquals(false, store.observeHomeDisplayPreferences().first().gridShowsInfo)
        assertEquals(EntryCardDisplayMode.COMPACT, store.observeDisplayMode().first())
    }
}
