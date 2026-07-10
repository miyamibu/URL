package jp.mimac.urlsaver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MusicVideo
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import jp.mimac.urlsaver.R
import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.theme.OrbitTokens

@Composable
fun ServiceIcon(
    serviceType: ServiceType,
    modifier: Modifier = Modifier,
) {
    val spec = when (serviceType) {
        ServiceType.YOUTUBE -> IconSpec(
            vector = Icons.Outlined.PlayCircle,
            color = Color(0xFFFF3B30),
            desc = "YouTube のアイコン",
        )
        ServiceType.TIKTOK -> IconSpec(
            vector = Icons.Outlined.MusicVideo,
            color = Color(0xFF00B8D4),
            desc = "TikTok のアイコン",
        )
        ServiceType.X -> IconSpec(
            drawableRes = R.drawable.ic_x_logo,
            color = Color(0xFF263238),
            desc = "X のアイコン",
        )
        ServiceType.INSTAGRAM -> IconSpec(
            vector = Icons.Outlined.CameraAlt,
            color = Color(0xFFEC407A),
            desc = "Instagram のアイコン",
        )
        ServiceType.WEB -> IconSpec(
            vector = Icons.Outlined.Language,
            color = Color(0xFF546E7A),
            desc = "Web のアイコン",
        )
        ServiceType.ALL -> IconSpec(
            vector = Icons.Outlined.Apps,
            color = Color(0xFF7E8A9B),
            desc = "すべてのサービス",
        )
    }

    if (spec.drawableRes != null) {
        Icon(
            painter = painterResource(id = spec.drawableRes),
            contentDescription = spec.desc,
            tint = spec.color,
            modifier = modifier.size(24.dp),
        )
    } else {
        Icon(
            imageVector = spec.vector ?: Icons.Outlined.Language,
            contentDescription = spec.desc,
            tint = spec.color,
            modifier = modifier.size(24.dp),
        )
    }
}

@Composable
fun ServiceBadge(
    serviceType: ServiceType,
    badgeImageUrl: String?,
    modifier: Modifier = Modifier,
) {
    val size = 32.dp
    if (badgeImageUrl.isNullOrBlank()) {
        ServiceIcon(serviceType, modifier = modifier.size(size))
        return
    }

    SubcomposeAsyncImage(
        model = badgeImageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(OrbitTokens.panelStrong, CircleShape),
        loading = {
            ServiceIcon(serviceType, modifier = Modifier.size(size))
        },
        error = {
            ServiceIcon(serviceType, modifier = Modifier.size(size))
        },
        success = {
            SubcomposeAsyncImageContent()
        },
    )
}

private data class IconSpec(
    val vector: ImageVector? = null,
    val drawableRes: Int? = null,
    val color: Color,
    val desc: String,
)
