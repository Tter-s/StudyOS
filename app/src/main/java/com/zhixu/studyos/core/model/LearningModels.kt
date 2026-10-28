package com.zhixu.studyos.core.model

import java.time.Instant
import java.time.LocalDate

data class PresetSubject(val id: Long, val name: String)

val PRESET_SUBJECTS: List<PresetSubject> = listOf(
    PresetSubject(1, "数学分析"),
    PresetSubject(2, "高等代数"),
    PresetSubject(3, "大学英语"),
    PresetSubject(4, "空间解析几何"),
    PresetSubject(5, "编程"),
)

data class Subject(
    val id: Long = 0,
    val name: String,
    val isPreset: Boolean = false,
)

enum class StudyTimerMode { STOPWATCH, POMODORO, MANUAL }

enum class StudyTimerStatus { RUNNING, PAUSED, COMPLETED }

enum class TimerInterruptionReason { USER, APP_BACKGROUND, PROCESS_RECOVERY }

data class TimingSession(
    val id: Long = 0,
    val subjectId: Long,
    val taskId: Long? = null,
    val studyDate: LocalDate,
    val mode: StudyTimerMode,
    val status: StudyTimerStatus,
    val plannedDurationMinutes: Int? = null,
    val accumulatedMillis: Long = 0,
    val runningSince: Instant? = null,
    val interruptionCount: Int = 0,
    val lastInterruptionReason: TimerInterruptionReason? = null,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val correctedAt: Instant? = null,
    val note: String = "",
) {
    fun elapsedMillis(now: Instant): Long {
        if (status != StudyTimerStatus.RUNNING) return accumulatedMillis
        val segmentStart = runningSince ?: return accumulatedMillis
        val segment = (now.toEpochMilli() - segmentStart.toEpochMilli()).coerceAtLeast(0L)
        return accumulatedMillis + segment
    }

    fun remainingMillis(now: Instant): Long? {
        val planned = plannedDurationMinutes?.times(60_000L) ?: return null
        return (planned - elapsedMillis(now)).coerceAtLeast(0L)
    }
}

enum class ProblemStatus { TODO, IN_PROGRESS, COMPLETED }

enum class BlockerReason {
    CONCEPT,
    THEOREM,
    PROOF_CONSTRUCTION,
    CALCULATION,
    REPRODUCTION,
    OTHER,
}

data class Problem(
    val id: Long = 0,
    val subjectId: Long,
    val textbook: String = "",
    val chapter: String = "",
    val problemNumber: String = "",
    val knowledgePoint: String = "",
    val description: String = "",
    val status: ProblemStatus = ProblemStatus.TODO,
    val independentThinkingMinutes: Int = 0,
    val usedAiHint: Boolean = false,
    val blockerReason: BlockerReason? = null,
    val isMistake: Boolean = false,
    val isDifficult: Boolean = false,
    val independentCompleted: Boolean? = null,
    val retryCount: Int = 0,
    val secondAttemptIndependent: Boolean? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isCompleted: Boolean get() = status == ProblemStatus.COMPLETED

    val latestAttemptIndependent: Boolean
        get() = if (retryCount > 0) {
            secondAttemptIndependent ?: independentCompleted ?: false
        } else {
            independentCompleted ?: false
        }
}

enum class ProcrastinationReason {
    TOO_DIFFICULT,
    UNCLEAR_START,
    FATIGUE,
    DISTRACTION,
    EMOTION,
    OTHER,
}

data class SelfControlEntry(
    val id: Long = 0,
    val date: LocalDate,
    val procrastinationReason: ProcrastinationReason? = null,
    val voluntaryInterruption: Boolean = false,
    val note: String = "",
    val createdAt: Instant,
)

data class LearningGoals(
    val dailyStudyMinutes: Int? = null,
    val weeklyStudyMinutes: Int? = null,
    val dailyTaskTarget: Int? = null,
)

data class LearningSnapshot(
    val subjects: List<Subject> = emptyList(),
    val timings: List<TimingSession> = emptyList(),
    val problems: List<Problem> = emptyList(),
    val selfControlEntries: List<SelfControlEntry> = emptyList(),
    val goals: LearningGoals = LearningGoals(),
)

data class LearningDateRange(
    val start: LocalDate,
    val end: LocalDate,
) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

data class SubjectLearningStats(
    val subject: Subject,
    val studyMillis: Long,
    val taskTotal: Int,
    val taskCompleted: Int,
    val problemCompleted: Int,
    val difficultProblemCount: Int,
    val independentProblemCount: Int,
    val completedProblemCount: Int,
) {
    val taskCompletionRate: Float?
        get() = if (taskTotal == 0) null else taskCompleted.toFloat() / taskTotal
    val independentCompletionRate: Float?
        get() = if (completedProblemCount == 0) null else {
            independentProblemCount.toFloat() / completedProblemCount
        }
}

data class LearningSubjectAnalytics(
    val stats: List<SubjectLearningStats> = emptyList(),
)

data class SelfControlPeriodMetrics(
    val start: LocalDate,
    val end: LocalDate,
    val plannedTasks: Int,
    val completedTasks: Int,
    val plannedMinutes: Int,
    val actualMinutes: Int,
    val timerInterruptions: Int,
    val voluntaryInterruptions: Int,
) {
    val completionRate: Float?
        get() = if (plannedTasks == 0) null else completedTasks.toFloat() / plannedTasks
    val timeDeviationMinutes: Int get() = actualMinutes - plannedMinutes
}

data class DailySelfControlTrend(
    val date: LocalDate,
    val plannedTasks: Int,
    val completedTasks: Int,
    val plannedMinutes: Int,
    val actualMinutes: Int,
    val interruptions: Int,
) {
    val completionRate: Float?
        get() = if (plannedTasks == 0) null else completedTasks.toFloat() / plannedTasks
}

data class WeeklySelfControlTrend(
    val start: LocalDate,
    val end: LocalDate,
    val plannedTasks: Int,
    val completedTasks: Int,
    val actualMinutes: Int,
) {
    val completionRate: Float?
        get() = if (plannedTasks == 0) null else completedTasks.toFloat() / plannedTasks
}

data class SelfControlOverview(
    val today: SelfControlPeriodMetrics,
    val week: SelfControlPeriodMetrics,
    val dailyTrend: List<DailySelfControlTrend> = emptyList(),
    val weeklyTrend: List<WeeklySelfControlTrend> = emptyList(),
    val entries: List<SelfControlEntry> = emptyList(),
    val goals: LearningGoals = LearningGoals(),
)
