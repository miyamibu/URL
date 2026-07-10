package jp.mimac.urlsaver

import jp.mimac.urlsaver.ui.exportTodayDateInput
import jp.mimac.urlsaver.ui.shouldShowSharedTagExportPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExportScreenTest {
    @Test
    fun exportTodayDateInput_formatsProvidedDateWithoutStaleFixtureDate() {
        assertEquals("2026-07-09", exportTodayDateInput(LocalDate.of(2026, 7, 9)))
    }

    @Test
    fun shouldShowSharedTagExportPreset_hidesPresetWhenCloudDisabled() {
        assertFalse(shouldShowSharedTagExportPreset(isSharedTagCloudEnabled = false))
    }

    @Test
    fun shouldShowSharedTagExportPreset_keepsPresetWhenCloudEnabled() {
        assertTrue(shouldShowSharedTagExportPreset(isSharedTagCloudEnabled = true))
    }
}
