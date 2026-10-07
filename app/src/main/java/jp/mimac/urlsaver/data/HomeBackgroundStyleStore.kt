package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.HomeBackgroundStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

interface HomeBackgroundStyleStore {
    fun observeStyle(): Flow<HomeBackgroundStyle>
    suspend fun setStyle(style: HomeBackgroundStyle)
}

class DataStoreHomeBackgroundStyleStore(
    context: Context,
) : HomeBackgroundStyleStore {
    private val appContext = context.applicationContext

    override fun observeStyle(): Flow<HomeBackgroundStyle> {
        return appContext.homeBackgroundStyleDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences ->
                HomeBackgroundStyle.fromStorageValue(preferences[HOME_BACKGROUND_STYLE_KEY])
            }
    }

    override suspend fun setStyle(style: HomeBackgroundStyle) {
        appContext.homeBackgroundStyleDataStore.edit { preferences ->
            preferences[HOME_BACKGROUND_STYLE_KEY] = style.storageValue
        }
    }

    private companion object {
        val HOME_BACKGROUND_STYLE_KEY = stringPreferencesKey("home_background_style")
    }
}

private val Context.homeBackgroundStyleDataStore by preferencesDataStore(name = "home_background_preferences")
