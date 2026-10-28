package com.zhixu.studyos.core.learning

import com.zhixu.studyos.core.model.DailySelfControlTrend
import com.zhixu.studyos.core.model.LearningGoals
import com.zhixu.studyos.core.model.SelfControlEntry
import com.zhixu.studyos.core.model.SelfControlOverview
import com.zhixu.studyos.core.model.SelfControlPeriodMetrics
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.StudyTimerStatus
import com.zhixu.studyos.core.model.TimingSession
import com.zhixu.studyos.core.model.WeeklySelfControlTrend
import com.zhixu.studyos.core.task.TaskPlanner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object SelfControlCalculator {
    fun calculate(
        tasks: List<StudyTask>,
        timings: List<TimingSession>,
        entries: List<SelfControlEntry>,
        goals: LearningGoals,
        today: LocalDate,
    ): SelfControlOverview {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val dailyTrend = (6L downTo 0L).map { offset ->
            val date = today.minusDays(offset)
            val metrics = dayMetrics(date, tasks, timings, entries)
            DailySelfControlTrend(
                date = date,
                plannedTasks = metrics.plannedTasks,
                completedTasks = metrics.completedTasks,
                plannedMinutes = metrics.plannedMinutes,
                actualMinutes = metrics.actualMinutes,
                interruptions = metrics.timerInterruptions,
            )
        }
        val weeklyTrend = (3L downTo 0L).map { offset ->
            val start = weekStart.minusWeeks(offset)
            val end = start.plusDays(6)
            val metrics = periodMetrics(start, end, tasks, timings, entries)
            WeeklySelfControlTrend(
                start = start,
                end = end,
                plannedTasks = metrics.plannedTasks,
                completedTasks = metrics.completedTasks,
                actualMinutes = metrics.actualMinutes,
            )
        }
        return SelfControlOverview(
            today = dayMetrics(today, tasks, timings, entries),
            week = periodMetrics(weekStart, weekStart.plusDays(6), tasks, timings, entries),
            dailyTrend = dailyTrend,
            weeklyTrend = weeklyTrend,
            entries = entries.sortedByDescending(SelfControlEntry::createdAt).take(20),
            goals = goals,
        )
    }

    private fun dayMetrics(
        date: LocalDate,
        tasks: List<StudyTask>,
        timings: List<TimingSession>,
        entries: List<SelfControlEntry>,
    ): SelfControlPeriodMetrics {
        val dayTasks = TaskPlanner.dayEntries(tasks, date)
        return SelfControlPeriodMetrics(
            start = date,
            end = date,
            plannedTasks = dayTasks.size,
            completedTasks = dayTasks.count { it.task.isCompleted },
            plannedMinutes = dayTasks.sumOf { it.task.estimatedMinutes },
            actualMinutes = actualMinutes(date, date, timings),
            timerInterruptions = timerInterruptions(date, date, timings),
            voluntaryInterruptions = entries.count { it.date == date && it.voluntaryInterruption },
        )
    }

    private fun periodMetrics(
        start: LocalDate,
        end: LocalDate,
        tasks: List<StudyTask>,
        timings: List<TimingSession>,
        entries: List<SelfControlEntry>,
    ): SelfControlPeriodMetrics {
        val planned = tasks.filter { task ->
            task.sessions.any { session -> !session.date.isBefore(start) && !session.date.isAfter(end) }
        }
        return SelfControlPeriodMetrics(
            start = start,
            end = end,
            plannedTasks = planned.size,
            completedTasks = planned.count(StudyTask::isCompleted),
            plannedMinutes = planned.sumOf(StudyTask::estimatedMinutes),
            actualMinutes = actualMinutes(start, end, timings),
            timerInterruptions = timerInterruptions(start, end, timings),
            voluntaryInterruptions = entries.count {
                it.voluntaryInterruption && !it.date.isBefore(start) && !it.date.isAfter(end)
            },
        )
    }

    private fun actualMinutes(
        start: LocalDate,
        end: LocalDate,
        timings: List<TimingSession>,
    ): Int = (timings.filter {
        it.status == StudyTimerStatus.COMPLETED &&
            !it.studyDate.isBefore(start) &&
            !it.studyDate.isAfter(end)
    }.sumOf(TimingSession::accumulatedMillis) / 60_000L).toInt()

    private fun timerInterruptions(
        start: LocalDate,
        end: LocalDate,
        timings: List<TimingSession>,
    ): Int = timings.filter {
        !it.studyDate.isBefore(start) && !it.studyDate.isAfter(end)
    }.sumOf(TimingSession::interruptionCount)
}
