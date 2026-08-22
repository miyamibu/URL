package jp.mimac.urlsaver.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes account-scoped foreground work with account deletion.
 *
 * The durable cleanup marker is the process-recreation fence. The in-memory set closes the
 * smaller window after the remote account has been deleted but before the first marker write
 * has completed. A process crash in that server-to-marker window still requires a server-side
 * idempotency/status contract; callers must not interpret an auth failure as delete success.
 */
class AccountOperationFence(
    private val localAccountCleanupStore: LocalAccountCleanupStore = NoopLocalAccountCleanupStore,
) {
    private val operationMutex = Mutex()
    private val deletedAuthUserIds = mutableSetOf<String>()

    suspend fun <T> withAccountOperation(
        authUserId: () -> String?,
        blockedResult: () -> T,
        operation: suspend () -> T,
    ): T = operationMutex.withLock {
        val currentAuthUserId = authUserId()?.trim()?.takeIf(String::isNotEmpty)
        if (currentAuthUserId != null && isBlocked(currentAuthUserId)) {
            blockedResult()
        } else {
            operation()
        }
    }

    suspend fun <T> withExclusiveOperation(operation: suspend () -> T): T =
        operationMutex.withLock { operation() }

    /** Must be called before persisting the first post-remote-delete cleanup marker. */
    fun markRemoteAccountDeleted(authUserId: String) {
        val normalized = authUserId.trim()
        require(normalized.isNotEmpty())
        deletedAuthUserIds += normalized
    }

    fun releaseAfterRemoteDeleteFailure(authUserId: String) {
        deletedAuthUserIds -= authUserId.trim()
    }

    private fun isBlocked(authUserId: String): Boolean {
        if (authUserId in deletedAuthUserIds) return true
        val pending = localAccountCleanupStore.pending.value ?: return false
        return pending.authUserId?.trim() == authUserId && pending.requiresCleanup
    }
}
