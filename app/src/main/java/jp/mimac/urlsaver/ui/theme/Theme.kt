package jp.mimac.urlsaver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val lightColorPalette = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF1F6FD1),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    secondary = androidx.compose.ui.graphics.Color(0xFF00695C),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    background = androidx.compose.ui.graphics.Color(0xFFF4F7FB),
    onBackground = androidx.compose.ui.graphics.Color(0xFF102033),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF102033),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7EDF5),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF506176),
    outline = androidx.compose.ui.graphics.Color(0xFFC5D0DD),
    error = androidx.compose.ui.graphics.Color(0xFFB3261E),
    onError = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
)

private val darkColorPalette = darkColorScheme(
    primary = OrbitTokens.primary,
    onPrimary = OrbitTokens.onPrimary,
    secondary = OrbitTokens.secondary,
    onSecondary = OrbitTokens.onSecondary,
    background = OrbitTokens.background,
    onBackground = OrbitTokens.textPrimary,
    surface = OrbitTokens.panel,
    onSurface = OrbitTokens.textPrimary,
    surfaceVariant = OrbitTokens.panelSoft,
    onSurfaceVariant = OrbitTokens.textMutedStrong,
    outline = OrbitTokens.outline,
    error = OrbitTokens.danger,
    onError = OrbitTokens.onDanger,
)

private val titleFontFamily = FontFamily.SansSerif
private val bodyFontFamily = FontFamily.SansSerif

private val appTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        displayMedium = displayMedium.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        displaySmall = displaySmall.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = titleFontFamily, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal),
        bodyMedium = bodyMedium.copy(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal),
        bodySmall = bodySmall.copy(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal),
        labelLarge = labelLarge.copy(fontFamily = bodyFontFamily, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = bodyFontFamily, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = bodyFontFamily, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun UrlSaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorPalette else lightColorPalette,
        typography = appTypography,
        content = content,
    )
}
