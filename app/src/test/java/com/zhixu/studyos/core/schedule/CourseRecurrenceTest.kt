package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseRecurrenceTest {
    private val semester = SemesterSettings(
        startDate = LocalDate.of(2026, 9, 7),
        zoneId = ZoneId.of("Asia/Shanghai"),
    )

    @Test
    fun weeklyCourse_expandsOneRuleWithoutCopyingWeeks() {
        val instances = ScheduleEngine.courseInstances(course(startWeek = 1, endWeek = 4), semester)

        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 28),
            ),
            instances.map { it.anchorDate },
        )
    }

    @Test
    fun oddAndEvenWeekRules_onlyGenerateMatchingTeachingWeeks() {
        val odd = ScheduleEngine.courseInstances(
            course(id = 1, startWeek = 1, endWeek = 4, parity = WeekParity.ODD),
            semester,
        )
        val even = ScheduleEngine.courseInstances(
            course(id = 2, startWeek = 1, endWeek = 4, parity = WeekParity.EVEN),
            semester,
        )

        assertEquals(listOf(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 21)), odd.map { it.anchorDate })
        assertEquals(listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 28)), even.map { it.anchorDate })
    }

    @Test
    fun semesterStartingMidWeek_doesNotGenerateEarlierDatesInWeekOne() {
        val midweekSemester = semester.copy(startDate = LocalDate.of(2026, 9, 9))
        val instances = ScheduleEngine.courseInstances(course(startWeek = 1, endWeek = 2), midweekSemester)

        assertEquals(listOf(LocalDate.of(2026, 9, 14)), instances.map { it.anchorDate })
    }

    @Test
    fun weekBoundaries_includeStartAndEndButExcludeOutsideWeeks() {
        val instances = ScheduleEngine.courseInstances(course(startWeek = 2, endWeek = 3), semester)

        assertEquals(listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21)), instances.map { it.anchorDate })
    }

    @Test
    fun cancellation_removesOnlyTheSpecifiedDate() {
        val exception = CourseException(
            id = 1,
            courseId = 1,
            originalDate = LocalDate.of(2026, 9, 14),
            type = CourseExceptionType.CANCEL,
        )

        val instances = ScheduleEngine.courseInstances(course(startWeek = 1, endWeek = 2), semester, listOf(exception))

        assertEquals(listOf(LocalDate.of(2026, 9, 7)), instances.map { it.anchorDate })
    }

    @Test
    fun movedCourse_replacesOriginalOccurrenceInsteadOfAddingDuplicate() {
        val exception = CourseException(
            id = 1,
            courseId = 1,
            originalDate = LocalDate.of(2026, 9, 7),
            type = CourseExceptionType.MOVE,
            targetDate = LocalDate.of(2026, 9, 9),
            targetStartMinute = 10 * 60,
            targetEndMinuteExclusive = 11 * 60,
        )

        val instances = ScheduleEngine.courseInstances(course(startWeek = 1, endWeek = 2), semester, listOf(exception))

        assertEquals(2, instances.size)
        val moved = instances.first { it.isMoved }
        assertEquals(LocalDate.of(2026, 9, 9), moved.anchorDate)
        assertEquals(LocalDate.of(2026, 9, 7), moved.originalDate)
        assertEquals(10 * 60, moved.startMinute)
        assertEquals(11 * 60, moved.endMinuteExclusive)
    }

    @Test
    fun crossDayCourse_appearsAtEndOfAnchorDayAndStartOfNextDay() {
        val snapshot = ScheduleSnapshot(
            semester = semester,
            courses = listOf(course(startMinute = 22 * 60, endMinuteExclusive = 25 * 60)),
        )

        val monday = ScheduleEngine.daySchedule(snapshot, LocalDate.of(2026, 9, 7), semester.zoneId)
        val tuesday = ScheduleEngine.daySchedule(snapshot, LocalDate.of(2026, 9, 8), semester.zoneId)

        assertEquals(22 * 60 to 24 * 60, monday.items.single().let { it.segmentStartMinute to it.segmentEndMinuteExclusive })
        assertEquals(0 to 60, tuesday.items.single().let { it.segmentStartMinute to it.segmentEndMinuteExclusive })
    }

    @Test
    fun moveFromPreviousWeekIntoRequestedRange_isIncluded() {
        val course = course(dayOfWeek = 7, startWeek = 1, endWeek = 1)
        val exception = CourseException(
            id = 1,
            courseId = course.id,
            originalDate = LocalDate.of(2026, 9, 13),
            type = CourseExceptionType.MOVE,
            targetDate = LocalDate.of(2026, 9, 14),
            targetStartMinute = 9 * 60,
            targetEndMinuteExclusive = 10 * 60,
        )
        val snapshot = ScheduleSnapshot(semester, listOf(course), listOf(exception))

        val instances = ScheduleEngine.instancesForAnchors(
            snapshot,
            anchorStart = LocalDate.of(2026, 9, 14),
            anchorEnd = LocalDate.of(2026, 9, 20),
        )

        assertEquals(1, instances.size)
        assertTrue(instances.single().isMoved)
        assertEquals(LocalDate.of(2026, 9, 14), instances.single().anchorDate)
    }

    @Test
    fun teachingWeek_usesCivilDatesNotFixedMillisecondArithmetic() {
        val newYorkSemester = SemesterSettings(
            startDate = LocalDate.of(2026, 10, 26),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(1, SemesterCalendar.teachingWeek(LocalDate.of(2026, 10, 31), newYorkSemester))
        assertEquals(2, SemesterCalendar.teachingWeek(LocalDate.of(2026, 11, 2), newYorkSemester))
        assertFalse(SemesterCalendar.occursOn(course(startWeek = 1, endWeek = 1), LocalDate.of(2026, 10, 25), newYorkSemester))
    }

    private fun course(
        id: Long = 1,
        dayOfWeek: Int = 1,
        startMinute: Int = 8 * 60,
        endMinuteExclusive: Int = 9 * 60,
        startWeek: Int = 1,
        endWeek: Int = 2,
        parity: WeekParity = WeekParity.ALL,
    ) = Course(
        id = id,
        name = "数学分析",
        teacher = "王老师",
        classroom = "A101",
        dayOfWeek = dayOfWeek,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
        startWeek = startWeek,
        endWeek = endWeek,
        weekParity = parity,
        colorArgb = 0xFF64B5F6.toInt(),
    )
}
