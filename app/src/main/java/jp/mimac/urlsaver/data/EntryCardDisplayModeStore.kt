package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.EntryCardDisplayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface EntryCardDisplayModeStore {
    fun observeDisplayMode(): Flow<EntryCardDisplayMode>
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

    override suspend fun setDisplayMode(mode: EntryCardDisplayMode) {
        context.displayModeDataStore.edit { preferences ->
            preferences[DISPLAY_MODE_KEY] = mode.name
        }
    }

    private companion object {
        val DISPLAY_MODE_KEY = stringPreferencesKey("entry_card_display_mode")
    }
}

private val Context.displayModeDataStore by preferencesDataStore(name = "list_display_preferences")
