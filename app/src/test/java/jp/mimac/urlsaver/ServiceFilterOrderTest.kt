package jp.mimac.urlsaver

import jp.mimac.urlsaver.domain.ServiceType
import jp.mimac.urlsaver.ui.components.fixedServiceFilterOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceFilterOrderTest {

    @Test
    fun fixedOrder_matchesPhase1a() {
        assertEquals(
            listOf(
                ServiceType.ALL,
                ServiceType.YOUTUBE,
                ServiceType.X,
                ServiceType.INSTAGRAM,
                ServiceType.TIKTOK,
                ServiceType.WEB,
            ),
            fixedServiceFilterOrder,
        )
    }

    @Test
    fun allLabel_isSubete() {
        assertEquals("すべて", ServiceType.ALL.displayName)
    }
}
