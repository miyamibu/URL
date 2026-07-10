package jp.mimac.urlsaver.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

object OrbitTokens {
    val background = Color(0xFF05080D)
    val backgroundSoft = Color(0xFF0A1018)
    val surface = Color(0xFF0C121A)
    val panel = Color(0xFF121A24)
    val panelSoft = Color(0xFF171F2A)
    val panelStrong = Color(0xFF1A2230)
    val outline = Color(0xFF273446)
    val outlineStrong = Color(0xFF34465D)
    val textPrimary = Color(0xFFEFF4FF)
    val textMuted = Color(0xFF8FA3C1)
    val textMutedStrong = Color(0xFFA9B8D1)

    val primary = Color(0xFF67B0FF)
    val primaryStrong = Color(0xFF4A94E8)
    val primarySurface = Color(0xFF203C61)
    val primarySoftSurface = Color(0xFF17314D)
    val onPrimary = Color(0xFF08111D)

    val secondary = Color(0xFF78F0D1)
    val secondarySurface = Color(0xFF14342E)
    val onSecondary = Color(0xFFE8FFF9)

    val danger = Color(0xFFFF6A5F)
    val dangerSurface = Color(0xFF3B1818)
    val onDanger = Color(0xFFFFD6D1)

    val metadataPending = primary
    val metadataReady = secondary
    val metadataFailed = Color(0xFFFFB74D)
    val metadataUnavailable = Color(0xFF8A96A8)

    val serviceAccentWeb = Color(0xFF34C759)
    val serviceAccentVideo = Color(0xFFFF3B30)
    val serviceAccentInstagram = Color(0xFFE4405F)
    val serviceAccentX = Color(0xFF98A7B8)

    val segmentedInactiveText = textMutedStrong.copy(alpha = 0.86f)

    val radiusPanel = 28.dp
    val radiusButton = 22.dp
    val radiusChip = 18.dp
    val radiusIcon = 20.dp
    val radiusCardMedia = 24.dp

    val screenHorizontalPadding = 16.dp
    val contentMaxWidth = 760.dp
    val sectionSpacing = 12.dp
}
