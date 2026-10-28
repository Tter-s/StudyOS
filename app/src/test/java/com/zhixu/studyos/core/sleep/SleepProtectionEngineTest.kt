package com.zhixu.studyos.core.sleep

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepProtectionEngineTest {
    private val monday = LocalDate.of(2026, 10, 19)
    private val default = SleepSchedule()

    @Test
    fun defaultSchedule_coversMidnightTailAndEveningHeadWithBuffer() {
        val intervals = SleepProtectionEngine.intervalsOn(monday, default)
        // 前一晚 22:30 开始保护，到当天 07:00；当天 22:30 开始下一晚保护。
        assertEquals(2, intervals.size)
        assertEquals(0, intervals[0].startMinute)
        assertEquals(7 * 60, intervals[0].endMinuteExclusive)
        assertEquals(22 * 60 + 30, intervals[1].startMinute)
        assertEquals(24 * 60, intervals[1].endMinuteExclusive)
    }

    @Test
    fun weekendNight_usesWeekendScheduleOnFridayAndSaturdayOnly() {
        val friday = monday.plusDays(4)
        val sunday = monday.plusDays(6)
        val fridayEffective = SleepProtectionEngine.effectiveForNight(friday, default)
        val sundayEffective = SleepProtectionEngine.effectiveForNight(sunday, default)
        assertEquals(SleepSource.WEEKEND, fridayEffective.source)
        assertEquals(23 * 60 + 30, fridayEffective.bedMinute)
        assertEquals(SleepSource.WEEKDAY, sundayEffective.source)
        assertEquals(23 * 60, sundayEffective.bedMinute)
    }

    @Test
    fun dateOverride_winsOverWeekendAndExam() {
        val override = SleepDateOverride(
            date = monday,
            bedMinute = 22 * 60,
            wakeMinute = 6 * 60,
            note = "早起",
        )
        val effective = SleepProtectionEngine.effectiveForNight(
            monday,
            default,
            mapOf(monday to override),
        )
        assertEquals(SleepSource.DATE_OVERRIDE, effective.source)
        assertEquals(22 * 60, effective.bedMinute)
        assertEquals(6 * 60, effective.wakeMinute)
    }

    @Test
    fun examPeriod_usesExamSchedule() {
        val schedule = default.copy(
            examPeriodStart = monday,
            examPeriodEnd = monday.plusDays(6),
            examBedMinute = 23 * 60,
            examWakeMinute = 6 * 60 + 30,
        )
        val effective = SleepProtectionEngine.effectiveForNight(monday, schedule)
        assertEquals(SleepSource.EXAM_PERIOD, effective.source)
        assertEquals(6 * 60 + 30, effective.wakeMinute)
    }

    @Test
    fun nap_isBlockedAndMergedWithSleepWhenOverlapping() {
        val schedule = default.copy(
            napEnabled = true,
            napStartMinute = 13 * 60,
            napEndMinute = 14 * 60,
        )
        val intervals = SleepProtectionEngine.intervalsOn(monday, schedule)
        assertTrue(intervals.any { it.startMinute == 13 * 60 && it.endMinuteExclusive == 14 * 60 })
    }

    @Test
    fun conflictWithCourse_isReportedNotSuppressed() {
        val conflicts = SleepProtectionEngine.findConflicts(
            date = monday,
            schedule = default,
            busy = listOf(SleepBusyItem("早课", 6 * 60 + 30, 8 * 60)),
        )
        assertEquals(1, conflicts.size)
        assertEquals(6 * 60 + 30, conflicts.single().conflictStartMinute)
        assertEquals(7 * 60, conflicts.single().conflictEndMinuteExclusive)
        assertTrue(conflicts.single().message.contains("早课"))
    }

    @Test
    fun status_reportsWindDownBeforeBedAndAwakeDuringDay() {
        val windDown = SleepProtectionEngine.statusAt(monday, 22 * 60 + 45, default)
        assertEquals(SleepPhase.WIND_DOWN, windDown.phase)
        val awake = SleepProtectionEngine.statusAt(monday, 12 * 60, default)
        assertEquals(SleepPhase.AWAKE, awake.phase)
        val sleeping = SleepProtectionEngine.statusAt(monday, 3 * 60, default)
        assertEquals(SleepPhase.ASLEEP, sleeping.phase)
    }

    @Test
    fun validation_rejectsShortSleepAndPartialExamPeriod() {
        assertNotNull(SleepSchedule(weekdayBedMinute = 23 * 60, weekdayWakeMinute = 23 * 60 + 60).validationError())
        assertNotNull(default.copy(examPeriodStart = monday).validationError())
        assertNotNull(default.copy(examBedMinute = 60).validationError())
        assertNull(default.validationError())
    }

    @Test
    fun afterMidnightBedWithBuffer_blocksEveningOfPreviousDayAndMorning() {
        val schedule = default.copy(
            weekdayBedMinute = 10,
            weekdayWakeMinute = 7 * 60,
            weekendBedMinute = 10,
            weekendWakeMinute = 7 * 60,
        )
        val intervals = SleepProtectionEngine.intervalsOn(monday, schedule)
        assertTrue(intervals.any { it.startMinute == 0 && it.endMinuteExclusive == 7 * 60 })
        assertTrue(intervals.any { it.startMinute == 23 * 60 + 40 && it.endMinuteExclusive == 24 * 60 })
        val windDown = SleepProtectionEngine.statusAt(monday, 23 * 60 + 45, schedule)
        assertEquals(SleepPhase.WIND_DOWN, windDown.phase)
        val asleep = SleepProtectionEngine.statusAt(monday, 2 * 60, schedule)
        assertEquals(SleepPhase.ASLEEP, asleep.phase)
    }

    @Test
    fun afterMidnightBed_usesTheEveningBeforeForWeekendAndStatus() {
        val schedule = default.copy(
            weekdayBedMinute = 10,
            weekdayWakeMinute = 9 * 60,
            weekendBedMinute = 60,
            weekendWakeMinute = 9 * 60 + 30,
            preSleepBufferMinutes = 30,
        )
        val sunday = monday.plusDays(6)

        val intervals = SleepProtectionEngine.intervalsOn(sunday, schedule)

        // 周日凌晨属于周六夜：周末 01:00 入睡 + 30 分钟缓冲。
        assertTrue(intervals.any { it.startMinute == 30 && it.endMinuteExclusive == 9 * 60 + 30 })
        // 周日晚属于周日（工作日）夜：周一 00:10 入睡，缓冲从 23:40 开始。
        assertTrue(intervals.any { it.startMinute == 23 * 60 + 40 && it.endMinuteExclusive == 24 * 60 })

        val earlySunday = SleepProtectionEngine.statusAt(sunday, 2 * 60, schedule)
        assertEquals(SleepPhase.ASLEEP, earlySunday.phase)
        assertEquals(SleepSource.WEEKEND, earlySunday.source)
        assertEquals(60, earlySunday.bedMinute)

        val lateSunday = SleepProtectionEngine.statusAt(sunday, 23 * 60 + 45, schedule)
        assertEquals(SleepPhase.WIND_DOWN, lateSunday.phase)
        assertEquals(SleepSource.WEEKDAY, lateSunday.source)
    }

    @Test
    fun latestBedComparison_handlesAfterMidnight() {
        assertFalse(default.isLaterThanLatestBed(23 * 60))
        assertTrue(default.isLaterThanLatestBed(90))
        assertFalse(SleepSchedule(latestBedMinute = 90).isLaterThanLatestBed(23 * 60))
    }
}
