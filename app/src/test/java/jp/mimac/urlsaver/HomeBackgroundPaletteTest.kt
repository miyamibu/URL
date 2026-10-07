package jp.mimac.urlsaver

import androidx.compose.ui.graphics.Color
import jp.mimac.urlsaver.domain.HomeBackgroundStyle
import jp.mimac.urlsaver.ui.theme.homeBackgroundGradientColors
import jp.mimac.urlsaver.ui.theme.homeBackgroundLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackgroundPaletteTest {
    @Test
    fun currentStyleKeepsExactExistingWarmBeigeGradient() {
        assertEquals(
            listOf(Color(0xFFF7F3EC), Color(0xFFEDE7DF), Color(0xFFE9E1D7)),
            homeBackgroundGradientColors(HomeBackgroundStyle.WARM_BEIGE),
        )
        assertEquals("今の背景", homeBackgroundLabel(HomeBackgroundStyle.WARM_BEIGE))
    }

    @Test
    fun pastelStylesUseApprovedCrossPlatformStopsAndLabels() {
        assertEquals(
            listOf(Color(0xFFFCF3F6), Color(0xFFF5E6EC), Color(0xFFEFDBE4)),
            homeBackgroundGradientColors(HomeBackgroundStyle.PASTEL_BLUSH),
        )
        assertEquals(
            listOf(Color(0xFFF6F3FC), Color(0xFFECE7F5), Color(0xFFE3DDEC)),
            homeBackgroundGradientColors(HomeBackgroundStyle.PASTEL_LAVENDER),
        )
        assertEquals(
            listOf(Color(0xFFF2F9F5), Color(0xFFE4F1E9), Color(0xFFD8E9DE)),
            homeBackgroundGradientColors(HomeBackgroundStyle.PASTEL_MINT),
        )
        assertEquals("さくら", homeBackgroundLabel(HomeBackgroundStyle.PASTEL_BLUSH))
        assertEquals("ラベンダー", homeBackgroundLabel(HomeBackgroundStyle.PASTEL_LAVENDER))
        assertEquals("ミント", homeBackgroundLabel(HomeBackgroundStyle.PASTEL_MINT))
    }
}
