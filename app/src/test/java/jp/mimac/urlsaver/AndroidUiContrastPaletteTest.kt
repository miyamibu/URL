package jp.mimac.urlsaver

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import jp.mimac.urlsaver.ui.theme.OrbitTokens
import jp.mimac.urlsaver.ui.theme.SwipeActionTone
import jp.mimac.urlsaver.ui.theme.detailSupportingColor
import jp.mimac.urlsaver.ui.theme.selectableChipPalette
import jp.mimac.urlsaver.ui.theme.selectionBarPalette
import jp.mimac.urlsaver.ui.theme.swipeActionPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class AndroidUiContrastPaletteTest {
    @Test
    fun filterManualAndBatchTagChips_areReadableInEveryThemeAndSelectionState() {
        listOf(lightScheme(), darkScheme()).forEach { scheme ->
            listOf(false, true).forEach { selected ->
                val palette = selectableChipPalette(scheme, selected)
                assertEquals(if (selected) scheme.primary else scheme.surfaceVariant, palette.container)
                assertEquals(if (selected) scheme.onPrimary else scheme.onSurfaceVariant, palette.content)
                assertContrastAtLeast("chip selected=$selected", palette.content, palette.container, 4.5)
            }
        }
    }

    @Test
    fun selectionBarCount_usesReadableFixedDarkPair() {
        val palette = selectionBarPalette()
        assertEquals(OrbitTokens.panelSoft, palette.container)
        assertEquals(OrbitTokens.textMutedStrong, palette.content)
        assertContrastAtLeast("selection count", palette.content, palette.container, 4.5)
    }

    @Test
    fun swipeActions_useExplicitReadableContentColors() {
        val positive = swipeActionPalette(SwipeActionTone.POSITIVE)
        val destructive = swipeActionPalette(SwipeActionTone.DESTRUCTIVE)

        assertEquals(OrbitTokens.secondarySurface, positive.container)
        assertEquals(OrbitTokens.secondary, positive.content)
        assertContrastAtLeast("archive/restore", positive.content, positive.container, 4.5)
        assertEquals(OrbitTokens.dangerSurface, destructive.container)
        assertEquals(OrbitTokens.danger, destructive.content)
        assertContrastAtLeast("delete", destructive.content, destructive.container, 4.5)
    }

    @Test
    fun detailServiceLabel_tracksThemeSupportingColor() {
        listOf(lightScheme(), darkScheme()).forEach { scheme ->
            val content = detailSupportingColor(scheme)
            assertEquals(scheme.onSurfaceVariant, content)
            assertContrastAtLeast("detail service label", content, scheme.surface, 4.5)
        }
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

    private fun lightScheme(): ColorScheme = lightColorScheme(
        primary = Color(0xFF1F6FD1),
        onPrimary = Color.White,
        surface = Color.White,
        onSurface = Color(0xFF102033),
        surfaceVariant = Color(0xFFE7EDF5),
        onSurfaceVariant = Color(0xFF506176),
        outline = Color(0xFFC5D0DD),
    )

    private fun darkScheme(): ColorScheme = darkColorScheme(
        primary = OrbitTokens.primary,
        onPrimary = OrbitTokens.onPrimary,
        surface = OrbitTokens.panel,
        onSurface = OrbitTokens.textPrimary,
        surfaceVariant = OrbitTokens.panelSoft,
        onSurfaceVariant = OrbitTokens.textMutedStrong,
        outline = OrbitTokens.outline,
    )
}
