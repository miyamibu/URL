package jp.mimac.urlsaver.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalAccountCleanupMarker(
    val aiDataPending: Boolean,
    val sessionPending: Boolean,
    val syncWorkCancellationPending: Boolean = false,
    val sharedDataCleanupPending: Boolean = false,
    val pendingInviteCleanupPending: Boolean = false,
    val entitlementCleanupPending: Boolean = false,
    val personalLinkSettingsCleanupPending: Boolean = false,
    val authUserId: String? = null,
) {
    val accountDataPending: Boolean
        get() = syncWorkCancellationPending ||
            sharedDataCleanupPending ||
            entitlementCleanupPending ||
            personalLinkSettingsCleanupPending

    val requiresCleanup: Boolean
        get() = aiDataPending || sessionPending || accountDataPending
}

interface LocalAccountCleanupStore {
    val pending: StateFlow<LocalAccountCleanupMarker?>

    fun save(marker: LocalAccountCleanupMarker)

    fun clear()
}

class SharedPreferencesLocalAccountCleanupStore(
    context: Context,
) : LocalAccountCleanupStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pendingState = MutableStateFlow(loadPending())

    override val pending: StateFlow<LocalAccountCleanupMarker?> = pendingState.asStateFlow()

    override fun save(marker: LocalAccountCleanupMarker) {
        if (!marker.requiresCleanup) {
            clear()
            return
        }
        val normalizedAuthUserId = marker.authUserId?.trim()?.takeIf { it.isNotEmpty() }
        require(!marker.accountDataPending || normalizedAuthUserId != null) {
            "authUserId is required while account-linked cleanup is pending"
        }
        val savedMarker = marker.copy(
            // Pending invites are not scoped to an authenticated account. Never persist this
            // legacy stage as part of deleting one account.
            pendingInviteCleanupPending = false,
            authUserId = normalizedAuthUserId,
        )
        val committed = prefs.edit()
            .putBoolean(KEY_AI_DATA_PENDING, savedMarker.aiDataPending)
            .putBoolean(KEY_SESSION_PENDING, savedMarker.sessionPending)
            .putBoolean(KEY_SYNC_WORK_CANCELLATION_PENDING, savedMarker.syncWorkCancellationPending)
            .putBoolean(KEY_SHARED_DATA_CLEANUP_PENDING, savedMarker.sharedDataCleanupPending)
            .putBoolean(KEY_PENDING_INVITE_CLEANUP_PENDING, false)
            .putBoolean(KEY_ENTITLEMENT_CLEANUP_PENDING, savedMarker.entitlementCleanupPending)
            .putBoolean(KEY_PERSONAL_LINK_SETTINGS_CLEANUP_PENDING, savedMarker.personalLinkSettingsCleanupPending)
            .putBoolean(KEY_ACCOUNT_DATA_PENDING, savedMarker.accountDataPending)
            .apply {
                if (normalizedAuthUserId == null) {
                    remove(KEY_AUTH_USER_ID)
                } else {
                    putString(KEY_AUTH_USER_ID, normalizedAuthUserId)
                }
            }
            .commit()
        check(committed) { "Could not persist the local account cleanup marker" }
        pendingState.value = savedMarker
    }

    override fun clear() {
        val committed = prefs.edit()
            .remove(KEY_AI_DATA_PENDING)
            .remove(KEY_SESSION_PENDING)
            .remove(KEY_SYNC_WORK_CANCELLATION_PENDING)
            .remove(KEY_SHARED_DATA_CLEANUP_PENDING)
            .remove(KEY_PENDING_INVITE_CLEANUP_PENDING)
            .remove(KEY_ENTITLEMENT_CLEANUP_PENDING)
            .remove(KEY_PERSONAL_LINK_SETTINGS_CLEANUP_PENDING)
            .remove(KEY_ACCOUNT_DATA_PENDING)
            .remove(KEY_AUTH_USER_ID)
            .commit()
        check(committed) { "Could not clear the local account cleanup marker" }
        pendingState.value = null
    }

    private fun loadPending(): LocalAccountCleanupMarker? {
        val aiDataPending = prefs.getBoolean(KEY_AI_DATA_PENDING, false)
        val sessionPending = prefs.getBoolean(KEY_SESSION_PENDING, false)
        val legacyAccountDataPending = prefs.getBoolean(KEY_ACCOUNT_DATA_PENDING, false)
        val syncWorkCancellationPending = pendingValue(
            KEY_SYNC_WORK_CANCELLATION_PENDING,
            legacyAccountDataPending,
        )
        val sharedDataCleanupPending = pendingValue(
            KEY_SHARED_DATA_CLEANUP_PENDING,
            legacyAccountDataPending,
        )
        // Pending invites can be received while signed out and have no account owner binding.
        // An account deletion must not erase an unowned or another account's invite intent.
        val pendingInviteCleanupPending = false
        val entitlementCleanupPending = pendingValue(
            KEY_ENTITLEMENT_CLEANUP_PENDING,
            legacyAccountDataPending,
        )
        val personalLinkSettingsCleanupPending = pendingValue(
            KEY_PERSONAL_LINK_SETTINGS_CLEANUP_PENDING,
            legacyAccountDataPending,
        )
        val authUserId = prefs.getString(KEY_AUTH_USER_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        return LocalAccountCleanupMarker(
            aiDataPending = aiDataPending,
            sessionPending = sessionPending,
            syncWorkCancellationPending = syncWorkCancellationPending,
            sharedDataCleanupPending = sharedDataCleanupPending,
            pendingInviteCleanupPending = pendingInviteCleanupPending,
            entitlementCleanupPending = entitlementCleanupPending,
            personalLinkSettingsCleanupPending = personalLinkSettingsCleanupPending,
            authUserId = authUserId,
        ).takeIf(LocalAccountCleanupMarker::requiresCleanup)
    }

    private fun pendingValue(key: String, legacyValue: Boolean): Boolean {
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else legacyValue
    }

    private companion object {
        const val PREFS_NAME = "local_account_cleanup"
        const val KEY_AI_DATA_PENDING = "ai_data_pending"
        const val KEY_SESSION_PENDING = "session_pending"
        const val KEY_SYNC_WORK_CANCELLATION_PENDING = "sync_work_cancellation_pending"
        const val KEY_SHARED_DATA_CLEANUP_PENDING = "shared_data_cleanup_pending"
        const val KEY_PENDING_INVITE_CLEANUP_PENDING = "pending_invite_cleanup_pending"
        const val KEY_ENTITLEMENT_CLEANUP_PENDING = "entitlement_cleanup_pending"
        const val KEY_PERSONAL_LINK_SETTINGS_CLEANUP_PENDING = "personal_link_settings_cleanup_pending"
        const val KEY_ACCOUNT_DATA_PENDING = "account_data_pending"
        const val KEY_AUTH_USER_ID = "auth_user_id"
    }
}

object NoopLocalAccountCleanupStore : LocalAccountCleanupStore {
    private val pendingState = MutableStateFlow<LocalAccountCleanupMarker?>(null)

    override val pending: StateFlow<LocalAccountCleanupMarker?> = pendingState.asStateFlow()

    override fun save(marker: LocalAccountCleanupMarker) {
        pendingState.value = marker.takeIf(LocalAccountCleanupMarker::requiresCleanup)
    }

    override fun clear() {
        pendingState.value = null
    }
}
