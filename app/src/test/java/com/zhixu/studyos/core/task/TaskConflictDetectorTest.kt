package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.WeekParity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskConflictDetectorTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val semester = SemesterSettings(LocalDate.of(2026, 9, 7), zoneId)
    private val monday = LocalDate.of(2026, 9, 7)

    @Test
    fun overlappingCourse_isReported() {
        val task = task(startMinute = 9 * 60, endMinuteExclusive = 10 * 60)

        val conflicts = TaskConflictDetector.findConflicts(task, snapshot(), zoneId)

        assertEquals(1, conflicts.size)
        assertEquals("数学分析", conflicts.single().otherTitle)
    }

    @Test
    fun touchingCourse_isNotAConflict() {
        val task = task(startMinute = 10 * 60, endMinuteExclusive = 11 * 60)

        val conflicts = TaskConflictDetector.findConflicts(task, snapshot(), zoneId)

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun completedTask_doesNotShowActiveConflict() {
        val task = task(startMinute = 9 * 60, endMinuteExclusive = 10 * 60).copy(
            status = TaskStatus.COMPLETED,
            completedAt = Instant.parse("2026-09-07T03:00:00Z"),
        )

        val conflicts = TaskConflictDetector.findConflicts(task, snapshot(), zoneId)

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun crossDayTask_conflictsWithPreviousCourse() {
        val task = StudyTask(
            id = 2,
            title = "夜间复习",
            status = TaskStatus.SCHEDULED,
            sessions = listOf(
                TaskSession(date = monday.plusDays(1), startMinute = 0, endMinuteExclusive = 60),
            ),
        )
        val lateCourse = course(startMinute = 22 * 60, endMinuteExclusive = 25 * 60)

        val conflicts = TaskConflictDetector.findConflicts(
            task,
            ScheduleSnapshot(semester = semester, courses = listOf(lateCourse)),
            zoneId,
        )

        assertEquals(1, conflicts.size)
    }

    @Test
    fun missingSemester_meansNoCourseConflictData() {
        val conflicts = TaskConflictDetector.findConflicts(
            task(startMinute = 9 * 60, endMinuteExclusive = 10 * 60),
            ScheduleSnapshot(),
            zoneId,
        )

        assertTrue(conflicts.isEmpty())
    }

    private fun snapshot() = ScheduleSnapshot(
        semester = semester,
        courses = listOf(course()),
    )

    private fun course(
        startMinute: Int = 8 * 60,
        endMinuteExclusive: Int = 10 * 60,
    ) = Course(
        id = 1,
        name = "数学分析",
        classroom = "A101",
        dayOfWeek = 1,
        startMinute = startMinute,
        endMinuteExclusive = endMinuteExclusive,
        startWeek = 1,
        endWeek = 4,
        weekParity = WeekParity.ALL,
        colorArgb = 0xFF64B5F6.toInt(),
    )

    private fun task(startMinute: Int, endMinuteExclusive: Int) = StudyTask(
        id = 1,
        title = "习题",
        status = TaskStatus.SCHEDULED,
        sessions = listOf(TaskSession(date = monday, startMinute = startMinute, endMinuteExclusive = endMinuteExclusive)),
    )
}
