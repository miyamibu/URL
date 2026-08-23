package jp.mimac.urlsaver.ui

import jp.mimac.urlsaver.ui.components.cappedOrbitFontScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBottomBarLayoutTest {
    @Test
    fun normalModeStartsAt360DpWithStandardFontScale() {
        assertFalse(shouldUseExpandedMainBottomBar(maxWidthDp = 360f, fontScale = 1f))
        assertEquals(76f, mainBottomBarContentHeightDp(maxWidthDp = 360f, fontScale = 1f), 0.001f)
    }

    @Test
    fun widthBelow360DpUsesExpandedMode() {
        assertTrue(shouldUseExpandedMainBottomBar(maxWidthDp = 359.9f, fontScale = 1f))
        assertEquals(98f, mainBottomBarContentHeightDp(maxWidthDp = 359.9f, fontScale = 1f), 0.001f)
    }

    @Test
    fun fontScaleAtOrAbove130PercentUsesExpandedMode() {
        assertTrue(shouldUseExpandedMainBottomBar(maxWidthDp = 390f, fontScale = 1.3f))
        assertEquals(110.6f, mainBottomBarContentHeightDp(maxWidthDp = 390f, fontScale = 1.3f), 0.001f)
    }

    @Test
    fun expandedHeightGrowsFor200And400PercentFontScale() {
        val heightAt200Percent = mainBottomBarContentHeightDp(maxWidthDp = 390f, fontScale = 2f)
        val heightAt400Percent = mainBottomBarContentHeightDp(maxWidthDp = 390f, fontScale = 4f)

        assertEquals(140f, heightAt200Percent, 0.001f)
        assertEquals(140f, heightAt400Percent, 0.001f)
        assertEquals(heightAt200Percent, heightAt400Percent, 0.001f)
    }

    @Test
    fun cappedOrbitFontScalePreservesBelowMax() {
        assertEquals(1.0f, cappedOrbitFontScale(1.0f, 2.0f), 0.001f)
        assertEquals(1.3f, cappedOrbitFontScale(1.3f, 2.0f), 0.001f)
        assertEquals(2.0f, cappedOrbitFontScale(2.0f, 2.0f), 0.001f)
    }

    @Test
    fun cappedOrbitFontScaleCapsAboveMax() {
        assertEquals(2.0f, cappedOrbitFontScale(4.0f, 2.0f), 0.001f)
    }

    @Test
    fun cappedOrbitFontScaleWithCustomMax() {
        assertEquals(1.3f, cappedOrbitFontScale(2.0f, 1.3f), 0.001f)
        assertEquals(1.3f, cappedOrbitFontScale(4.0f, 1.3f), 0.001f)
    }
}
