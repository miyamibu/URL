package jp.mimac.urlsaver.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import jp.mimac.urlsaver.domain.LinkedIdentityProvider
import jp.mimac.urlsaver.domain.LinkedIdentityProvidersSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SharedTagAuthSession(
    val authUserId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val userEmail: String? = null,
    val linkedIdentityProvidersSnapshot: LinkedIdentityProvidersSnapshot? = null,
)

interface SharedTagAuthSessionProvider {
    val session: StateFlow<SharedTagAuthSession?>
    fun updateSession(newSession: SharedTagAuthSession?)

    fun linkedIdentityProvidersSnapshotFor(authUserId: String): LinkedIdentityProvidersSnapshot? =
        session.value?.linkedIdentityProvidersSnapshot?.takeIf { it.authUserId == authUserId }

    fun updateLinkedIdentityProvidersSnapshot(
        snapshot: LinkedIdentityProvidersSnapshot,
        expectedAccessToken: String,
    ): Boolean {
        val current = session.value?.takeIf {
            it.authUserId == snapshot.authUserId && it.accessToken == expectedAccessToken
        } ?: return false
        updateSession(current.copy(linkedIdentityProvidersSnapshot = snapshot))
        return true
    }
}

class SharedPreferencesSharedTagAuthSessionProvider(
    context: Context,
) : SharedTagAuthSessionProvider {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStorage = AndroidKeystoreSharedTagAuthStorage()
    private val sessionState = MutableStateFlow(loadSession())
    private var storedIdentityProvidersSnapshot = sessionState.value?.linkedIdentityProvidersSnapshot

    override val session: StateFlow<SharedTagAuthSession?> = sessionState.asStateFlow()

    @Synchronized
    override fun updateSession(newSession: SharedTagAuthSession?) {
        if (newSession == null) {
            prefs.edit()
                .remove(KEY_AUTH_USER_ID)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_ENCRYPTED_SESSION)
                .apply()
            storedIdentityProvidersSnapshot = null
            sessionState.value = null
        } else {
            val snapshot = newSession.linkedIdentityProvidersSnapshot
                ?.takeIf { it.authUserId == newSession.authUserId }
                ?: storedIdentityProvidersSnapshot?.takeIf { it.authUserId == newSession.authUserId }
            val persistedSession = newSession.copy(linkedIdentityProvidersSnapshot = snapshot)
            persistSession(persistedSession)
            storedIdentityProvidersSnapshot = snapshot
            sessionState.value = persistedSession
        }
    }

    @Synchronized
    override fun linkedIdentityProvidersSnapshotFor(authUserId: String): LinkedIdentityProvidersSnapshot? {
        if (sessionState.value?.authUserId != authUserId) return null
        return storedIdentityProvidersSnapshot?.takeIf { it.authUserId == authUserId }
    }

    @Synchronized
    override fun updateLinkedIdentityProvidersSnapshot(
        snapshot: LinkedIdentityProvidersSnapshot,
        expectedAccessToken: String,
    ): Boolean {
        val currentSession = sessionState.value?.takeIf {
            it.authUserId == snapshot.authUserId && it.accessToken == expectedAccessToken
        } ?: return false
        val persistedSession = currentSession.copy(linkedIdentityProvidersSnapshot = snapshot)
        return runCatching {
            persistSession(persistedSession)
            storedIdentityProvidersSnapshot = snapshot
        }.isSuccess
    }

    private fun persistSession(session: SharedTagAuthSession) {
        val encryptedSession = secureStorage.encrypt(session.toJson())
        prefs.edit()
            .putString(KEY_ENCRYPTED_SESSION, encryptedSession)
            .remove(KEY_AUTH_USER_ID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_EMAIL)
            .apply()
    }

    private fun loadSession(): SharedTagAuthSession? {
        prefs.getString(KEY_ENCRYPTED_SESSION, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { encrypted ->
                runCatching { sharedTagAuthSessionFromJson(secureStorage.decrypt(encrypted)) }
                    .getOrNull()
                    ?.let { return it }
            }

        val authUserId = prefs.getString(KEY_AUTH_USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
        val userEmail = prefs.getString(KEY_USER_EMAIL, null)?.takeIf { it.isNotBlank() }
        val migratedSession = SharedTagAuthSession(
            authUserId = authUserId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            userEmail = userEmail,
        )
        runCatching { persistSession(migratedSession) }
        return migratedSession
    }

    private companion object {
        const val PREFS_NAME = "shared_tag_auth_session"
        const val KEY_ENCRYPTED_SESSION = "encrypted_session_v1"
        const val KEY_AUTH_USER_ID = "auth_user_id"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_EMAIL = "user_email"
    }
}

private class AndroidKeystoreSharedTagAuthStorage {
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$payload"
    }

    fun decrypt(encryptedText: String): String {
        val parts = encryptedText.split(":", limit = 2)
        require(parts.size == 2) { "Invalid encrypted auth payload." }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let {
            return it
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "urlsaver_shared_tag_auth_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal fun SharedTagAuthSession.toJson(): String {
    val snapshot = linkedIdentityProvidersSnapshot?.takeIf { it.authUserId == authUserId }
    return JSONObject()
        .put("authUserId", authUserId)
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("userEmail", userEmail)
        .put("linkedIdentityProvidersSnapshot", snapshot?.let { it.toJson() })
        .toString()
}

internal fun sharedTagAuthSessionFromJson(json: String): SharedTagAuthSession {
    val payload = JSONObject(json)
    val authUserId = payload.getString("authUserId")
    return SharedTagAuthSession(
        authUserId = authUserId,
        accessToken = payload.getString("accessToken"),
        refreshToken = payload.optString("refreshToken").takeIf { it.isNotBlank() && it != "null" },
        userEmail = payload.optString("userEmail").takeIf { it.isNotBlank() && it != "null" },
        linkedIdentityProvidersSnapshot = payload.optJSONObject("linkedIdentityProvidersSnapshot")
            ?.let { runCatching { it.toDomain(authUserId) }.getOrNull() },
    )
}

private fun LinkedIdentityProvidersSnapshot.toJson(): JSONObject = JSONObject()
    .put("authUserId", authUserId)
    .put("providers", JSONArray().apply { providers.forEach { put(it.supabaseValue) } })
    .put("verifiedAtEpochMillis", verifiedAtEpochMillis)

private fun JSONObject.toDomain(sessionAuthUserId: String): LinkedIdentityProvidersSnapshot? {
    val snapshotAuthUserId = optString("authUserId").takeIf(String::isNotBlank) ?: return null
    if (snapshotAuthUserId != sessionAuthUserId) return null
    val providerArray = optJSONArray("providers") ?: return null
    val providers = buildSet {
        for (index in 0 until providerArray.length()) {
            when (providerArray.optString(index)) {
                LinkedIdentityProvider.GOOGLE.supabaseValue -> add(LinkedIdentityProvider.GOOGLE)
                LinkedIdentityProvider.APPLE.supabaseValue -> add(LinkedIdentityProvider.APPLE)
            }
        }
    }
    val verifiedAt = optLong("verifiedAtEpochMillis", -1L)
    if (verifiedAt < 0L) return null
    return LinkedIdentityProvidersSnapshot(snapshotAuthUserId, providers, verifiedAt)
}

internal fun SharedTagAuthSession.withPreservedIdentityProviderSnapshotFrom(
    previousSession: SharedTagAuthSession,
): SharedTagAuthSession = copy(
    linkedIdentityProvidersSnapshot = previousSession.linkedIdentityProvidersSnapshot
        ?.takeIf { it.authUserId == authUserId && previousSession.authUserId == authUserId },
)
