package jp.mimac.urlsaver.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
private const val FIXED_OAUTH_PROVIDER = "google"
private const val LEGACY_OAUTH_PROVIDER = "__legacy_unbound__"
private const val ENCRYPTED_BLOB_VERSION = "v1"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val OAUTH_STATE_KEY_ALIAS = "jp.mimac.urlsaver.oauth.state.v1"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private object SharedTagOAuthStateStoreLock

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
        val fixedProvider = requireFixedOAuthProvider(provider)
        if (redirectTo != AUTH_CALLBACK_URL) {
            throw IOException("Googleサインインのredirect URLが不正です。")
        }
        val pending = SharedTagOAuthPendingState.create(
            provider = fixedProvider,
            redirectTo = redirectTo,
        )
        if (!oauthStateStore.saveIfAbsent(pending)) {
            throw IOException("Googleサインインがすでに開始されています。現在の認証を完了してから、もう一度お試しください。")
        }
        val encodedProvider = URLEncoder.encode(fixedProvider, Charsets.UTF_8.name())
        val encodedRedirect = URLEncoder.encode(redirectTo, Charsets.UTF_8.name())
        val encodedChallenge = URLEncoder.encode(pending.codeChallenge, Charsets.UTF_8.name())
        return "${config.supabaseUrl.trimEnd('/')}/auth/v1/authorize" +
            "?provider=$encodedProvider" +
            "&redirect_to=$encodedRedirect" +
            "&code_challenge=$encodedChallenge" +
            "&code_challenge_method=S256"
    }

    override suspend fun signInWithOAuthCallback(callbackUrl: String): SharedTagAuthRemoteResult {
        validateCallbackUrl(callbackUrl)
        val params = parseCallbackParams(callbackUrl)
        params.values.firstOrNull { it.size != 1 }?.let {
            throw IOException("OAuth callbackに同じパラメータが複数あります。")
        }
        val errorDescription = singleCallbackParam(params, "error_description")
        val error = singleCallbackParam(params, "error")
        if (error != null || errorDescription != null) {
            val message = errorDescription?.takeIf { it.isNotBlank() }
                ?: error?.takeIf { it.isNotBlank() }
            throw IOException(message ?: "OAuth callbackのエラー情報が不正です。")
        }
        if (params.containsKey("access_token") || params.containsKey("refresh_token")) {
            throw IOException("OAuth callbackにtokenが含まれています。PKCE code callbackだけを受け付けます。")
        }
        val code = singleCallbackParam(params, "code")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Googleサインインの認可コードを受け取れませんでした。")
        val pending = oauthStateStore.load()
            ?: throw IOException("Googleサインインの開始情報が見つかりません。もう一度やり直してください。")
        if (pending.provider == LEGACY_OAUTH_PROVIDER) {
            throw IOException("以前のOAuth開始情報は安全に引き継げません。もう一度Googleサインインを開始してください。")
        }
        if (pending.isExpired()) {
            throw IOException("Googleサインインの有効期限が切れました。もう一度やり直してください。")
        }
        if (pending.provider != FIXED_OAUTH_PROVIDER || pending.redirectTo != AUTH_CALLBACK_URL) {
            throw IOException("Googleサインインのstate検証に失敗しました。")
        }
        val callbackProvider = singleCallbackParam(params, "provider")
        if (callbackProvider != null && callbackProvider != pending.provider) {
            throw IOException("OAuth callbackのproviderが開始時のproviderと一致しません。")
        }
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
        val result = SharedTagAuthRemoteResult.SignedIn(parseSession(requireSession(response)))
        if (!oauthStateStore.consume(pending)) {
            throw IOException("OAuth開始情報が変わったため、Googleサインインを完了できませんでした。もう一度お試しください。")
        }
        return result
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

    fun saveIfAbsent(state: SharedTagOAuthPendingState): Boolean =
        synchronized(SharedTagOAuthStateStoreLock) {
            val existing = load()
            if (existing != null && !existing.isExpired()) {
                false
            } else {
                save(state)
                true
            }
        }

    fun consume(expected: SharedTagOAuthPendingState): Boolean =
        synchronized(SharedTagOAuthStateStoreLock) {
            val existing = load()
            if (existing == null || existing != expected || existing.isExpired()) {
                false
            } else {
                clear()
                true
            }
        }
}

interface SharedTagOAuthStateProtector {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

class AndroidKeystoreSharedTagOAuthStateProtector : SharedTagOAuthStateProtector {
    override fun encrypt(plaintext: String): String = runCatching {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        formatEncryptedBlob(cipher.iv, ciphertext)
    }.getOrElse { error ->
        throw IOException("OAuth開始情報を暗号化できませんでした。", error)
    }

