package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.TagColorChoice
import jp.mimac.urlsaver.domain.TagColorKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

interface TagColorStore {
    fun observeChoices(): Flow<Map<String, TagColorChoice>>
    suspend fun setChoice(keys: List<TagColorKey>, choice: TagColorChoice)
    suspend fun setChoice(key: TagColorKey, choice: TagColorChoice) = setChoice(listOf(key), choice)
}

class DataStoreTagColorStore(context: Context) : TagColorStore {
    private val appContext = context.applicationContext

    override fun observeChoices(): Flow<Map<String, TagColorChoice>> {
        return appContext.tagColorDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences ->
                preferences.asMap().mapNotNull { (key, value) ->
                    val storageKey = key.name.removePrefix(TAG_COLOR_KEY_PREFIX)
                        .takeIf { key.name.startsWith(TAG_COLOR_KEY_PREFIX) }
                        ?: return@mapNotNull null
                    val choice = TagColorChoice.fromStorageValue(value as? String)
                    if (choice == TagColorChoice.DEFAULT) null else storageKey to choice
                }.toMap()
            }
    }

    override suspend fun setChoice(keys: List<TagColorKey>, choice: TagColorChoice) {
        require(keys.isNotEmpty())
        appContext.tagColorDataStore.edit { preferences ->
            keys.distinct().forEachIndexed { index, key ->
                val preferenceKey = stringPreferencesKey(TAG_COLOR_KEY_PREFIX + key.storageValue)
                // Clear pre-sync fallback colors in the same transaction as the current choice.
                if (index == 0 && choice != TagColorChoice.DEFAULT) {
                    preferences[preferenceKey] = choice.storageValue
                } else {
                    preferences.remove(preferenceKey)
                }
            }
        }
    }

    private companion object {
        const val TAG_COLOR_KEY_PREFIX = "tag_color_"
    }
}

private val Context.tagColorDataStore by preferencesDataStore(name = "tag_color_preferences")
