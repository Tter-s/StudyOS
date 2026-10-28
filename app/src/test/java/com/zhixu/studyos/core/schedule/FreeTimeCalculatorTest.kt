package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.FreeTimeInterval
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.ScheduleInstance
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTimeCalculatorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val semester = SemesterSettings(LocalDate.of(2026, 9, 7), zoneId)

    @Test
    fun emptyDay_returnsTheWholeLocalDay() {
        val freeTimes = FreeTimeCalculator.calculate(LocalDate.of(2026, 9, 8), emptyList(), zoneId)

        assertEquals(1, freeTimes.size)
        assertEquals(24 * 60L, freeTimes.single().durationMinutes)
    }

    @Test
    fun courseSleepMealAndFixedBlocks_areAllExcluded() {
        val date = LocalDate.of(2026, 9, 8)
        val snapshot = ScheduleSnapshot(
            semester = semester,
            courses = listOf(
                course(dayOfWeek = 2, start = 8 * 60, end = 10 * 60),
            ),
            events = listOf(event(id = 1, date = date, start = 18 * 60, end = 19 * 60)),
            routines = listOf(
                routine(id = 1, category = RoutineCategory.SLEEP, start = 22 * 60, end = 31 * 60),
                routine(id = 2, category = RoutineCategory.MEAL, start = 12 * 60, end = 13 * 60),
                routine(
                    id = 3,
                    category = RoutineCategory.FIXED,
                    repeat = RoutineRepeat.WEEKLY,
                    dayOfWeek = 2,
                    start = 14 * 60,
                    end = 15 * 60,
                ),
            ),
        )

        val day = ScheduleEngine.daySchedule(snapshot, date, zoneId)

        assertEquals(
            listOf(7 * 60 to 8 * 60, 10 * 60 to 12 * 60, 13 * 60 to 14 * 60, 15 * 60 to 18 * 60, 19 * 60 to 22 * 60),
            day.freeTimes.map { it.start.hour * 60 + it.start.minute to it.end.hour * 60 + it.end.minute },
        )
        assertEquals(10 * 60L, day.totalFreeMinutes)
    }

    @Test
    fun overlappingAndAdjacentBusyIntervals_areMergedBeforeComplement() {
        val date = LocalDate.of(2026, 9, 8)
        val busy = listOf(
            instance(id = 1, date = date, start = 8 * 60, end = 9 * 60),
            instance(id = 2, date = date, start = 8 * 60 + 30, end = 10 * 60),
            instance(id = 3, date = date, start = 10 * 60, end = 11 * 60),
        )

        val freeTimes = FreeTimeCalculator.calculate(date, busy, zoneId)

        assertEquals(2, freeTimes.size)
        assertEquals(8 * 60L, freeTimes[0].durationMinutes)
        assertEquals(13 * 60L, freeTimes[1].durationMinutes)
        assertEquals(21 * 60L, FreeTimeCalculator.totalMinutes(freeTimes))
    }

    @Test
    fun previousDayOverflow_isClippedToTheRequestedDay() {
        val date = LocalDate.of(2026, 9, 8)
        val sleep = instance(id = 1, date = date.minusDays(1), start = 22 * 60, end = 31 * 60)

        val freeTimes = FreeTimeCalculator.calculate(date, listOf(sleep), zoneId)

        assertEquals(1, freeTimes.size)
        assertEquals(17 * 60L, freeTimes.single().durationMinutes)
    }

    @Test
    fun fullyBusyDay_hasNoFreeTime() {
        val date = LocalDate.of(2026, 9, 8)
        val busy = listOf(instance(id = 1, date = date, start = 0, end = 24 * 60))

        val freeTimes = FreeTimeCalculator.calculate(date, busy, zoneId)

        assertTrue(freeTimes.isEmpty())
    }

    @Test
    fun daylightSavingGapDay_hasTwentyThreeActualHours() {
        val newYork = ZoneId.of("America/New_York")
        val springForward = LocalDate.of(2026, 3, 8)

        val freeTimes = FreeTimeCalculator.calculate(springForward, emptyList(), newYork)

        assertEquals(23 * 60L, freeTimes.single().durationMinutes)
    }

    @Test
    fun daylightSavingOverlapDay_hasTwentyFiveActualHours() {
        val newYork = ZoneId.of("America/New_York")
        val fallBack = LocalDate.of(2026, 11, 1)

        val freeTimes = FreeTimeCalculator.calculate(fallBack, emptyList(), newYork)

        assertEquals(25 * 60L, freeTimes.single().durationMinutes)
    }

    @Test
    fun ambiguousLocalInterval_usesEarlierStartOffsetAndLaterEndOffset() {
        val newYork = ZoneId.of("America/New_York")
        val interval = instance(
            id = 1,
            date = LocalDate.of(2026, 11, 1),
            start = 60,
            end = 150,
        )

        val range = ScheduleDateTimes.instantRange(interval, newYork)

        assertEquals(150L, Duration.between(range!!.first, range.second).toMinutes())
    }

    @Test
    fun busyIntervalsAcrossDstGap_doNotMakeFreeTimeNegative() {
        val newYork = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val gapCourse = instance(id = 1, date = date, start = 2 * 60, end = 3 * 60 + 30)

        val freeTimes = FreeTimeCalculator.calculate(date, listOf(gapCourse), newYork)

        assertEquals(22 * 60L + 30L, freeTimes.sumOf(FreeTimeInterval::durationMinutes))
        assertTrue(freeTimes.all { it.end.isAfter(it.start) })
    }

    private fun course(dayOfWeek: Int, start: Int, end: Int) = Course(
        id = 1,
        name = "数学分析",
        teacher = null,
        classroom = "A101",
        dayOfWeek = dayOfWeek,
        startMinute = start,
        endMinuteExclusive = end,
        startWeek = 1,
        endWeek = 2,
        weekParity = WeekParity.ALL,
        colorArgb = 0xFF64B5F6.toInt(),
    )

    private fun event(id: Long, date: LocalDate, start: Int, end: Int) = OneOffEvent(
        id = id,
        title = "临时日程$id",
        date = date,
        startMinute = start,
        endMinuteExclusive = end,
        colorArgb = 0xFFFFB74D.toInt(),
    )

    private fun routine(
        id: Long,
        category: RoutineCategory,
        start: Int,
        end: Int,
        repeat: RoutineRepeat = RoutineRepeat.DAILY,
        dayOfWeek: Int? = null,
    ) = RoutineBlock(
        id = id,
        title = category.name,
        category = category,
        repeat = repeat,
        dayOfWeek = dayOfWeek,
        startMinute = start,
        endMinuteExclusive = end,
    )

    private fun instance(id: Long, date: LocalDate, start: Int, end: Int) = ScheduleInstance(
        type = ScheduleItemType.ONE_OFF_EVENT,
        sourceId = id,
        title = "测试$id",
        subtitle = "",
        colorArgb = 0xFFFFB74D.toInt(),
        anchorDate = date,
        startMinute = start,
        endMinuteExclusive = end,
    )
}
