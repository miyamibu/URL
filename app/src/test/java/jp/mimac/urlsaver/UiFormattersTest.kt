package jp.mimac.urlsaver

import jp.mimac.urlsaver.ui.formatTimestamp
import jp.mimac.urlsaver.ui.formatDetailDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UiFormattersTest {

    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun formatTimestamp_sameDayUsesTime() {
        val now = ZonedDateTime.of(2026, 4, 11, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val target = ZonedDateTime.of(2026, 4, 11, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("09:05", formatTimestamp(target, zone, now))
    }

    @Test
    fun formatTimestamp_sameYearUsesMonthDay() {
        val now = ZonedDateTime.of(2026, 4, 11, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val target = ZonedDateTime.of(2026, 1, 2, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("1/2", formatTimestamp(target, zone, now))
    }

    @Test
    fun formatTimestamp_otherYearUsesYearMonthDay() {
        val now = ZonedDateTime.of(2026, 4, 11, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val target = ZonedDateTime.of(2025, 12, 31, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2025/12/31", formatTimestamp(target, zone, now))
    }

    @Test
    fun formatDetailDateTime_usesFullDateAndTime() {
        val target = ZonedDateTime.of(2026, 4, 11, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026/4/11 09:05", formatDetailDateTime(target, zone))
    }
}
