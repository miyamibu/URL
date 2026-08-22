package jp.mimac.urlsaver.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal data class AccessibleContainerPalette(
    val container: Color,
    val content: Color,
    val outline: Color,
)

internal fun selectableChipPalette(
    colorScheme: ColorScheme,
    selected: Boolean,
): AccessibleContainerPalette {
    return if (selected) {
        AccessibleContainerPalette(
            container = colorScheme.primary,
            content = colorScheme.onPrimary,
            outline = colorScheme.primary,
        )
    } else {
        AccessibleContainerPalette(
            container = colorScheme.surfaceVariant,
            content = colorScheme.onSurfaceVariant,
            outline = colorScheme.outline,
        )
    }
}

internal fun selectionBarPalette(): AccessibleContainerPalette {
    return AccessibleContainerPalette(
        container = OrbitTokens.panelSoft,
        content = OrbitTokens.textMutedStrong,
        outline = OrbitTokens.outline,
    )
}

internal enum class SwipeActionTone {
    POSITIVE,
    DESTRUCTIVE,
}

internal fun swipeActionPalette(tone: SwipeActionTone): AccessibleContainerPalette {
    return when (tone) {
        SwipeActionTone.POSITIVE -> AccessibleContainerPalette(
            container = OrbitTokens.secondarySurface,
            content = OrbitTokens.secondary,
            outline = OrbitTokens.secondary,
        )

        SwipeActionTone.DESTRUCTIVE -> AccessibleContainerPalette(
            container = OrbitTokens.dangerSurface,
            content = OrbitTokens.danger,
            outline = OrbitTokens.danger,
        )
    }
}

internal fun detailSupportingColor(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant
