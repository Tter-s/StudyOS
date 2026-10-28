package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.Course
import com.zhixu.studyos.core.model.RoutineBlock
import com.zhixu.studyos.core.model.RoutineCategory
import com.zhixu.studyos.core.model.RoutineRepeat
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.SemesterSettings
import com.zhixu.studyos.core.model.StudyBlockKind
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.WeekParity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyDayPlannerTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 7)
    private val semester = SemesterSettings(date, zoneId)

    @Test
    fun taskSessions_areIncludedInTimelineAndExcludedFromFreeTime() {
        val schedule = ScheduleSnapshot(
            semester = semester,
            courses = listOf(
                Course(
                    id = 1,
                    name = "数学分析",
                    classroom = "A101",
                    dayOfWeek = 1,
                    startMinute = 8 * 60,
                    endMinuteExclusive = 10 * 60,
                    startWeek = 1,
                    endWeek = 4,
                    weekParity = WeekParity.ALL,
                    colorArgb = 0xFF64B5F6.toInt(),
                ),
            ),
            routines = listOf(
                RoutineBlock(
                    id = 1,
                    title = "睡眠",
                    category = RoutineCategory.SLEEP,
                    repeat = RoutineRepeat.DAILY,
                    startMinute = 23 * 60,
                    endMinuteExclusive = 31 * 60,
                ),
            ),
        )
        val task = StudyTask(
            id = 9,
            title = "习题",
            status = TaskStatus.SCHEDULED,
            sessions = listOf(TaskSession(date = date, startMinute = 9 * 60, endMinuteExclusive = 11 * 60)),
        )
        val entries = TaskPlanner.dayEntries(listOf(task), date)

        val plan = StudyDayPlanner.build(date, entries, schedule, zoneId)

        assertTrue(plan.timeline.any { it.kind == StudyBlockKind.COURSE && it.title == "数学分析" })
        assertTrue(plan.timeline.any { it.kind == StudyBlockKind.TASK && it.title == "习题" })
        assertTrue(plan.timeline.any { it.kind == StudyBlockKind.REST && it.title == "睡眠" })
        assertEquals(120, plan.taskMinutes)
        assertEquals(13 * 60L, plan.totalFreeMinutes)
    }
}
