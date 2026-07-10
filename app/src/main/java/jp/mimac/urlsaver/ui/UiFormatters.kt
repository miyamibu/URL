package jp.mimac.urlsaver.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
private val monthDayFormatter = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
private val yearMonthDayFormatter = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.JAPAN)
private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.JAPAN)

fun formatTimestamp(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    nowEpochMillis: Long = Instant.now().toEpochMilli(),
): String {
    val target = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
    return when {
        target.toLocalDate() == now.toLocalDate() -> target.format(timeFormatter)
        target.year == now.year -> target.format(monthDayFormatter)
        else -> target.format(yearMonthDayFormatter)
    }
}

fun formatDetailDateTime(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val target = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    return target.format(fullDateTimeFormatter)
}
