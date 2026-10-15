package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.SharedTagAuthSession
import jp.mimac.urlsaver.data.sharedTagAuthSessionFromJson
import jp.mimac.urlsaver.data.toJson
import jp.mimac.urlsaver.data.withPreservedIdentityProviderSnapshotFrom
import jp.mimac.urlsaver.domain.LinkedIdentityProvider
import jp.mimac.urlsaver.domain.LinkedIdentityProvidersSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedTagAuthSessionCodecTest {
    @Test
    fun legacySessionDecodesWithoutLosingExistingFieldsAndProviderStateIsUnknown() {
        val session = sharedTagAuthSessionFromJson(
            """{"authUserId":"legacy-user","accessToken":"access","refreshToken":"refresh","userEmail":"private@example.com"}""",
        )

        assertEquals("legacy-user", session.authUserId)
        assertEquals("access", session.accessToken)
        assertEquals("refresh", session.refreshToken)
        assertEquals("private@example.com", session.userEmail)
        assertNull(session.linkedIdentityProvidersSnapshot)
    }

    @Test
    fun confirmedProviderSnapshotRoundTripsInsideSessionPayload() {
        val session = SharedTagAuthSession(
            authUserId = "current-user",
            accessToken = "access",
            refreshToken = "refresh",
            userEmail = "private@example.com",
            linkedIdentityProvidersSnapshot = LinkedIdentityProvidersSnapshot(
                authUserId = "current-user",
                providers = setOf(LinkedIdentityProvider.GOOGLE, LinkedIdentityProvider.APPLE),
                verifiedAtEpochMillis = 123L,
            ),
        )

        assertEquals(session, sharedTagAuthSessionFromJson(session.toJson()))
    }

    @Test
    fun snapshotForAnotherUserIsIgnoredWhileSessionRemainsUsable() {
        val session = sharedTagAuthSessionFromJson(
            """{"authUserId":"current-user","accessToken":"access","linkedIdentityProvidersSnapshot":{"authUserId":"other-user","providers":["google"],"verifiedAtEpochMillis":123}}""",
        )

        assertEquals("current-user", session.authUserId)
        assertNull(session.linkedIdentityProvidersSnapshot)
    }

    @Test
    fun tokenRefreshPreservesCachedSnapshotOnlyForTheSameAuthUser() {
        val snapshot = LinkedIdentityProvidersSnapshot(
            authUserId = "current-user",
            providers = setOf(LinkedIdentityProvider.APPLE),
            verifiedAtEpochMillis = 123L,
        )
        val previous = SharedTagAuthSession(
            authUserId = "current-user",
            accessToken = "old-access",
            linkedIdentityProvidersSnapshot = snapshot,
        )

        val refreshed = SharedTagAuthSession("current-user", "new-access")
            .withPreservedIdentityProviderSnapshotFrom(previous)
        val switched = SharedTagAuthSession("other-user", "other-access")
            .withPreservedIdentityProviderSnapshotFrom(previous)

        assertEquals(snapshot, refreshed.linkedIdentityProvidersSnapshot)
        assertNull(switched.linkedIdentityProvidersSnapshot)
    }
}
