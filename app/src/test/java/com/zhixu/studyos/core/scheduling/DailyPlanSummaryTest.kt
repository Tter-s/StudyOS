package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyPlanSummaryTest {
    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun summary_countsOpenAndCompletedTasksAndFirstOpenTask() {
        val summary = DailyPlanSummaryBuilder.build(
            tasks = listOf(
                task(
                    id = 1,
                    title = "数学分析",
                    completed = false,
                    sessions = listOf(
                        TaskSession(date = today, startMinute = 9 * 60, endMinuteExclusive = 10 * 60),
                        TaskSession(date = today, startMinute = 11 * 60, endMinuteExclusive = 11 * 60 + 30),
                    ),
                ),
                task(
                    id = 2,
                    title = "大学英语",
                    completed = true,
                    sessions = listOf(
                        TaskSession(date = today, startMinute = 13 * 60, endMinuteExclusive = 14 * 60),
                    ),
                ),
                task(id = 3, title = "无计划", completed = false, sessions = emptyList()),
                task(
                    id = 4,
                    title = "明天",
                    completed = false,
                    sessions = listOf(
                        TaskSession(date = today.plusDays(1), startMinute = 9 * 60, endMinuteExclusive = 10 * 60),
                    ),
                ),
            ),
            date = today,
        )
        assertEquals(1, summary.openTaskCount)
        assertEquals(1, summary.completedTaskCount)
        assertEquals(90, summary.openMinutes)
        assertEquals(9 * 60, summary.firstStartMinute)
        assertEquals("数学分析", summary.firstTaskTitle)

        val body = DailyPlanSummaryBuilder.notificationBody(summary)
        assertTrue(body.contains("还有 1 项"))
        assertTrue(body.contains("1 小时 30 分钟"))
        assertTrue(body.contains("09:00"))
        assertTrue(body.contains("数学分析"))
        assertTrue(body.contains("已完成 1 项"))
    }

    @Test
    fun emptyAndCompletedOnlyBodiesAreNeutral() {
        val empty = DailyPlanSummaryBuilder.build(emptyList(), today)
        assertTrue(DailyPlanSummaryBuilder.notificationBody(empty).contains("还没有安排"))
        assertEquals(0, empty.openTaskCount)

        val completedOnly = DailyPlanSummaryBuilder.build(
            tasks = listOf(
                task(
                    id = 1,
                    title = "已完成",
                    completed = true,
                    sessions = listOf(
                        TaskSession(date = today, startMinute = 9 * 60, endMinuteExclusive = 10 * 60),
                    ),
                ),
            ),
            date = today,
        )
        assertEquals(1, completedOnly.completedTaskCount)
        assertTrue(DailyPlanSummaryBuilder.notificationBody(completedOnly).contains("已完成"))
    }

    private fun task(
        id: Long,
        title: String,
        completed: Boolean,
        sessions: List<TaskSession>,
    ) = StudyTask(
        id = id,
        title = title,
        estimatedMinutes = 60,
        status = if (completed) TaskStatus.COMPLETED else TaskStatus.SCHEDULED,
        completedAt = if (completed) Instant.parse("2026-10-26T03:00:00Z") else null,
        sessions = sessions,
    )
}
