package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.ShareSaveResult
import jp.mimac.urlsaver.ui.shouldPreserveManualInputAfter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualInputUiContractTest {
    @Test
    fun failedSaveKeepsManualInputAvailableForRetry() {
        assertTrue(shouldPreserveManualInputAfter(ShareSaveResult.SAVE_FAILED))
    }

    @Test
    fun validationAndLimitFailuresKeepManualInputAvailable() {
        assertTrue(shouldPreserveManualInputAfter(ShareSaveResult.INVALID_URL))
        assertTrue(shouldPreserveManualInputAfter(ShareSaveResult.NO_URL_FOUND))
        assertTrue(shouldPreserveManualInputAfter(ShareSaveResult.INPUT_TOO_LARGE))
        assertTrue(shouldPreserveManualInputAfter(ShareSaveResult.PERSONAL_URL_LIMIT_REACHED))
    }

    @Test
    fun terminalSaveResultsCloseAndClearManualInput() {
        assertFalse(shouldPreserveManualInputAfter(ShareSaveResult.CREATED))
        assertFalse(shouldPreserveManualInputAfter(ShareSaveResult.DUPLICATE_ACTIVE))
        assertFalse(shouldPreserveManualInputAfter(ShareSaveResult.DUPLICATE_ARCHIVED))
        assertFalse(shouldPreserveManualInputAfter(ShareSaveResult.RESTORED_FROM_PENDING_DELETE))
    }
}
