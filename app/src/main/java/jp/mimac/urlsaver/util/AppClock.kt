package jp.mimac.urlsaver.util

import java.time.Instant

interface AppClock {
    fun nowEpochMillis(): Long
}

object SystemAppClock : AppClock {
    override fun nowEpochMillis(): Long = Instant.now().toEpochMilli()
}
