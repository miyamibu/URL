package jp.mimac.urlsaver.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface SharedTagAuthRemoteDataSource {
    suspend fun signUp(email: String, password: String): SharedTagAuthRemoteResult
    suspend fun signIn(email: String, password: String): SharedTagAuthRemoteResult
    suspend fun refreshSession(refreshToken: String): SharedTagAuthSession
}

sealed interface SharedTagAuthRemoteResult {
    data class SignedIn(val session: SharedTagAuthSession) : SharedTagAuthRemoteResult
    data object NeedsEmailConfirmation : SharedTagAuthRemoteResult
}

class SupabaseSharedTagAuthRemoteDataSource(
    private val config: SharedTagSyncRemoteConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : SharedTagAuthRemoteDataSource {

    override suspend fun signUp(email: String, password: String): SharedTagAuthRemoteResult {
        val response = withContext(Dispatchers.IO) {
            executeAuthRequest(
                path = "/auth/v1/signup",
                requestBody = json.encodeToString(
                    SupabasePasswordAuthRequest(email = email, password = password),
                ),
            )
        }
        return parseSessionResult(response)
    }

    override suspend fun signIn(email: String, password: String): SharedTagAuthRemoteResult {
        val response = withContext(Dispatchers.IO) {
            executeAuthRequest(
                path = "/auth/v1/token?grant_type=password",
                requestBody = json.encodeToString(
                    SupabasePasswordAuthRequest(email = email, password = password),
                ),
            )
        }
        return parseSessionResult(response)
    }

    override suspend fun refreshSession(refreshToken: String): SharedTagAuthSession {
        val response = withContext(Dispatchers.IO) {
            executeAuthRequest(
                path = "/auth/v1/token?grant_type=refresh_token",
                requestBody = json.encodeToString(
                    SupabaseRefreshTokenRequest(refreshToken = refreshToken),
                ),
            )
        }
        return parseSession(requireSession(response))
    }

    private fun parseSessionResult(response: String): SharedTagAuthRemoteResult {
        val sessionResponse = json.decodeFromString<SupabaseAuthSessionResponse>(response)
        val accessToken = sessionResponse.accessToken
        val refreshToken = sessionResponse.refreshToken
        return if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            SharedTagAuthRemoteResult.NeedsEmailConfirmation
        } else {
            SharedTagAuthRemoteResult.SignedIn(parseSession(sessionResponse))
        }
    }

    private fun requireSession(response: String): SupabaseAuthSessionResponse {
        val sessionResponse = json.decodeFromString<SupabaseAuthSessionResponse>(response)
        val accessToken = sessionResponse.accessToken
        val refreshToken = sessionResponse.refreshToken
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            throw IOException("Supabase Auth response did not contain a session.")
        }
        return sessionResponse
    }

    private fun parseSession(response: SupabaseAuthSessionResponse): SharedTagAuthSession {
        val user = response.user ?: throw IOException("Supabase Auth response did not contain a user.")
        val accessToken = response.accessToken ?: throw IOException("Supabase Auth response missing access token.")
        return SharedTagAuthSession(
            authUserId = user.id,
            accessToken = accessToken,
            refreshToken = response.refreshToken,
            userEmail = user.email,
        )
    }

    private fun executeAuthRequest(
        path: String,
        requestBody: String,
    ): String {
        check(config.isConfigured) { "Supabase shared tag sync is not configured." }
        val url = URL(config.supabaseUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.anonKey)
        }

        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        val responseCode = connection.responseCode
        val body = runCatching {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        if (responseCode !in 200..299) {
            val authError = runCatching {
                json.decodeFromString<SupabaseAuthErrorResponse>(body).message
            }.getOrNull().orEmpty()
            throw IOException(
                buildString {
                    append("Supabase Auth failed (")
                    append(responseCode)
                    append(")")
                    if (authError.isNotBlank()) {
                        append(": ")
                        append(authError)
                    } else if (body.isNotBlank()) {
                        append(": ")
                        append(body)
                    }
                },
            )
        }
        return body
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

@Serializable
private data class SupabasePasswordAuthRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class SupabaseRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
private data class SupabaseAuthSessionResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: SupabaseAuthUser? = null,
)

@Serializable
private data class SupabaseAuthUser(
    val id: String,
    val email: String? = null,
)

@Serializable
private data class SupabaseAuthErrorResponse(
    val message: String,
)
