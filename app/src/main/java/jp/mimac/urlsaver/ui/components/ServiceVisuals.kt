package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import jp.mimac.urlsaver.domain.ServiceType

@Composable
fun ServiceIcon(
    serviceType: ServiceType,
    modifier: Modifier = Modifier,
) {
    val (icon, color, desc) = when (serviceType) {
        ServiceType.YOUTUBE -> Triple(Icons.Outlined.PlayCircle, Color(0xFFE53935), "YouTube のアイコン")
        ServiceType.TIKTOK -> Triple(Icons.Outlined.MusicNote, Color(0xFF00ACC1), "TikTok のアイコン")
        ServiceType.X -> Triple(Icons.Outlined.AlternateEmail, Color(0xFF263238), "X のアイコン")
        ServiceType.INSTAGRAM -> Triple(Icons.Outlined.CameraAlt, Color(0xFFEC407A), "Instagram のアイコン")
        ServiceType.WEB -> Triple(Icons.Outlined.Link, Color(0xFF546E7A), "Webサイトのアイコン")
        ServiceType.ALL -> Triple(Icons.Outlined.Link, Color(0xFF546E7A), "リンクのアイコン")
    }

    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = color,
        modifier = modifier.size(24.dp),
    )
}
