package jp.mimac.urlsaver

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import jp.mimac.urlsaver.ui.components.entryCardPalette
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class EntryCardContrastTest {
    @Test
    fun lightUnselectedCard_usesThemeColorsWithReadableText() {
        assertReadablePalette(lightScheme(), selected = false)
    }

    @Test
    fun lightSelectedCard_usesThemeColorsWithReadableText() {
        assertReadablePalette(lightScheme(), selected = true)
    }

    @Test
    fun darkUnselectedCard_usesThemeColorsWithReadableText() {
        assertReadablePalette(darkScheme(), selected = false)
    }

    @Test
    fun darkSelectedCard_usesThemeColorsWithReadableText() {
        assertReadablePalette(darkScheme(), selected = true)
    }

    private fun assertReadablePalette(
        scheme: androidx.compose.material3.ColorScheme,
        selected: Boolean,
    ) {
        val palette = entryCardPalette(scheme, selected)
        assertEquals(if (selected) scheme.surfaceVariant else scheme.surface, palette.container)
        assertEquals(scheme.onSurface, palette.title)
        assertEquals(scheme.onSurfaceVariant, palette.supporting)
        assertEquals(if (selected) scheme.surface else scheme.surfaceVariant, palette.chipContainer)
        assertEquals(if (selected) scheme.onSurface else scheme.onSurfaceVariant, palette.chipContent)
        assertContrastAtLeast("title", palette.title, palette.container, 4.5)
        assertContrastAtLeast("supporting", palette.supporting, palette.container, 4.5)
        assertContrastAtLeast("local tag", palette.chipContent, palette.chipContainer, 4.5)
    }

    private fun assertContrastAtLeast(label: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$label contrast $ratio must be at least $minimum", ratio >= minimum)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)
    }

    private fun lightScheme() = lightColorScheme(
        primary = Color(0xFF1F6FD1),
        onPrimary = Color.White,
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF102033),
        surfaceVariant = Color(0xFFE7EDF5),
        onSurfaceVariant = Color(0xFF506176),
        outline = Color(0xFFC5D0DD),
    )

    private fun darkScheme() = darkColorScheme(
        primary = OrbitTokens.primary,
        onPrimary = OrbitTokens.onPrimary,
        surface = OrbitTokens.panel,
        onSurface = OrbitTokens.textPrimary,
        surfaceVariant = OrbitTokens.panelSoft,
        onSurfaceVariant = OrbitTokens.textMutedStrong,
        outline = OrbitTokens.outline,
    )
}
