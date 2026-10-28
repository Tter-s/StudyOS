package com.zhixu.studyos.core.learning

import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.ProcrastinationReason
import com.zhixu.studyos.core.model.SelfControlEntry
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.TaskSession
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.TimingSession
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfControlCalculatorTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val now = Instant.parse("2026-10-26T10:00:00Z")

    @Test
    fun dailyAndWeeklyMetricsUsePlannedSessionsAndRealCompletedTime() {
        val tasks = listOf(
            task(1, today, completed = true),
            task(2, today, completed = false),
        )
        val timings = listOf(
            timing(90, 2),
            timing(30, 1),
        )
        val entries = listOf(
            entry(today, ProcrastinationReason.UNCLEAR_START, voluntary = true),
            entry(today.minusDays(1), ProcrastinationReason.FATIGUE, voluntary = false),
        )
        val goals = LearningGoals(90, 600, 2)

        val overview = SelfControlCalculator.calculate(tasks, timings, entries, goals, today)

        assertEquals(2, overview.today.plannedTasks)
        assertEquals(1, overview.today.completedTasks)
        assertEquals(0.5f, overview.today.completionRate)
        assertEquals(90, overview.today.plannedMinutes)
        assertEquals(120, overview.today.actualMinutes)
        assertEquals(30, overview.today.timeDeviationMinutes)
        assertEquals(3, overview.today.timerInterruptions)
        assertEquals(1, overview.today.voluntaryInterruptions)
        assertEquals(7, overview.dailyTrend.size)
        assertEquals(4, overview.weeklyTrend.size)
        assertEquals(today, overview.dailyTrend.last().date)
        assertEquals(goals, overview.goals)
    }

    @Test
    fun emptyPlanReportsNoRateInsteadOfZeroPercent() {
        val overview = SelfControlCalculator.calculate(
            tasks = emptyList(),
            timings = emptyList(),
            entries = emptyList(),
            goals = LearningGoals(),
            today = today,
        )

        assertEquals(0, overview.today.plannedTasks)
        assertEquals(null, overview.today.completionRate)
        assertEquals(0, overview.today.actualMinutes)
    }

    private fun task(id: Long, date: LocalDate, completed: Boolean) = StudyTask(
        id = id,
        title = "任务$id",
        status = if (completed) TaskStatus.COMPLETED else TaskStatus.SCHEDULED,
        estimatedMinutes = 45,
        sessions = listOf(TaskSession(date = date, startMinute = 9 * 60, endMinuteExclusive = 10 * 60)),
    )

    private fun timing(minutes: Int, interruptions: Int) = TimingSession(
        subjectId = 1,
        studyDate = today,
        mode = StudyTimerMode.STOPWATCH,
        status = StudyTimerStatus.COMPLETED,
        accumulatedMillis = minutes * 60_000L,
        interruptionCount = interruptions,
        startedAt = now,
    )

    private fun entry(date: LocalDate, reason: ProcrastinationReason, voluntary: Boolean) = SelfControlEntry(
        date = date,
        procrastinationReason = reason,
        voluntaryInterruption = voluntary,
        createdAt = now,
    )
}
