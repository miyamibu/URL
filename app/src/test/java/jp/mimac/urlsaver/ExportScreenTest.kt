package jp.mimac.urlsaver

import jp.mimac.urlsaver.ui.shouldShowSharedTagExportPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportScreenTest {
    @Test
    fun shouldShowSharedTagExportPreset_hidesPresetWhenCloudDisabled() {
        assertFalse(shouldShowSharedTagExportPreset(isSharedTagCloudEnabled = false))
    }

    @Test
    fun shouldShowSharedTagExportPreset_keepsPresetWhenCloudEnabled() {
        assertTrue(shouldShowSharedTagExportPreset(isSharedTagCloudEnabled = true))
    }
}
