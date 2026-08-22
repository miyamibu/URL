package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.AccountOperationFence
import jp.mimac.urlsaver.data.LocalAccountCleanupMarker
import jp.mimac.urlsaver.data.LocalAccountCleanupStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountOperationFenceTest {
    @Test
    fun deletionWaitsForStartedOperationThenBlocksLateWorkWithoutDeadlock() = runTest {
        val cleanupStore = InMemoryCleanupStore()
        val fence = AccountOperationFence(cleanupStore)
        val foregroundStarted = CompletableDeferred<Unit>()
        val releaseForeground = CompletableDeferred<Unit>()
        val deletionEntered = CompletableDeferred<Unit>()

        val foreground = async {
            fence.withAccountOperation(
                authUserId = { "user-a" },
                blockedResult = { "blocked" },
            ) {
                foregroundStarted.complete(Unit)
                releaseForeground.await()
                "completed"
            }
        }
        foregroundStarted.await()
        val deletion = async {
            fence.withExclusiveOperation {
                deletionEntered.complete(Unit)
                fence.markRemoteAccountDeleted("user-a")
                "deleted"
            }
        }
        runCurrent()
        assertFalse(deletionEntered.isCompleted)

        releaseForeground.complete(Unit)
        withTimeout(2_000) {
            assertEquals("completed", foreground.await())
            assertEquals("deleted", deletion.await())
        }

        assertEquals(
            "blocked",
            fence.withAccountOperation(
                authUserId = { "user-a" },
                blockedResult = { "blocked" },
                operation = { "unexpected" },
            ),
        )
    }

    @Test
    fun recreatedFenceUsesDurableCleanupMarkerToRejectOldAccountWork() = runTest {
        val cleanupStore = InMemoryCleanupStore().apply {
            save(
                LocalAccountCleanupMarker(
                    aiDataPending = false,
                    sessionPending = true,
                    authUserId = "user-a",
                ),
            )
        }
        val recreatedFence = AccountOperationFence(cleanupStore)
        var operationRan = false

        val result = recreatedFence.withAccountOperation(
            authUserId = { "user-a" },
            blockedResult = { false },
        ) {
            operationRan = true
            true
        }

        assertFalse(result)
        assertFalse(operationRan)
        assertTrue(cleanupStore.pending.value?.requiresCleanup == true)
    }

    private class InMemoryCleanupStore : LocalAccountCleanupStore {
        private val state = MutableStateFlow<LocalAccountCleanupMarker?>(null)
        override val pending: StateFlow<LocalAccountCleanupMarker?> = state

        override fun save(marker: LocalAccountCleanupMarker) {
            state.value = marker.takeIf(LocalAccountCleanupMarker::requiresCleanup)
        }

        override fun clear() {
            state.value = null
        }
    }
}
