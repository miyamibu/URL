package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.DataStoreEntitlementGrantStore
import jp.mimac.urlsaver.domain.EntitlementGrant
import jp.mimac.urlsaver.domain.EntitlementSource
import jp.mimac.urlsaver.domain.PlanType
import kotlinx.coroutines.test.runTest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntitlementGrantStoreTest {
    @Test
    fun clearLastKnownGrants_removesOnlyMatchingAccount() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DataStoreEntitlementGrantStore(context)
        val userA = "entitlement-user-a-${UUID.randomUUID()}"
        val userB = "entitlement-user-b-${UUID.randomUUID()}"
        val grant = EntitlementGrant(
            planType = PlanType.PRO,
            source = EntitlementSource.ADMIN_GRANT,
            startsAt = 0L,
        )
        store.saveLastKnownGrants(
            authUserId = userA,
            grants = listOf(grant),
            fetchedAtMillis = 1_000L,
        )

        store.clearLastKnownGrants(userB)

        assertEquals(
            listOf(grant),
            store.loadLastKnownGrants(userA, currentTimeMillis = 1_001L),
        )

        store.clearLastKnownGrants(userA)

        assertEquals(
            emptyList<EntitlementGrant>(),
            store.loadLastKnownGrants(userA, currentTimeMillis = 1_001L),
        )
    }

    @Test
    fun accountDeletionInvalidationRejectsLateSaveAfterClear() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staleOperationStore = DataStoreEntitlementGrantStore(context)
        val cleanupStore = DataStoreEntitlementGrantStore(context)
        val userA = "deleted-entitlement-user-${UUID.randomUUID()}"
        val grant = EntitlementGrant(
            planType = PlanType.PROMO_PRO,
            source = EntitlementSource.ADMIN_GRANT,
            startsAt = 0L,
        )

        staleOperationStore.saveLastKnownGrants(userA, listOf(grant), fetchedAtMillis = 999L)
        cleanupStore.clearLastKnownGrants(userA)
        staleOperationStore.saveLastKnownGrants(userA, listOf(grant), fetchedAtMillis = 1_000L)

        assertEquals(
            emptyList<EntitlementGrant>(),
            staleOperationStore.loadLastKnownGrants(userA, currentTimeMillis = 1_001L),
        )
        assertEquals(emptyList<EntitlementGrant>(), staleOperationStore.cachedGrantsSnapshot(userA, 1_001L))
        assertEquals(
            emptyList<EntitlementGrant>(),
            DataStoreEntitlementGrantStore(context).loadLastKnownGrants(userA, currentTimeMillis = 1_001L),
        )
    }
}
