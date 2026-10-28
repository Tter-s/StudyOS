package com.zhixu.studyos.core.schedule

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.CourseException
import com.zhixu.studyos.core.model.CourseExceptionType
import com.zhixu.studyos.core.model.OneOffEvent
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.WeekParity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleConflictDetectorTest {
    private val semester = SemesterSettings(
        startDate = LocalDate.of(2026, 9, 7),
        zoneId = ZoneId.of("Asia/Shanghai"),
    )

    @Test
    fun overlappingCourses_areRejected() {
        val existing = course(id = 1, name = "高等代数", start = 8 * 60, end = 10 * 60)
        val candidate = course(id = 2, start = 9 * 60, end = 11 * 60)

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            candidate,
            ScheduleSnapshot(semester = semester, courses = listOf(existing)),
        )

        assertEquals(1, conflicts.size)
        assertEquals("高等代数", conflicts.single().existing.title)
    }

    @Test
    fun touchingIntervals_doNotConflict() {
        val existing = course(id = 1, start = 8 * 60, end = 9 * 60)
        val candidate = course(id = 2, start = 9 * 60, end = 10 * 60)

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            candidate,
            ScheduleSnapshot(semester = semester, courses = listOf(existing)),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun differentWeekParity_doesNotConflict() {
        val existing = course(id = 1, parity = WeekParity.ODD, startWeek = 1, endWeek = 4)
        val candidate = course(id = 2, parity = WeekParity.EVEN, startWeek = 1, endWeek = 4)

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            candidate,
            ScheduleSnapshot(semester = semester, courses = listOf(existing)),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun editingCourse_excludesItsOwnOriginalInstances() {
        val original = course(id = 1, start = 8 * 60, end = 9 * 60)
        val edited = original.copy(startMinute = 10 * 60, endMinuteExclusive = 11 * 60)

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            edited,
            ScheduleSnapshot(semester = semester, courses = listOf(original)),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun cancelledOccurrence_noLongerConflicts() {
        val existing = course(id = 1, start = 8 * 60, end = 9 * 60)
        val cancel = CourseException(
            id = 1,
            courseId = 1,
            originalDate = LocalDate.of(2026, 9, 7),
            type = CourseExceptionType.CANCEL,
        )
        val candidate = course(id = 2, start = 8 * 60, end = 9 * 60, startWeek = 1, endWeek = 1)

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            candidate,
            ScheduleSnapshot(semester = semester, courses = listOf(existing), exceptions = listOf(cancel)),
        )

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun movedOccurrence_conflictsAtItsNewTime() {
        val movedCourse = course(id = 1, start = 8 * 60, end = 9 * 60)
        val other = course(id = 2, name = "英语", start = 10 * 60, end = 11 * 60)
        val move = CourseException(
            id = 1,
            courseId = 1,
            originalDate = LocalDate.of(2026, 9, 7),
            type = CourseExceptionType.MOVE,
            targetDate = LocalDate.of(2026, 9, 7),
            targetStartMinute = 10 * 60,
            targetEndMinuteExclusive = 11 * 60,
        )
        val snapshot = ScheduleSnapshot(
            semester = semester,
            courses = listOf(movedCourse, other),
        )

        val conflicts = ScheduleConflictDetector.findExceptionConflicts(move, snapshot)

        assertEquals(1, conflicts.size)
        assertEquals("英语", conflicts.single().existing.title)
    }

    @Test
    fun crossDayOverlap_isDetectedAcrossDateBoundary() {
        val existing = course(id = 1, start = 22 * 60, end = 25 * 60)
        val candidate = course(
            id = 2,
            dayOfWeek = 2,
            start = 0,
            end = 30,
            startWeek = 1,
            endWeek = 1,
        )

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            candidate,
            ScheduleSnapshot(semester = semester, courses = listOf(existing)),
        )

        assertEquals(1, conflicts.size)
        assertEquals(LocalDate.of(2026, 9, 8), conflicts.single().date)
    }

    @Test
    fun oneOffEvent_conflictsWithExpandedRecurringCourse() {
        val existingCourse = course(id = 1, start = 8 * 60, end = 9 * 60)
        val event = OneOffEvent(
            id = 1,
            title = "班会",
            date = LocalDate.of(2026, 9, 7),
            startMinute = 8 * 60 + 30,
            endMinuteExclusive = 9 * 60 + 30,
            colorArgb = 0xFFFFB74D.toInt(),
        )

        val conflicts = ScheduleConflictDetector.findEventConflicts(
            event,
            ScheduleSnapshot(semester = semester, courses = listOf(existingCourse)),
            semester.zoneId,
        )

        assertEquals(1, conflicts.size)
        assertEquals("数学分析", conflicts.single().existing.title)
    }

    @Test
    fun sameDstWallTimeCourse_isDetectedUsingActualInstants() {
        val zoneId = ZoneId.of("America/New_York")
        val dstSemester = SemesterSettings(LocalDate.of(2026, 10, 26), zoneId)
        val existing = course(
            id = 1,
            dayOfWeek = 7,
            start = 60,
            end = 120,
            startWeek = 1,
            endWeek = 1,
        )
        val candidate = course(
            id = 2,
            dayOfWeek = 7,
            start = 90,
            end = 150,
            startWeek = 1,
            endWeek = 1,
        )

        val conflicts = ScheduleConflictDetector.findCourseConflicts(
            candidate,
            ScheduleSnapshot(semester = dstSemester, courses = listOf(existing)),
        )

        assertEquals(1, conflicts.size)
    }

    private fun course(
        id: Long,
        name: String = "数学分析",
        dayOfWeek: Int = 1,
        start: Int = 8 * 60,
        end: Int = 9 * 60,
        startWeek: Int = 1,
        endWeek: Int = 1,
        parity: WeekParity = WeekParity.ALL,
    ) = Course(
        id = id,
        name = name,
        teacher = null,
        classroom = "A101",
        dayOfWeek = dayOfWeek,
        startMinute = start,
        endMinuteExclusive = end,
        startWeek = startWeek,
        endWeek = endWeek,
        weekParity = parity,
        colorArgb = 0xFF64B5F6.toInt(),
    )
}
