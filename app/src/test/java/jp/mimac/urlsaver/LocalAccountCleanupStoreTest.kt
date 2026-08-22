package jp.mimac.urlsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jp.mimac.urlsaver.data.LocalAccountCleanupMarker
import jp.mimac.urlsaver.data.SharedPreferencesLocalAccountCleanupStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalAccountCleanupStoreTest {
    @Test
    fun deletionStagesAndAuthUserIdSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstStore = SharedPreferencesLocalAccountCleanupStore(context)
        firstStore.clear()
        val marker = LocalAccountCleanupMarker(
            aiDataPending = true,
            sessionPending = false,
            syncWorkCancellationPending = true,
            sharedDataCleanupPending = true,
            pendingInviteCleanupPending = true,
            entitlementCleanupPending = true,
            personalLinkSettingsCleanupPending = true,
            authUserId = "deleted-account-user",
        )

        firstStore.save(marker)
        val recreatedStore = SharedPreferencesLocalAccountCleanupStore(context)

        assertEquals(marker.copy(pendingInviteCleanupPending = false), recreatedStore.pending.value)
        recreatedStore.clear()
        assertNull(SharedPreferencesLocalAccountCleanupStore(context).pending.value)
    }
}
