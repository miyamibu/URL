package jp.mimac.urlsaver.ui.theme

import androidx.compose.ui.graphics.Color
import jp.mimac.urlsaver.domain.HomeBackgroundStyle

internal fun homeBackgroundGradientColors(style: HomeBackgroundStyle): List<Color> {
    return when (style) {
        HomeBackgroundStyle.WARM_BEIGE -> listOf(
            Color(0xFFF7F3EC),
            Color(0xFFEDE7DF),
            Color(0xFFE9E1D7),
        )
        HomeBackgroundStyle.PASTEL_BLUSH -> listOf(
            Color(0xFFFCF3F6),
            Color(0xFFF5E6EC),
            Color(0xFFEFDBE4),
        )
        HomeBackgroundStyle.PASTEL_LAVENDER -> listOf(
            Color(0xFFF6F3FC),
            Color(0xFFECE7F5),
            Color(0xFFE3DDEC),
        )
        HomeBackgroundStyle.PASTEL_MINT -> listOf(
            Color(0xFFF2F9F5),
            Color(0xFFE4F1E9),
            Color(0xFFD8E9DE),
        )
    }
}

internal fun homeBackgroundLabel(style: HomeBackgroundStyle): String {
    return when (style) {
        HomeBackgroundStyle.WARM_BEIGE -> "今の背景"
        HomeBackgroundStyle.PASTEL_BLUSH -> "さくら"
        HomeBackgroundStyle.PASTEL_LAVENDER -> "ラベンダー"
        HomeBackgroundStyle.PASTEL_MINT -> "ミント"
    }
}
