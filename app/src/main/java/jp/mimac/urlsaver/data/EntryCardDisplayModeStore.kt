package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.HomeEntryDisplayPreferences
import jp.mimac.urlsaver.domain.HomeEntryLayout
import jp.mimac.urlsaver.domain.EntryListSortOrder
import jp.mimac.urlsaver.domain.EntryListSortKey
import kotlinx.coroutines.flow.flowOf
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface EntryCardDisplayModeStore {
    fun observeDisplayMode(): Flow<EntryCardDisplayMode>
    fun observeHomeDisplayPreferences(): Flow<HomeEntryDisplayPreferences> = flowOf(HomeEntryDisplayPreferences())
    suspend fun setHomeDisplayPreferences(value: HomeEntryDisplayPreferences) = Unit
    fun observeSortOrder(): Flow<EntryListSortOrder> = flowOf(EntryListSortOrder())
    suspend fun setSortOrder(order: EntryListSortOrder) = Unit
    suspend fun setDisplayMode(mode: EntryCardDisplayMode)
}

class DataStoreEntryCardDisplayModeStore(
    private val context: Context,
) : EntryCardDisplayModeStore {

    override fun observeDisplayMode(): Flow<EntryCardDisplayMode> {
        return context.displayModeDataStore.data.map { preferences ->
            preferences[DISPLAY_MODE_KEY]
                ?.let { storedValue ->
                    runCatching { EntryCardDisplayMode.valueOf(storedValue) }.getOrNull()
                }
                ?: EntryCardDisplayMode.RICH
        }
    }

    override fun observeHomeDisplayPreferences(): Flow<HomeEntryDisplayPreferences> {
        return context.displayModeDataStore.data.map { prefs ->
            HomeEntryDisplayPreferences(
                layout = prefs[HOME_LAYOUT_KEY]
                    ?.let { runCatching { HomeEntryLayout.valueOf(it) }.getOrNull() }
                    ?: HomeEntryLayout.LIST,
                gridShowsInfo = prefs[HOME_GRID_INFO_KEY] ?: true,
            )
        }
    }

    override suspend fun setHomeDisplayPreferences(value: HomeEntryDisplayPreferences) {
        context.displayModeDataStore.edit { prefs ->
            prefs[HOME_LAYOUT_KEY] = value.layout.name
            prefs[HOME_GRID_INFO_KEY] = value.gridShowsInfo
        }
    }

    override fun observeSortOrder(): Flow<EntryListSortOrder> = context.displayModeDataStore.data.map { prefs ->
        EntryListSortOrder(
            key = prefs[SORT_KEY]?.let { runCatching { EntryListSortKey.valueOf(it) }.getOrNull() } ?: EntryListSortKey.ADDED_DATE,
            ascending = prefs[SORT_ASCENDING_KEY] ?: false,
        )
    }

    override suspend fun setSortOrder(order: EntryListSortOrder) {
        context.displayModeDataStore.edit { prefs ->
            prefs[SORT_KEY] = order.key.name
            prefs[SORT_ASCENDING_KEY] = order.ascending
        }
    }

    override suspend fun setDisplayMode(mode: EntryCardDisplayMode) {
        context.displayModeDataStore.edit { preferences ->
            preferences[DISPLAY_MODE_KEY] = mode.name
        }
    }

    private companion object {
        val HOME_LAYOUT_KEY = stringPreferencesKey("home_entry_layout")
        val HOME_GRID_INFO_KEY = booleanPreferencesKey("home_grid_shows_info")
        val SORT_KEY = stringPreferencesKey("entry_list_sort_key")
        val SORT_ASCENDING_KEY = booleanPreferencesKey("entry_list_sort_ascending")
        val DISPLAY_MODE_KEY = stringPreferencesKey("entry_card_display_mode")
    }
}

private val Context.displayModeDataStore by preferencesDataStore(name = "list_display_preferences")
