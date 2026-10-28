package com.zhixu.studyos.core.reminder

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanReminderPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun nextDelay_targetsTheNextOccurrenceOfTheClockTime() {
        assertEquals(
            Duration.ofMinutes(90).toMillis(),
            PlanReminderPolicy.nextDelayMillis(
                ZonedDateTime.of(2026, 10, 26, 6, 0, 0, 0, zone),
                7,
                30,
            ),
        )
        assertEquals(
            Duration.ofMinutes(23 * 60 + 30).toMillis(),
            PlanReminderPolicy.nextDelayMillis(
                ZonedDateTime.of(2026, 10, 26, 8, 0, 0, 0, zone),
                7,
                30,
            ),
        )
        assertEquals(
            Duration.ofHours(24).toMillis(),
            PlanReminderPolicy.nextDelayMillis(
                ZonedDateTime.of(2026, 10, 26, 7, 30, 0, 0, zone),
                7,
                30,
            ),
        )
    }

    @Test
    fun nextDelay_respectsDaylightSavingTransitions() {
        val newYork = ZoneId.of("America/New_York")
        val delay = PlanReminderPolicy.nextDelayMillis(
            ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, newYork),
            7,
            30,
        )
        assertEquals(Duration.ofMinutes(330).toMillis(), delay)
    }

    @Test
    fun invalidTimesAreRejected() {
        val now = ZonedDateTime.of(2026, 10, 26, 6, 0, 0, 0, zone)
        assertThrows(IllegalArgumentException::class.java) {
            PlanReminderPolicy.nextDelayMillis(now, 24, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanReminderPolicy.nextDelayMillis(now, 12, 60)
        }
    }
}
