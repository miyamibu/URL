package jp.mimac.urlsaver.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PendingInviteRecord(
    val inviteToken: String,
    val savedAt: Long,
)

interface PendingInviteStore {
    fun load(): PendingInviteRecord?
    fun save(inviteToken: String, savedAt: Long = System.currentTimeMillis())
    fun clear()
}

internal fun pendingInviteSavedAt(
    existing: PendingInviteRecord?,
    inviteToken: String,
    requestedSavedAt: Long,
): Long = existing
    ?.takeIf { it.inviteToken == inviteToken }
    ?.savedAt
    ?: requestedSavedAt

class SharedPreferencesPendingInviteStore(context: Context) : PendingInviteStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PendingInviteRecord? {
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
        val token = decrypt(preferences.getString(KEY_ENCRYPTED_INVITE_TOKEN, null))
            ?: migratePlaintextToken(savedAt)
        if (token.isNullOrBlank()) return null
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || age < 0L || age > INVITE_TTL_MILLIS) {
            clear()
            return null
        }
        return PendingInviteRecord(inviteToken = token, savedAt = savedAt)
    }

    override fun save(inviteToken: String, savedAt: Long) {
        if (inviteToken.isBlank()) return
        val encrypted = runCatching { encrypt(inviteToken) }.getOrNull() ?: return
        val effectiveSavedAt = pendingInviteSavedAt(load(), inviteToken, savedAt)
        preferences.edit()
            .putString(KEY_ENCRYPTED_INVITE_TOKEN, encrypted)
            .remove(KEY_INVITE_TOKEN)
            .putLong(KEY_SAVED_AT, effectiveSavedAt)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_ENCRYPTED_INVITE_TOKEN)
            .remove(KEY_INVITE_TOKEN)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "pending_invite"
        const val KEY_INVITE_TOKEN = "invite_token"
        const val KEY_ENCRYPTED_INVITE_TOKEN = "encrypted_invite_token"
        const val KEY_SAVED_AT = "saved_at"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "jp.miyamibu.urlalbum.pending_invite"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val INVITE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    }

    private fun migratePlaintextToken(existingSavedAt: Long): String? {
        val plaintext = preferences.getString(KEY_INVITE_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val encrypted = runCatching { encrypt(plaintext) }.getOrNull() ?: return null
        val migratedSavedAt = existingSavedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        preferences.edit()
            .putString(KEY_ENCRYPTED_INVITE_TOKEN, encrypted)
            .remove(KEY_INVITE_TOKEN)
            .putLong(KEY_SAVED_AT, migratedSavedAt)
            .apply()
        return plaintext
    }

    private fun key(): SecretKey {
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

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv.$payload"
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrNull()
    }
}
