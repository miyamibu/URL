package jp.mimac.urlsaver.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class StorePurchaseVerificationResult(
    val status: String,
    val verificationId: String?,
    val grantId: String?,
    val plan: String?,
    val billingPeriod: String?,
)

interface StorePurchaseRemoteDataSource {
    suspend fun verifyStorePurchase(
        session: SharedTagAuthSession,
        storePlatform: String,
        storeProductId: String,
        purchaseToken: String,
        storeTransactionId: String?,
    ): StorePurchaseVerificationResult
}

class SupabaseStorePurchaseRemoteDataSource(
    private val config: SharedTagSyncRemoteConfig,
    private val authSessionProvider: SharedTagAuthSessionProvider,
    private val authRemoteDataSource: SharedTagAuthRemoteDataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : StorePurchaseRemoteDataSource {
    override suspend fun verifyStorePurchase(
        session: SharedTagAuthSession,
        storePlatform: String,
        storeProductId: String,
        purchaseToken: String,
        storeTransactionId: String?,
    ): StorePurchaseVerificationResult {
        val body = json.encodeToString(
            StorePurchaseVerificationRequest(
                storePlatform = storePlatform,
                storeProductId = storeProductId,
                purchaseToken = purchaseToken,
                storeTransactionId = storeTransactionId,
            ),
        )
        val response = withContext(Dispatchers.IO) {
            executeFunction(session = session, requestBody = body)
        }
        val decoded = json.decodeFromString<StorePurchaseVerificationResponse>(response)
        return StorePurchaseVerificationResult(
            status = decoded.status,
            verificationId = decoded.verificationId,
            grantId = decoded.grantId,
            plan = decoded.plan,
            billingPeriod = decoded.billingPeriod,
        )
    }

    private suspend fun executeFunction(
        session: SharedTagAuthSession,
        requestBody: String,
        allowRefresh: Boolean = true,
    ): String {
        check(config.isConfigured) { "Supabase store purchase verification is not configured." }
        val url = URL(config.supabaseUrl.trimEnd('/') + "/functions/v1/verify-store-purchase")
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
            return executeFunction(
                session = refreshedSession,
                requestBody = requestBody,
                allowRefresh = false,
            )
        }
        if (responseCode !in 200..299) {
            throw IOException("Supabase store purchase verification failed ($responseCode): $body")
        }
        return body
    }

    @Serializable
    private data class StorePurchaseVerificationRequest(
        @SerialName("storePlatform") val storePlatform: String,
        @SerialName("storeProductId") val storeProductId: String,
        @SerialName("purchaseToken") val purchaseToken: String,
        @SerialName("storeTransactionId") val storeTransactionId: String?,
    )

    @Serializable
    private data class StorePurchaseVerificationResponse(
        val status: String,
        val verificationId: String? = null,
        val grantId: String? = null,
        val plan: String? = null,
        val billingPeriod: String? = null,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
