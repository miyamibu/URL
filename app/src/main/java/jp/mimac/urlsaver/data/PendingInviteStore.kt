package jp.mimac.urlsaver.data

import android.content.Context

data class PendingInviteRecord(
    val inviteToken: String,
    val savedAt: Long,
)

interface PendingInviteStore {
    fun load(): PendingInviteRecord?
    fun save(inviteToken: String, savedAt: Long = System.currentTimeMillis())
    fun clear()
}

class SharedPreferencesPendingInviteStore(context: Context) : PendingInviteStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PendingInviteRecord? {
        val token = preferences.getString(KEY_INVITE_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
        return PendingInviteRecord(inviteToken = token, savedAt = savedAt)
    }

    override fun save(inviteToken: String, savedAt: Long) {
        if (inviteToken.isBlank()) return
        preferences.edit()
            .putString(KEY_INVITE_TOKEN, inviteToken)
            .putLong(KEY_SAVED_AT, savedAt)
            .apply()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_INVITE_TOKEN)
            .remove(KEY_SAVED_AT)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "pending_invite"
        const val KEY_INVITE_TOKEN = "invite_token"
        const val KEY_SAVED_AT = "saved_at"
    }
}
