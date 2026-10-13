package jp.mimac.urlsaver.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import jp.mimac.urlsaver.domain.TagColorChoice

internal fun tagColorChipPalette(
    colorScheme: ColorScheme,
    choice: TagColorChoice,
    selected: Boolean,
): AccessibleContainerPalette {
    if (choice == TagColorChoice.DEFAULT) {
        return selectableChipPalette(colorScheme, selected)
    }

    val pastel = Color(choice.argb ?: return selectableChipPalette(colorScheme, selected))
    return if (colorScheme.background.luminance() < 0.3f) {
        AccessibleContainerPalette(
            container = lerp(colorScheme.surfaceVariant, pastel, 0.2f),
            content = colorScheme.onSurface,
            outline = if (selected) colorScheme.primary else pastel.copy(alpha = 0.72f),
        )
    } else {
        AccessibleContainerPalette(
            container = pastel,
            content = Color(0xFF38354A),
            outline = if (selected) colorScheme.primary else lerp(pastel, Color(0xFF38354A), 0.24f),
        )
    }
}

internal fun tagColorSwatch(choice: TagColorChoice, colorScheme: ColorScheme): Color {
    return choice.argb?.let(::Color) ?: colorScheme.surfaceVariant
}
