package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlannerTest {
    private val startDate = LocalDate.of(2026, 10, 26)

    @Test
    fun dailyRecurrence_materializesBoundedOccurrencesAndShiftsSessions() {
        val task = baseTask().copy(
            recurrence = TaskRecurrence.DAILY,
            recurrenceUntil = startDate.plusDays(2),
            sessions = listOf(
                TaskSession(date = startDate, startMinute = 19 * 60, endMinuteExclusive = 20 * 60),
                TaskSession(date = startDate, startMinute = 20 * 60 + 30, endMinuteExclusive = 21 * 60),
            ),
        )

        val occurrences = TaskPlanner.expandForCreation(task, "series-1", firstSortOrder = 5)

        assertEquals(3, occurrences.size)
        assertEquals(listOf(startDate, startDate.plusDays(1), startDate.plusDays(2)), occurrences.map { it.sessions.first().date })
        assertTrue(occurrences.all { it.seriesId == "series-1" })
        assertTrue(occurrences.all { it.status == TaskStatus.SCHEDULED })
        assertEquals(listOf(5, 6, 7), occurrences.map { it.sortOrder })
        assertEquals(2, occurrences.last().sessions.size)
    }

    @Test
    fun weeklyRecurrence_usesSevenDayPeriodAndStopsAtBoundary() {
        val task = baseTask().copy(
            recurrence = TaskRecurrence.WEEKLY,
            recurrenceInterval = 1,
            recurrenceUntil = startDate.plusDays(20),
            sessions = listOf(TaskSession(date = startDate, startMinute = 9 * 60, endMinuteExclusive = 10 * 60)),
        )

        val occurrences = TaskPlanner.expandForCreation(task, "series-2", firstSortOrder = 0)

        assertEquals(
            listOf(startDate, startDate.plusDays(7), startDate.plusDays(14)),
            occurrences.map { it.sessions.single().date },
        )
    }

    @Test
    fun unscheduledTask_isBacklogAndKeepsNoSessions() {
        val occurrence = TaskPlanner.expandForCreation(
            baseTask().copy(sessions = emptyList(), status = TaskStatus.SCHEDULED),
            seriesId = null,
            firstSortOrder = 1,
        ).single()

        assertEquals(TaskStatus.BACKLOG, occurrence.status)
        assertTrue(occurrence.sessions.isEmpty())
        assertNull(occurrence.seriesId)
    }

    @Test
    fun dayEntries_clipCrossDaySessionsAndSortByTaskOrder() {
        val later = baseTask(id = 2, sortOrder = 2).copy(
            sessions = listOf(TaskSession(date = startDate, startMinute = 8 * 60, endMinuteExclusive = 9 * 60)),
        )
        val crossDay = baseTask(id = 1, sortOrder = 1).copy(
            sessions = listOf(TaskSession(date = startDate, startMinute = 22 * 60, endMinuteExclusive = 25 * 60)),
        )

        val monday = TaskPlanner.dayEntries(listOf(later, crossDay), startDate)
        val tuesday = TaskPlanner.dayEntries(listOf(later, crossDay), startDate.plusDays(1))

        assertEquals(listOf(1L, 2L), monday.map { it.task.id })
        assertEquals(22 * 60 to 24 * 60, monday.first().segments.single().let { it.startMinute to it.endMinuteExclusive })
        assertEquals(0 to 60, tuesday.single().segments.single().let { it.startMinute to it.endMinuteExclusive })
        assertTrue(tuesday.single().segments.single().isContinuation)
    }

    @Test
    fun backlog_containsUnplannedAndOverdueIncompleteTasksButNotFutureOrCompleted() {
        val today = startDate.plusDays(2)
        val unscheduled = baseTask(id = 1, sortOrder = 1).copy(status = TaskStatus.BACKLOG)
        val overdue = baseTask(id = 2, sortOrder = 2).copy(
            sessions = listOf(TaskSession(date = today.minusDays(2), startMinute = 60, endMinuteExclusive = 120)),
        )
        val future = baseTask(id = 3, sortOrder = 3).copy(
            sessions = listOf(TaskSession(date = today.plusDays(1), startMinute = 60, endMinuteExclusive = 120)),
        )
        val completed = baseTask(id = 4, sortOrder = 4).copy(
            status = TaskStatus.COMPLETED,
            completedAt = java.time.Instant.parse("2026-10-27T10:00:00Z"),
            sessions = listOf(TaskSession(date = today.minusDays(1), startMinute = 60, endMinuteExclusive = 120)),
        )

        val backlog = TaskPlanner.backlogTasks(listOf(unscheduled, overdue, future, completed), today)

        assertEquals(listOf(1L, 2L), backlog.map { it.id })
    }

    private fun baseTask(id: Long = 1, sortOrder: Int = 1) = StudyTask(
        id = id,
        title = "任务$id",
        priority = TaskPriority.MEDIUM,
        estimatedMinutes = 60,
        status = TaskStatus.SCHEDULED,
        sortOrder = sortOrder,
        sessions = listOf(TaskSession(date = startDate, startMinute = 19 * 60, endMinuteExclusive = 20 * 60)),
    )
}
