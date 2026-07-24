package jp.mimac.urlsaver.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
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
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AUTH_CALLBACK_URL = "urlsaver://auth/callback"

interface SharedTagAuthRemoteDataSource {
    fun oauthUrl(provider: String, redirectTo: String): String =
        throw IOException("OAuth sign-in is not configured.")
    suspend fun signInWithOAuthCallback(callbackUrl: String): SharedTagAuthRemoteResult =
        throw IOException("OAuth sign-in is not configured.")
    suspend fun signUp(email: String, password: String): SharedTagAuthRemoteResult
    suspend fun signIn(email: String, password: String): SharedTagAuthRemoteResult
    suspend fun refreshSession(refreshToken: String): SharedTagAuthSession
    suspend fun resendEmailConfirmation(email: String) {
        throw IOException("Email confirmation resend is not configured.")
    }
    suspend fun sendPasswordRecovery(email: String) {
        throw IOException("Password recovery is not configured.")
    }
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
        require(provider == "google") { "Unsupported OAuth provider." }
        require(redirectTo == AUTH_CALLBACK_URL) { "OAuth redirect URL is not allowed." }
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
        params["error_description"]?.takeIf { it.isNotBlank() }?.let {
            oauthStateStore.clear()
            throw IOException(it)
        }
        params["error"]?.takeIf { it.isNotBlank() }?.let {
            oauthStateStore.clear()
            throw IOException(it)
        }
        if (!params["access_token"].isNullOrBlank() || !params["refresh_token"].isNullOrBlank()) {
            oauthStateStore.clear()
            throw IOException("OAuth callbackにtokenが含まれています。PKCE code callbackだけを受け付けます。")
        }
        val code = params["code"]?.takeIf { it.isNotBlank() }
            ?: run {
                oauthStateStore.clear()
                throw IOException("Googleサインインの認可コードを受け取れませんでした。")
            }
        val pending = oauthStateStore.load()
            ?: throw IOException("Googleサインインの開始情報が見つかりません。もう一度やり直してください。")
        if (pending.isExpired()) {
            oauthStateStore.clear()
            throw IOException("Googleサインインの有効期限が切れました。もう一度やり直してください。")
        }
        if (pending.redirectTo != AUTH_CALLBACK_URL) {
            oauthStateStore.clear()
            throw IOException("Googleサインインのstate検証に失敗しました。")
        }
        val callbackState = params["state"]?.takeIf { it.isNotBlank() }
        if (callbackState == null || !constantTimeEquals(callbackState, pending.state)) {
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
                path = authPathWithRedirect("/auth/v1/signup"),
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

    override suspend fun resendEmailConfirmation(email: String) {
        withContext(Dispatchers.IO) {
            executeAuthRequest(
                path = "/auth/v1/resend",
                requestBody = json.encodeToString(
                    SupabaseResendRequest(
                        type = "signup",
                        email = email.trim(),
                        options = SupabaseEmailRedirectOptions(emailRedirectTo = AUTH_CALLBACK_URL),
                    ),
                ),
            )
        }
    }

    override suspend fun sendPasswordRecovery(email: String) {
        withContext(Dispatchers.IO) {
            executeAuthRequest(
                path = authPathWithRedirect("/auth/v1/recover"),
                requestBody = json.encodeToString(
                    SupabasePasswordRecoveryRequest(email = email.trim()),
                ),
            )
        }
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

    private fun authPathWithRedirect(path: String): String {
        val encodedRedirect = URLEncoder.encode(AUTH_CALLBACK_URL, Charsets.UTF_8.name())
        val separator = if (path.contains("?")) "&" else "?"
        return "$path${separator}redirect_to=$encodedRedirect"
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
        val encrypted = listOf(state.codeVerifier, state.codeChallenge, state.state, state.redirectTo)
            .map { runCatching { encrypt(it) }.getOrNull() }
        if (encrypted.any { it == null }) {
            clear()
            return
        }
        prefs.edit()
            .putString(KEY_ENCRYPTED_VERIFIER, encrypted[0])
            .putString(KEY_ENCRYPTED_CHALLENGE, encrypted[1])
            .putString(KEY_ENCRYPTED_STATE, encrypted[2])
            .putString(KEY_ENCRYPTED_REDIRECT_TO, encrypted[3])
            .remove(KEY_VERIFIER)
            .remove(KEY_CHALLENGE)
            .remove(KEY_STATE)
            .remove(KEY_REDIRECT_TO)
            .putLong(KEY_CREATED_AT, state.createdAtMillis)
            .apply()
    }

    override fun load(): SharedTagOAuthPendingState? {
        val verifier = decrypt(prefs.getString(KEY_ENCRYPTED_VERIFIER, null)) ?: run {
            if (prefs.contains(KEY_VERIFIER)) clear()
            return null
        }
        val challenge = decrypt(prefs.getString(KEY_ENCRYPTED_CHALLENGE, null)) ?: run { clear(); return null }
        val state = decrypt(prefs.getString(KEY_ENCRYPTED_STATE, null)) ?: run { clear(); return null }
        val redirectTo = decrypt(prefs.getString(KEY_ENCRYPTED_REDIRECT_TO, null)) ?: run { clear(); return null }
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L).takeIf { it > 0L } ?: run {
            clear()
            return null
        }
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
        const val KEY_ENCRYPTED_VERIFIER = "encrypted_code_verifier"
        const val KEY_ENCRYPTED_CHALLENGE = "encrypted_code_challenge"
        const val KEY_ENCRYPTED_STATE = "encrypted_state"
        const val KEY_ENCRYPTED_REDIRECT_TO = "encrypted_redirect_to"
        const val KEY_CREATED_AT = "created_at"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "jp.miyamibu.urlalbum.oauth_state"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKey()
            }
        }

        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = AndroidBase64.encodeToString(cipher.iv, AndroidBase64.NO_WRAP)
            val payload = AndroidBase64.encodeToString(
                cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
                AndroidBase64.NO_WRAP,
            )
            return "$iv.$payload"
        }

        fun decrypt(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                val parts = value.split('.', limit = 2)
                require(parts.size == 2)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, AndroidBase64.decode(parts[0], AndroidBase64.DEFAULT)),
                )
                String(
                    cipher.doFinal(AndroidBase64.decode(parts[1], AndroidBase64.DEFAULT)),
                    Charsets.UTF_8,
                )
            }.getOrNull()
        }
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
        createdAtMillis > nowMillis || nowMillis - createdAtMillis > EXPIRY_MILLIS

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

private fun constantTimeEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(
        left.toByteArray(Charsets.US_ASCII),
        right.toByteArray(Charsets.US_ASCII),
    )
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
private data class SupabaseEmailRedirectOptions(
    @SerialName("email_redirect_to") val emailRedirectTo: String,
)

@Serializable
private data class SupabaseResendRequest(
    val type: String,
    val email: String,
    val options: SupabaseEmailRedirectOptions,
)

@Serializable
private data class SupabasePasswordRecoveryRequest(
    val email: String,
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
