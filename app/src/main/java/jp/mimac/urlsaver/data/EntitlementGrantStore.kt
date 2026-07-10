package jp.mimac.urlsaver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.domain.EntitlementGrantStatus
import jp.mimac.urlsaver.domain.EntitlementSource
import jp.mimac.urlsaver.domain.PlanType
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface EntitlementGrantStore {
    suspend fun loadLastKnownGrants(
        authUserId: String,
        currentTimeMillis: Long,
    ): List<EntitlementGrant>

    suspend fun saveLastKnownGrants(
        authUserId: String,
        grants: List<EntitlementGrant>,
        fetchedAtMillis: Long,
    )

    fun cachedGrantsSnapshot(
        authUserId: String?,
        currentTimeMillis: Long,
    ): List<EntitlementGrant>
}

class DataStoreEntitlementGrantStore(
    context: Context,
    private val cacheTtlMillis: Long = CACHE_TTL_MILLIS,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : EntitlementGrantStore {
    private val dataStore = context.entitlementGrantDataStore
    @Volatile private var snapshot: CacheSnapshot? = null

    override suspend fun loadLastKnownGrants(
        authUserId: String,
        currentTimeMillis: Long,
    ): List<EntitlementGrant> {
        snapshot?.validGrants(authUserId, currentTimeMillis, cacheTtlMillis)?.let { return it }
        val preferences = dataStore.data.first()
        val loaded = CacheSnapshot(
            authUserId = preferences[KEY_AUTH_USER_ID],
            fetchedAtMillis = preferences[KEY_FETCHED_AT] ?: 0L,
            grants = preferences[KEY_GRANTS_JSON]
                ?.takeIf { it.isNotBlank() }
                ?.let { encoded ->
                    runCatching { json.decodeFromString<List<CachedGrant>>(encoded) }
                        .getOrDefault(emptyList())
                        .mapNotNull { it.toDomainOrNull() }
                }
                .orEmpty(),
        )
        snapshot = loaded
        return loaded.validGrants(authUserId, currentTimeMillis, cacheTtlMillis).orEmpty()
    }

    override suspend fun saveLastKnownGrants(
        authUserId: String,
        grants: List<EntitlementGrant>,
        fetchedAtMillis: Long,
    ) {
        val cached = CacheSnapshot(
            authUserId = authUserId,
            fetchedAtMillis = fetchedAtMillis,
            grants = grants,
        )
        dataStore.edit { preferences ->
            preferences[KEY_AUTH_USER_ID] = authUserId
            preferences[KEY_FETCHED_AT] = fetchedAtMillis
            preferences[KEY_GRANTS_JSON] = json.encodeToString(grants.map(CachedGrant::fromDomain))
        }
        snapshot = cached
    }

    override fun cachedGrantsSnapshot(
        authUserId: String?,
        currentTimeMillis: Long,
    ): List<EntitlementGrant> {
        if (authUserId == null) {
            return emptyList()
        }
        return snapshot?.validGrants(authUserId, currentTimeMillis, cacheTtlMillis).orEmpty()
    }

    private data class CacheSnapshot(
        val authUserId: String?,
        val fetchedAtMillis: Long,
        val grants: List<EntitlementGrant>,
    ) {
        fun validGrants(
            currentAuthUserId: String,
            currentTimeMillis: Long,
            cacheTtlMillis: Long,
        ): List<EntitlementGrant>? {
            if (authUserId != currentAuthUserId) {
                return null
            }
            if (currentTimeMillis - fetchedAtMillis > cacheTtlMillis) {
                return null
            }
            return grants
        }
    }

    @Serializable
    private data class CachedGrant(
        val planType: String,
        val source: String,
        val status: String,
        val startsAt: Long,
        val endsAt: Long? = null,
        val sourceId: String? = null,
        val note: String? = null,
    ) {
        fun toDomainOrNull(): EntitlementGrant? {
            return EntitlementGrant(
                planType = runCatching { PlanType.valueOf(planType) }.getOrNull() ?: return null,
                source = runCatching { EntitlementSource.valueOf(source) }.getOrNull() ?: return null,
                status = runCatching { EntitlementGrantStatus.valueOf(status) }.getOrNull() ?: return null,
                startsAt = startsAt,
                endsAt = endsAt,
                sourceId = sourceId,
                note = note,
            )
        }

        companion object {
            fun fromDomain(grant: EntitlementGrant): CachedGrant {
                return CachedGrant(
                    planType = grant.planType.name,
                    source = grant.source.name,
                    status = grant.status.name,
                    startsAt = grant.startsAt,
                    endsAt = grant.endsAt,
                    sourceId = grant.sourceId,
                    note = grant.note,
                )
            }
        }
    }

    companion object {
        const val CACHE_TTL_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L
        private val KEY_AUTH_USER_ID = stringPreferencesKey("auth_user_id")
        private val KEY_FETCHED_AT = longPreferencesKey("fetched_at_millis")
        private val KEY_GRANTS_JSON = stringPreferencesKey("grants_json")
    }
}

private val Context.entitlementGrantDataStore by preferencesDataStore(name = "entitlement_grants")