    override fun decrypt(ciphertext: String): String = runCatching {
        val parts = ciphertext.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != ENCRYPTED_BLOB_VERSION) {
            throw IOException("OAuth開始情報の暗号化形式が不正です。")
        }
        val iv = Base64.getUrlDecoder().decode(parts[1])
        if (iv.size != GCM_IV_BYTES) {
            throw IOException("OAuth開始情報の暗号化nonceが不正です。")
        }
        val encrypted = Base64.getUrlDecoder().decode(parts[2])
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrElse { error ->
        if (error is IOException) {
            throw error
        }
        throw IOException("OAuth開始情報を復号できませんでした。再認証してください。", error)
    }

    private fun loadOrCreateKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(OAUTH_STATE_KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                OAUTH_STATE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private val KEYSTORE_LOCK = Any()

        fun formatEncryptedBlob(iv: ByteArray, ciphertext: ByteArray): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return buildString {
                append(ENCRYPTED_BLOB_VERSION)
                append(':')
                append(encoder.encodeToString(iv))
                append(':')
                append(encoder.encodeToString(ciphertext))
            }
        }
    }
}

class SharedPreferencesSharedTagOAuthStateStore(
    context: Context,
    private val protector: SharedTagOAuthStateProtector = AndroidKeystoreSharedTagOAuthStateProtector(),
) : SharedTagOAuthStateStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("shared_tag_oauth_state", Context.MODE_PRIVATE)

    override fun saveIfAbsent(state: SharedTagOAuthPendingState): Boolean =
        synchronized(SharedTagOAuthStateStoreLock) {
            val existing = try {
                load()
            } catch (_: IOException) {
                // A corrupt/backup-restored blob cannot be safely resumed. Replace it only
                // when the user explicitly starts a new OAuth flow.
                clear()
                null
            }
            if (existing != null && !existing.isExpired()) {
                false
            } else {
                save(state)
                true
            }
        }

    override fun save(state: SharedTagOAuthPendingState) {
        val payload = sharedTagOAuthStateJson.encodeToString(state)
        val encryptedPayload = protector.encrypt(payload)
        val committed = prefs.edit()
            .putString(KEY_PENDING_BLOB, encryptedPayload)
            .remove(KEY_VERIFIER)
            .remove(KEY_CHALLENGE)
            .remove(KEY_REDIRECT_TO)
            .remove(KEY_CREATED_AT)
            .commit()
        if (!committed) {
            throw IOException("OAuth開始情報を保存できませんでした。")
        }
    }

    override fun load(): SharedTagOAuthPendingState? {
        prefs.getString(KEY_PENDING_BLOB, null)?.let { payload ->
            if (payload.startsWith("$ENCRYPTED_BLOB_VERSION:")) {
                val decrypted = protector.decrypt(payload)
                return try {
                    sharedTagOAuthStateJson.decodeFromString<SharedTagOAuthPendingState>(decrypted)
                } catch (error: Exception) {
                    throw IOException("OAuth開始情報が壊れています。", error)
                }
            }
            return decodeLegacyBlob(payload)
        }
        val hasLegacyState = LEGACY_KEYS.any(prefs::contains)
        if (!hasLegacyState) return null
        val verifier = prefs.getString(KEY_VERIFIER, null)?.takeIf { it.isNotBlank() }
        val challenge = prefs.getString(KEY_CHALLENGE, null)?.takeIf { it.isNotBlank() }
        val redirectTo = prefs.getString(KEY_REDIRECT_TO, null)?.takeIf { it.isNotBlank() }
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L).takeIf { it > 0L }
        if (verifier == null || challenge == null || redirectTo == null || createdAt == null) {
            throw IOException("OAuth開始情報が旧形式のまま壊れています。再認証してください。")
        }
        return SharedTagOAuthPendingState(
            provider = LEGACY_OAUTH_PROVIDER,
            codeVerifier = verifier,
            codeChallenge = challenge,
            redirectTo = redirectTo,
            createdAtMillis = createdAt,
        )
    }

    override fun clear() {
        if (!prefs.edit().clear().commit()) {
            throw IOException("OAuth開始情報を削除できませんでした。")
        }
    }

    private companion object {
        const val KEY_PENDING_BLOB = "pending_state"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_CHALLENGE = "code_challenge"
        const val KEY_REDIRECT_TO = "redirect_to"
        const val KEY_CREATED_AT = "created_at"
        val LEGACY_KEYS = setOf(KEY_VERIFIER, KEY_CHALLENGE, KEY_REDIRECT_TO, KEY_CREATED_AT)

        fun decodeLegacyBlob(payload: String): SharedTagOAuthPendingState {
            val legacy = try {
                sharedTagOAuthLegacyStateJson.decodeFromString<LegacySharedTagOAuthPendingState>(payload)
            } catch (error: Exception) {
                throw IOException("OAuth開始情報が旧形式または壊れています。再認証してください。", error)
            }
            return SharedTagOAuthPendingState(
                provider = LEGACY_OAUTH_PROVIDER,
                codeVerifier = legacy.codeVerifier,
                codeChallenge = legacy.codeChallenge,
                redirectTo = legacy.redirectTo,
                createdAtMillis = legacy.createdAtMillis,
            )
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

@Serializable
data class SharedTagOAuthPendingState(
    val codeVerifier: String,
    val codeChallenge: String,
    val redirectTo: String,
    val createdAtMillis: Long,
    val provider: String = LEGACY_OAUTH_PROVIDER,
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        provider == LEGACY_OAUTH_PROVIDER || nowMillis - createdAtMillis > EXPIRY_MILLIS

    companion object {
        private const val EXPIRY_MILLIS = 10 * 60 * 1000L

        fun create(provider: String, redirectTo: String): SharedTagOAuthPendingState {
            val verifier = randomUrlSafe(lengthBytes = 64)
            return SharedTagOAuthPendingState(
                provider = provider,
                codeVerifier = verifier,
                codeChallenge = sha256Base64UrlNoPadding(verifier),
                redirectTo = redirectTo,
                createdAtMillis = System.currentTimeMillis(),
            )
        }
    }
}

@Serializable
private data class LegacySharedTagOAuthPendingState(
    val codeVerifier: String,
    val codeChallenge: String,
    val redirectTo: String,
    val createdAtMillis: Long,
)

private val sharedTagOAuthStateJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

private val sharedTagOAuthLegacyStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private fun requireFixedOAuthProvider(provider: String): String {
    if (provider != FIXED_OAUTH_PROVIDER) {
        throw IOException("Googleサインイン以外のOAuth providerは許可されていません。")
    }
    return FIXED_OAUTH_PROVIDER
}

private fun validateCallbackUrl(callbackUrl: String) {
    val uri = runCatching { URI(callbackUrl) }.getOrNull()
        ?: throw IOException("Googleサインインのcallback URLが不正です。")
    val delimiterIndex = listOf(callbackUrl.indexOf('?'), callbackUrl.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull() ?: callbackUrl.length
    if (callbackUrl.substring(0, delimiterIndex) != AUTH_CALLBACK_URL ||
        uri.scheme != "urlsaver" ||
        uri.rawAuthority != "auth" ||
        uri.rawPath != "/callback" ||
        uri.userInfo != null ||
        uri.port != -1
    ) {
        throw IOException("Googleサインインのcallback URLを検証できませんでした。")
    }
}

private fun randomUrlSafe(lengthBytes: Int): String {
    val bytes = ByteArray(lengthBytes)
    try {
        SecureRandom().nextBytes(bytes)
    } catch (error: Exception) {
        throw IOException("OAuth用の安全な乱数を生成できませんでした。", error)
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Base64UrlNoPadding(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun parseCallbackParams(callbackUrl: String): Map<String, List<String>> {
    val uri = runCatching { URI(callbackUrl) }.getOrNull()
        ?: throw IOException("Googleサインインのcallback URLが不正です。")
    val queryStart = callbackUrl.indexOf('?')
    val fragmentStart = callbackUrl.indexOf('#')
    if (queryStart >= 0 && fragmentStart >= 0) {
        throw IOException("OAuth callbackのqueryとfragmentを混在させることはできません。")
    }
    val query = uri.rawQuery.orEmpty()
    val fragment = uri.rawFragment.orEmpty()

    val section = if (query.isNotEmpty()) query else fragment
    val params = linkedMapOf<String, MutableList<String>>()
    section.split('&').forEach { part ->
        if (part.isBlank()) return@forEach
        val index = part.indexOf('=')
        val rawKey = if (index >= 0) part.substring(0, index) else part
        val rawValue = if (index >= 0) part.substring(index + 1) else ""
        val key = decodeCallbackComponent(rawKey)
        if (key.isBlank()) {
            throw IOException("OAuth callbackのパラメータ名が空です。")
        }
        params.getOrPut(key) { mutableListOf() }.add(decodeCallbackComponent(rawValue))
    }
    return params
}

private fun singleCallbackParam(
    params: Map<String, List<String>>,
    name: String,
): String? {
    val values = params[name] ?: return null
    if (values.size != 1) {
        throw IOException("OAuth callbackの${name}が重複しています。")
    }
    return values.single()
}

private fun decodeCallbackComponent(value: String): String {
    return try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (error: IllegalArgumentException) {
        throw IOException("OAuth callbackのパラメータをdecodeできませんでした。", error)
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
