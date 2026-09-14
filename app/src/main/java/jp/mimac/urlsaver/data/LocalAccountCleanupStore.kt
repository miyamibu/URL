package jp.mimac.urlsaver.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalAccountCleanupMarker(
    val aiDataPending: Boolean,
    val sessionPending: Boolean,
    val accountDataPending: Boolean = false,
    val authUserId: String? = null,
)

interface LocalAccountCleanupStore {
    val pending: StateFlow<LocalAccountCleanupMarker?>

    fun save(
        aiDataPending: Boolean,
        sessionPending: Boolean,
        accountDataPending: Boolean = false,
        authUserId: String? = null,
    )

    fun clear()
}

class SharedPreferencesLocalAccountCleanupStore(
    context: Context,
) : LocalAccountCleanupStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pendingState = MutableStateFlow(loadPending())

    override val pending: StateFlow<LocalAccountCleanupMarker?> = pendingState.asStateFlow()

    override fun save(
        aiDataPending: Boolean,
        sessionPending: Boolean,
        accountDataPending: Boolean,
        authUserId: String?,
    ) {
        if (!aiDataPending && !sessionPending && !accountDataPending) {
            clear()
            return
        }
        val normalizedAuthUserId = authUserId?.trim()?.takeIf { it.isNotEmpty() }
        require(!accountDataPending || normalizedAuthUserId != null) {
            "authUserId is required while account-linked cleanup is pending"
        }
        prefs.edit()
            .putBoolean(KEY_AI_DATA_PENDING, aiDataPending)
            .putBoolean(KEY_SESSION_PENDING, sessionPending)
            .putBoolean(KEY_ACCOUNT_DATA_PENDING, accountDataPending)
            .apply {
                if (normalizedAuthUserId == null) {
                    remove(KEY_AUTH_USER_ID)
                } else {
                    putString(KEY_AUTH_USER_ID, normalizedAuthUserId)
                }
            }
            .commit()
        pendingState.value = LocalAccountCleanupMarker(
            aiDataPending = aiDataPending,
            sessionPending = sessionPending,
            accountDataPending = accountDataPending,
            authUserId = normalizedAuthUserId,
        )
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_AI_DATA_PENDING)
            .remove(KEY_SESSION_PENDING)
            .remove(KEY_ACCOUNT_DATA_PENDING)
            .remove(KEY_AUTH_USER_ID)
            .commit()
        pendingState.value = null
    }

    private fun loadPending(): LocalAccountCleanupMarker? {
        val aiDataPending = prefs.getBoolean(KEY_AI_DATA_PENDING, false)
        val sessionPending = prefs.getBoolean(KEY_SESSION_PENDING, false)
        val accountDataPending = prefs.getBoolean(KEY_ACCOUNT_DATA_PENDING, false)
        val authUserId = prefs.getString(KEY_AUTH_USER_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        return if (aiDataPending || sessionPending || accountDataPending) {
            LocalAccountCleanupMarker(
                aiDataPending = aiDataPending,
                sessionPending = sessionPending,
                accountDataPending = accountDataPending,
                authUserId = authUserId,
            )
        } else {
            null
        }
    }

    private companion object {
        const val PREFS_NAME = "local_account_cleanup"
        const val KEY_AI_DATA_PENDING = "ai_data_pending"
        const val KEY_SESSION_PENDING = "session_pending"
        const val KEY_ACCOUNT_DATA_PENDING = "account_data_pending"
        const val KEY_AUTH_USER_ID = "auth_user_id"
    }
}

object NoopLocalAccountCleanupStore : LocalAccountCleanupStore {
    private val pendingState = MutableStateFlow<LocalAccountCleanupMarker?>(null)

    override val pending: StateFlow<LocalAccountCleanupMarker?> = pendingState.asStateFlow()

    override fun save(
        aiDataPending: Boolean,
        sessionPending: Boolean,
        accountDataPending: Boolean,
        authUserId: String?,
    ) {
        pendingState.value = if (aiDataPending || sessionPending || accountDataPending) {
            LocalAccountCleanupMarker(
                aiDataPending = aiDataPending,
                sessionPending = sessionPending,
                accountDataPending = accountDataPending,
                authUserId = authUserId,
            )
        } else {
            null
        }
    }

    override fun clear() {
        pendingState.value = null
    }
}
