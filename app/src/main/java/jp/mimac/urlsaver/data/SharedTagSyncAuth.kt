package jp.mimac.urlsaver.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

interface SharedTagAuthSessionProvider {
    val session: StateFlow<SharedTagAuthSession?>
    fun updateSession(newSession: SharedTagAuthSession?)
}

class SharedPreferencesSharedTagAuthSessionProvider(
    context: Context,
) : SharedTagAuthSessionProvider {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStorage = AndroidKeystoreSharedTagAuthStorage()
    private val sessionState = MutableStateFlow(loadSession())

    override val session: StateFlow<SharedTagAuthSession?> = sessionState.asStateFlow()

    override fun updateSession(newSession: SharedTagAuthSession?) {
        if (newSession == null) {
            prefs.edit()
                .remove(KEY_AUTH_USER_ID)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_ENCRYPTED_SESSION)
                .apply()
        } else {
            val encryptedSession = secureStorage.encrypt(newSession.toJson())
            prefs.edit()
                .putString(KEY_ENCRYPTED_SESSION, encryptedSession)
                .remove(KEY_AUTH_USER_ID)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_EMAIL)
                .apply()
        }
        sessionState.value = newSession
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
        runCatching {
            val encryptedSession = secureStorage.encrypt(migratedSession.toJson())
            prefs.edit()
                .putString(KEY_ENCRYPTED_SESSION, encryptedSession)
                .remove(KEY_AUTH_USER_ID)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_EMAIL)
                .apply()
        }
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

private fun SharedTagAuthSession.toJson(): String {
    return JSONObject()
        .put("authUserId", authUserId)
        .put("accessToken", accessToken)
        .put("refreshToken", refreshToken)
        .put("userEmail", userEmail)
        .toString()
}

private fun sharedTagAuthSessionFromJson(json: String): SharedTagAuthSession {
    val payload = JSONObject(json)
    return SharedTagAuthSession(
        authUserId = payload.getString("authUserId"),
        accessToken = payload.getString("accessToken"),
        refreshToken = payload.optString("refreshToken").takeIf { it.isNotBlank() && it != "null" },
        userEmail = payload.optString("userEmail").takeIf { it.isNotBlank() && it != "null" },
    )
}
