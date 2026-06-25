package jp.mimac.urlsaver.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private const val AUTH_CALLBACK_URL = "urlsaver://auth/callback"

interface SharedTagAuthRemoteDataSource {
    fun oauthUrl(provider: String, redirectTo: String): String =
        throw IOException("OAuth sign-in is not configured.")
    suspend fun signInWithOAuthCallback(callbackUrl: String): SharedTagAuthRemoteResult =
        throw IOException("OAuth sign-in is not configured.")
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
    private val oauthStateStore: SharedTagOAuthStateStore = InMemorySharedTagOAuthStateStore(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : SharedTagAuthRemoteDataSource {

    override fun oauthUrl(provider: String, redirectTo: String): String {
        check(config.isConfigured) { "Supabase shared tag sync is not configured." }
        val pending = SharedTagOAuthPendingState.create(redirectTo = redirectTo)
        oauthStateStore.save(pending)
        val encodedProvider = URLEncoder.encode(provider, Charsets.UTF_8.name())
        val encodedRedirect = URLEncoder.encode(redirectTo, Charsets.UTF_8.name())
        val encodedChallenge = URLEncoder.encode(pending.codeChallenge, Charsets.UTF_8.name())
        val encodedState = URLEncoder.encode(pending.state, Charsets.UTF_8.name())
        return "${config.supabaseUrl.trimEnd('/')}/auth/v1/authorize" +
            "?provider=$encodedProvider" +
            "&redirect_to=$encodedRedirect" +
            "&code_challenge=$encodedChallenge" +
            "&code_challenge_method=S256" +
            "&state=$encodedState"
    }

    override suspend fun signInWithOAuthCallback(callbackUrl: String): SharedTagAuthRemoteResult {
        validateCallbackUrl(callbackUrl)
        val params = parseCallbackParams(callbackUrl)
        params["error_description"]?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
        params["error"]?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
        if (!params["access_token"].isNullOrBlank() || !params["refresh_token"].isNullOrBlank()) {
            oauthStateStore.clear()
            throw IOException("OAuth callbackにtokenが含まれています。PKCE code callbackだけを受け付けます。")
        }
        val code = params["code"]?.takeIf { it.isNotBlank() }
            ?: throw IOException("Googleサインインの認可コードを受け取れませんでした。")
        val returnedState = params["state"]?.takeIf { it.isNotBlank() }
            ?: throw IOException("Googleサインインのstateを受け取れませんでした。")
        val pending = oauthStateStore.load()
            ?: throw IOException("Googleサインインの開始情報が見つかりません。もう一度やり直してください。")
        if (pending.isExpired()) {
            oauthStateStore.clear()
            throw IOException("Googleサインインの有効期限が切れました。もう一度やり直してください。")
        }
        if (pending.redirectTo != AUTH_CALLBACK_URL || returnedState != pending.state) {
            oauthStateStore.clear()
            throw IOException("Googleサインインのstate検証に失敗しました。")
        }
        return try {
            val response = withContext(Dispatchers.IO) {
                executeAuthRequest(
                    path = "/auth/v1/token?grant_type=pkce",
                    requestBody = json.encodeToString(
                        SupabasePkceTokenRequest(
                            authCode = code,
                            codeVerifier = pending.codeVerifier,
                        ),
                    ),
                )
            }
            SharedTagAuthRemoteResult.SignedIn(parseSession(requireSession(response)))
        } finally {
            oauthStateStore.clear()
        }
    }

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

interface SharedTagOAuthStateStore {
    fun save(state: SharedTagOAuthPendingState)
    fun load(): SharedTagOAuthPendingState?
    fun clear()
}

class SharedPreferencesSharedTagOAuthStateStore(context: Context) : SharedTagOAuthStateStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shared_tag_oauth_state", Context.MODE_PRIVATE)

    override fun save(state: SharedTagOAuthPendingState) {
        prefs.edit()
            .putString(KEY_VERIFIER, state.codeVerifier)
            .putString(KEY_CHALLENGE, state.codeChallenge)
            .putString(KEY_STATE, state.state)
            .putString(KEY_REDIRECT_TO, state.redirectTo)
            .putLong(KEY_CREATED_AT, state.createdAtMillis)
            .apply()
    }

    override fun load(): SharedTagOAuthPendingState? {
        val verifier = prefs.getString(KEY_VERIFIER, null)?.takeIf { it.isNotBlank() } ?: return null
        val challenge = prefs.getString(KEY_CHALLENGE, null)?.takeIf { it.isNotBlank() } ?: return null
        val state = prefs.getString(KEY_STATE, null)?.takeIf { it.isNotBlank() } ?: return null
        val redirectTo = prefs.getString(KEY_REDIRECT_TO, null)?.takeIf { it.isNotBlank() } ?: return null
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L).takeIf { it > 0L } ?: return null
        return SharedTagOAuthPendingState(verifier, challenge, state, redirectTo, createdAt)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_CHALLENGE = "code_challenge"
        const val KEY_STATE = "state"
        const val KEY_REDIRECT_TO = "redirect_to"
        const val KEY_CREATED_AT = "created_at"
    }
}

private class InMemorySharedTagOAuthStateStore : SharedTagOAuthStateStore {
    private var state: SharedTagOAuthPendingState? = null
    override fun save(state: SharedTagOAuthPendingState) {
        this.state = state
    }
    override fun load(): SharedTagOAuthPendingState? = state
    override fun clear() {
        state = null
    }
}

data class SharedTagOAuthPendingState(
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
    val redirectTo: String,
    val createdAtMillis: Long,
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - createdAtMillis > EXPIRY_MILLIS

    companion object {
        private const val EXPIRY_MILLIS = 10 * 60 * 1000L

        fun create(redirectTo: String): SharedTagOAuthPendingState {
            val verifier = randomUrlSafe(lengthBytes = 64)
            return SharedTagOAuthPendingState(
                codeVerifier = verifier,
                codeChallenge = sha256Base64UrlNoPadding(verifier),
                state = randomUrlSafe(lengthBytes = 32),
                redirectTo = redirectTo,
                createdAtMillis = System.currentTimeMillis(),
            )
        }
    }
}

private fun validateCallbackUrl(callbackUrl: String) {
    val uri = runCatching { URI(callbackUrl) }.getOrNull()
        ?: throw IOException("Googleサインインのcallback URLが不正です。")
    if (uri.scheme != "urlsaver" || uri.host != "auth" || uri.path != "/callback") {
        throw IOException("Googleサインインのcallback URLを検証できませんでした。")
    }
}

private fun randomUrlSafe(lengthBytes: Int): String {
    val bytes = ByteArray(lengthBytes)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Base64UrlNoPadding(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun parseCallbackParams(callbackUrl: String): Map<String, String> {
    val pieces = buildList {
        val queryStart = callbackUrl.indexOf('?')
        if (queryStart >= 0) {
            val fragmentStart = callbackUrl.indexOf('#', queryStart)
            add(callbackUrl.substring(queryStart + 1, if (fragmentStart >= 0) fragmentStart else callbackUrl.length))
        }
        val fragmentStart = callbackUrl.indexOf('#')
        if (fragmentStart >= 0 && fragmentStart + 1 < callbackUrl.length) {
            add(callbackUrl.substring(fragmentStart + 1))
        }
    }
    return pieces
        .flatMap { it.split('&') }
        .mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val index = part.indexOf('=')
            val key = if (index >= 0) part.substring(0, index) else part
            val value = if (index >= 0) part.substring(index + 1) else ""
            URLDecoder.decode(key, Charsets.UTF_8.name()) to
                URLDecoder.decode(value, Charsets.UTF_8.name())
        }
        .toMap()
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
private data class SupabasePkceTokenRequest(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
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
