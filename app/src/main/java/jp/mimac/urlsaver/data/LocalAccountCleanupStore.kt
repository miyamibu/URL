package jp.mimac.urlsaver.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalAccountCleanupMarker(
    val aiDataPending: Boolean,
    val sessionPending: Boolean,
)

interface LocalAccountCleanupStore {
    val pending: StateFlow<LocalAccountCleanupMarker?>

    fun save(aiDataPending: Boolean, sessionPending: Boolean)

    fun clear()
}

class SharedPreferencesLocalAccountCleanupStore(
    context: Context,
) : LocalAccountCleanupStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pendingState = MutableStateFlow(loadPending())

    override val pending: StateFlow<LocalAccountCleanupMarker?> = pendingState.asStateFlow()

    override fun save(aiDataPending: Boolean, sessionPending: Boolean) {
        if (!aiDataPending && !sessionPending) {
            clear()
            return
        }
        prefs.edit()
            .putBoolean(KEY_AI_DATA_PENDING, aiDataPending)
            .putBoolean(KEY_SESSION_PENDING, sessionPending)
            .commit()
        pendingState.value = LocalAccountCleanupMarker(aiDataPending, sessionPending)
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_AI_DATA_PENDING)
            .remove(KEY_SESSION_PENDING)
            .commit()
        pendingState.value = null
    }

    private fun loadPending(): LocalAccountCleanupMarker? {
        val aiDataPending = prefs.getBoolean(KEY_AI_DATA_PENDING, false)
        val sessionPending = prefs.getBoolean(KEY_SESSION_PENDING, false)
        return if (aiDataPending || sessionPending) {
            LocalAccountCleanupMarker(aiDataPending, sessionPending)
        } else {
            null
        }
    }

    private companion object {
        const val PREFS_NAME = "local_account_cleanup"
        const val KEY_AI_DATA_PENDING = "ai_data_pending"
        const val KEY_SESSION_PENDING = "session_pending"
    }
}

object NoopLocalAccountCleanupStore : LocalAccountCleanupStore {
    private val pendingState = MutableStateFlow<LocalAccountCleanupMarker?>(null)

    override val pending: StateFlow<LocalAccountCleanupMarker?> = pendingState.asStateFlow()

    override fun save(aiDataPending: Boolean, sessionPending: Boolean) {
        pendingState.value = if (aiDataPending || sessionPending) {
            LocalAccountCleanupMarker(aiDataPending, sessionPending)
        } else {
            null
        }
    }

    override fun clear() {
        pendingState.value = null
    }
}
