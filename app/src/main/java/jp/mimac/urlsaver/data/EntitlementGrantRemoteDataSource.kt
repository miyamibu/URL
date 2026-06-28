package jp.mimac.urlsaver.data

import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.domain.EntitlementGrantStatus
import jp.mimac.urlsaver.domain.EntitlementSource
import jp.mimac.urlsaver.domain.PlanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

interface EntitlementGrantRemoteDataSource {
    suspend fun fetchGrants(session: SharedTagAuthSession): List<EntitlementGrant>
    suspend fun redeemPromoCode(session: SharedTagAuthSession, code: String): List<EntitlementGrant>
}

class SupabaseEntitlementGrantRemoteDataSource(
    private val config: SharedTagSyncRemoteConfig,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val authRemoteDataSource: SharedTagAuthRemoteDataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : EntitlementGrantRemoteDataSource {
    override suspend fun fetchGrants(session: SharedTagAuthSession): List<EntitlementGrant> {
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/get_my_entitlement_grants",
                session = session,
                requestBody = "{}",
            )
        }
        return decodeEntitlementGrantResponse(response)
    }

    override suspend fun redeemPromoCode(session: SharedTagAuthSession, code: String): List<EntitlementGrant> {
        val response = withContext(Dispatchers.IO) {
            executeRpc(
                path = "/rest/v1/rpc/redeem_promo_code",
                session = session,
                requestBody = json.encodeToString<PromoCodeRequest>(PromoCodeRequest(code)),
            )
        }
        return decodeEntitlementGrantResponse(response)
    }

    private fun decodeEntitlementGrantResponse(response: String): List<EntitlementGrant> {
        return when (json.parseToJsonElement(response)) {
            is JsonArray -> json.decodeFromString<List<RemoteEntitlementGrant>>(response)
            is JsonObject -> listOf(json.decodeFromString<RemoteEntitlementGrant>(response))
            else -> emptyList()
        }.mapNotNull { it.toDomainOrNull() }
    }

    private suspend fun executeRpc(
        path: String,
        session: SharedTagAuthSession,
        requestBody: String,
        allowRefresh: Boolean = true,
    ): String {
        check(config.isConfigured) { "Supabase entitlement grants are not configured." }
        val url = URL(config.supabaseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.anonKey)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        }

        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        val responseCode = connection.responseCode
        val body = runCatching {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED &&
            allowRefresh &&
            !session.refreshToken.isNullOrBlank()
        ) {
            val refreshedSession = authRemoteDataSource.refreshSession(requireNotNull(session.refreshToken))
            authSessionProvider.updateSession(refreshedSession)
            return executeRpc(
                path = path,
                session = refreshedSession,
                requestBody = requestBody,
                allowRefresh = false,
            )
        }
        if (responseCode !in 200..299) {
            throw IOException("Supabase entitlement RPC failed ($responseCode): $body")
        }
        return body
    }

    @Serializable
    private data class PromoCodeRequest(
        @SerialName("p_code")
        val code: String,
    )

    @Serializable
    private data class RemoteEntitlementGrant(
        val id: String? = null,
        @SerialName("grant_id")
        val grantId: String? = null,
        val plan: String,
        val source: String = "admin_grant",
        @SerialName("store_platform")
        val storePlatform: String? = null,
        @SerialName("store_transaction_id")
        val storeTransactionId: String? = null,
        @SerialName("starts_at")
        val startsAt: String? = null,
        @SerialName("claimed_at")
        val claimedAt: String? = null,
        @SerialName("expires_at")
        val expiresAt: String? = null,
        val status: String = "active",
    ) {
        fun toDomainOrNull(): EntitlementGrant? {
            val sourceId = storeTransactionId ?: id ?: grantId ?: return null
            val startsAtValue = startsAt ?: claimedAt ?: Instant.now().toString()
            return EntitlementGrant(
                planType = plan.toPlanTypeOrNull() ?: return null,
                source = source.toEntitlementSourceOrNull() ?: return null,
                status = status.toEntitlementGrantStatusOrNull() ?: return null,
                startsAt = runCatching { Instant.parse(startsAtValue).toEpochMilli() }.getOrNull() ?: return null,
                endsAt = expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                sourceId = sourceId,
                note = storePlatform,
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        fun String.toPlanTypeOrNull(): PlanType? {
            return when (lowercase()) {
                "free" -> PlanType.FREE
                "launch_standard" -> PlanType.LAUNCH_STANDARD
                "standard" -> PlanType.STANDARD
                "pro" -> PlanType.PRO
                "promo_pro" -> PlanType.PROMO_PRO
                else -> null
            }
        }

        fun String.toEntitlementSourceOrNull(): EntitlementSource? {
            return when (lowercase()) {
                "store_subscription" -> EntitlementSource.STORE_SUBSCRIPTION
                "store_promo_code" -> EntitlementSource.STORE_PROMO_CODE
                "admin_grant" -> EntitlementSource.ADMIN_GRANT
                "referral_grant" -> EntitlementSource.REFERRAL_GRANT
                else -> null
            }
        }

        fun String.toEntitlementGrantStatusOrNull(): EntitlementGrantStatus? {
            return when (lowercase()) {
                "active" -> EntitlementGrantStatus.ACTIVE
                "revoked" -> EntitlementGrantStatus.REVOKED
                "pending" -> EntitlementGrantStatus.PENDING
                else -> null
            }
        }
    }
}
