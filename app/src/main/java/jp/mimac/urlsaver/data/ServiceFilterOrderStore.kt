package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.ServiceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ServiceFilterOrderStore {
    fun observeServiceOrder(): Flow<List<ServiceType>>
    suspend fun setServiceOrder(serviceOrder: List<ServiceType>)
}

class DataStoreServiceFilterOrderStore(
    private val context: Context,
) : ServiceFilterOrderStore {

    override fun observeServiceOrder(): Flow<List<ServiceType>> {
        return context.serviceFilterDataStore.data.map { preferences ->
            preferences[SERVICE_FILTER_ORDER_KEY]
                ?.split(",")
                ?.mapNotNull { rawValue ->
                    runCatching { ServiceType.valueOf(rawValue) }.getOrNull()
                }
                ?.takeIf { storedOrder ->
                    storedOrder.toSet() == DEFAULT_MOVABLE_SERVICE_ORDER.toSet() &&
                        storedOrder.size == DEFAULT_MOVABLE_SERVICE_ORDER.size
                }
                ?: DEFAULT_MOVABLE_SERVICE_ORDER
        }
    }

    override suspend fun setServiceOrder(serviceOrder: List<ServiceType>) {
        val normalized = serviceOrder
            .filter { it in DEFAULT_MOVABLE_SERVICE_ORDER }
            .distinct()
            .takeIf { it.toSet() == DEFAULT_MOVABLE_SERVICE_ORDER.toSet() && it.size == DEFAULT_MOVABLE_SERVICE_ORDER.size }
            ?: DEFAULT_MOVABLE_SERVICE_ORDER

        context.serviceFilterDataStore.edit { preferences ->
            preferences[SERVICE_FILTER_ORDER_KEY] = normalized.joinToString(",") { it.name }
        }
    }

    private companion object {
        val DEFAULT_MOVABLE_SERVICE_ORDER = listOf(
            ServiceType.YOUTUBE,
            ServiceType.X,
            ServiceType.INSTAGRAM,
            ServiceType.TIKTOK,
            ServiceType.WEB,
        )
        val SERVICE_FILTER_ORDER_KEY = stringPreferencesKey("service_filter_order")
    }
}

private val Context.serviceFilterDataStore by preferencesDataStore(name = "service_filter_preferences")
