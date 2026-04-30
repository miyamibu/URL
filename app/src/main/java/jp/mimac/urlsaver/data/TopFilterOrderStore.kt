package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TopFilterOrderStore {
    fun observeOrderTokens(): Flow<List<String>>
    suspend fun setOrderTokens(tokens: List<String>)
}

class DataStoreTopFilterOrderStore(
    private val context: Context,
) : TopFilterOrderStore {

    override fun observeOrderTokens(): Flow<List<String>> {
        return context.topFilterDataStore.data.map { preferences ->
            preferences[TOP_FILTER_ORDER_KEY]
                ?.split(",")
                ?.map { token -> token.trim() }
                ?.filter { token -> token.isNotEmpty() }
                ?: emptyList()
        }
    }

    override suspend fun setOrderTokens(tokens: List<String>) {
        context.topFilterDataStore.edit { preferences ->
            preferences[TOP_FILTER_ORDER_KEY] = tokens
                .map { token -> token.trim() }
                .filter { token -> token.isNotEmpty() }
                .distinct()
                .joinToString(",")
        }
    }

    private companion object {
        val TOP_FILTER_ORDER_KEY = stringPreferencesKey("top_filter_order")
    }
}

private val Context.topFilterDataStore by preferencesDataStore(name = "top_filter_preferences")
