package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScoringTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val preferences = mapOf(
        "数学分析" to SubjectPreference.HIGHER,
        "大学英语" to SubjectPreference.LOWER,
    )

    @Test
    fun deadlineScore_isMonotonicAndCapped() {
        assertEquals(0, TaskScoring.deadlineScore(today, null).first)
        assertEquals(130, TaskScoring.deadlineScore(today, today.minusDays(60)).first)
        assertEquals(101, TaskScoring.deadlineScore(today, today.minusDays(1)).first)
        assertEquals(90, TaskScoring.deadlineScore(today, today).first)
        assertEquals(70, TaskScoring.deadlineScore(today, today.plusDays(1)).first)
        assertEquals(55, TaskScoring.deadlineScore(today, today.plusDays(2)).first)
        assertEquals(40, TaskScoring.deadlineScore(today, today.plusDays(3)).first)
        assertEquals(30, TaskScoring.deadlineScore(today, today.plusDays(4)).first)
        assertEquals(21, TaskScoring.deadlineScore(today, today.plusDays(7)).first)
        assertEquals(10, TaskScoring.deadlineScore(today, today.plusDays(14)).first)
        assertEquals(0, TaskScoring.deadlineScore(today, today.plusDays(15)).first)
    }

    @Test
    fun priorityAndSubjectPreference_addExplainableScores() {
        assertEquals(30, TaskScoring.priorityScore(TaskPriority.HIGH).first)
        assertEquals(15, TaskScoring.priorityScore(TaskPriority.MEDIUM).first)
        assertEquals(0, TaskScoring.priorityScore(TaskPriority.LOW).first)
        assertEquals(20, TaskScoring.subjectScore(" 数学分析 ", preferences).first)
        assertEquals(-15, TaskScoring.subjectScore("大学英语", preferences).first)
        assertEquals(0, TaskScoring.subjectScore("Python", preferences).first)
        assertEquals(0, TaskScoring.subjectScore("", preferences).first)
    }

    @Test
    fun carryOver_onlyAppliesToPastIncompleteSessions() {
        assertFalse(TaskScoring.isCarryOver(task(sessions = listOf(session(today.plusDays(1)))), today, 480))
        assertTrue(TaskScoring.isCarryOver(task(sessions = listOf(session(today.minusDays(1)))), today, 480))
        assertTrue(
            TaskScoring.isCarryOver(
                task(sessions = listOf(session(today, 480, 600))),
                today,
                660,
            ),
        )
        assertFalse(
            TaskScoring.isCarryOver(
                task(sessions = listOf(session(today, 600, 660))),
                today,
                540,
            ),
        )
    }

    @Test
    fun score_combinesDeadlinePriorityPreferenceAndCarryOver() {
        val task = task(
            priority = TaskPriority.HIGH,
            dueDate = today,
            subject = "数学分析",
            sessions = listOf(session(today.minusDays(1))),
        )
        val score = TaskScoring.score(task, today, 480, preferences)
        assertEquals(90, score.deadlineScore)
        assertEquals(30, score.priorityScore)
        assertEquals(20, score.subjectScore)
        assertEquals(25, score.carryOverScore)
        assertEquals(165, score.total)
        assertTrue(score.reasons.any { it.contains("今天截止") })
        assertTrue(score.reasons.any { it.contains("高优先级") })
        assertTrue(score.reasons.any { it.contains("提高") })
        assertTrue(score.reasons.any { it.contains("顺延") })
    }

    @Test
    fun comparator_ordersByScoreThenDeadlinePriorityAndId() {
        val nearDeadlineLow = plan(
            id = 2,
            score = TaskScoring.score(
                task(priority = TaskPriority.LOW, dueDate = today),
                today,
                480,
                preferences,
            ),
        )
        val laterDeadlineHigh = plan(
            id = 1,
            score = TaskScoring.score(
                task(priority = TaskPriority.HIGH, dueDate = today.plusDays(3)),
                today,
                480,
                preferences,
            ),
        )
        val sorted = listOf(laterDeadlineHigh, nearDeadlineLow).sortedWith(TaskScoring.planComparator)
        assertEquals(2L, sorted.first().taskId)
    }

    private fun plan(id: Long, score: TaskScoreBreakdown) = SchedulingTaskPlan(
        taskId = id,
        title = "task $id",
        subject = "",
        priority = TaskPriority.MEDIUM,
        dueDate = null,
        estimatedMinutes = 60,
        fixedMinutes = 0,
        remainingMinutes = 60,
        lockedSessionCount = 0,
        score = score,
        latestDate = today,
        isCarryOver = false,
    )

    private fun task(
        priority: TaskPriority = TaskPriority.MEDIUM,
        dueDate: LocalDate? = null,
        subject: String = "",
        sessions: List<TaskSession> = emptyList(),
    ) = StudyTask(
        id = 1,
        title = "数学分析",
        subject = subject,
        priority = priority,
        dueDate = dueDate,
        estimatedMinutes = 60,
        status = if (sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED,
        sessions = sessions,
    )

    private fun session(
        date: LocalDate,
        start: Int = 480,
        end: Int = 540,
    ) = TaskSession(date = date, startMinute = start, endMinuteExclusive = end)
}
