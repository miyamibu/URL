package jp.mimac.urlsaver

import jp.mimac.urlsaver.data.PendingInviteRecord
import jp.mimac.urlsaver.data.pendingInviteSavedAt
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingInviteStoreTest {
    @Test
    fun savingTheSameInviteDoesNotExtendItsOriginalTtl() {
        val firstSavedAt = 1_000L
        val existing = PendingInviteRecord("a-token", firstSavedAt)

        assertEquals(
            firstSavedAt,
            pendingInviteSavedAt(existing, "a-token", firstSavedAt + 12 * 60 * 60 * 1_000L),
        )
    }

    @Test
    fun savingANewInviteReplacesThePreviousRecordTimestamp() {
        val secondSavedAt = 2_000L
        val existing = PendingInviteRecord("first-token", 1_000L)

        assertEquals(
            secondSavedAt,
            pendingInviteSavedAt(existing, "second-token", secondSavedAt),
        )
    }
}
