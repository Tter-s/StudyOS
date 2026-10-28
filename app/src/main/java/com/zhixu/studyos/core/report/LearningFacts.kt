package com.zhixu.studyos.core.report

import java.time.LocalDate
import java.time.YearMonth

/**
 * 周报与成长分析共用的只读事实集合。
 * 全部来自本地数据库，不含 AI 推断；时间统一为分钟，日期为当地自然日。
 */
data class LearningFactBundle(
    val tasks: List<TaskFact> = emptyList(),
    val timings: List<TimingFact> = emptyList(),
    val attempts: List<AttemptFact> = emptyList(),
    val hints: List<HintFact> = emptyList(),
    val vocabulary: List<VocabularyFact> = emptyList(),
    val vocabularyReviews: List<VocabularyReviewFact> = emptyList(),
    val sleep: List<SleepFact> = emptyList(),
    val selfControl: List<SelfControlFact> = emptyList(),
    val transactions: List<TransactionFact> = emptyList(),
    val goals: List<GoalFact> = emptyList(),
    val milestones: List<MilestoneFact> = emptyList(),
) {
    fun emptyFor(range: DateRange): Boolean = tasks.none {
        it.scheduledDate.inRange(range) || it.completedDate.inRange(range)
    } &&
        timings.none { it.date in range } &&
        attempts.none { it.date in range } &&
        vocabularyReviews.none { it.date in range } &&
        sleep.none { it.date in range } &&
        transactions.none { it.date in range }
}

data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    val days: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1
}

data class TaskSessionFact(
    val date: LocalDate,
    val minutes: Int,
)

data class TaskFact(
    val id: Long,
    val title: String,
    val subject: String,
    val estimatedMinutes: Int,
    val scheduledMinutes: Int,
    val scheduledDate: LocalDate?,
    val completed: Boolean,
    val completedDate: LocalDate?,
    val dueDate: LocalDate?,
    val postponeCount: Int,
    val sourceType: String,
    /** 每个学习时段；跨周任务必须按时段日期归属，不能只看 scheduledDate。 */
    val sessions: List<TaskSessionFact> = emptyList(),
) {
    val isOverdue: Boolean
        get() = !completed && dueDate != null && dueDate.isBefore(LocalDate.now())

    fun hasScheduleIn(range: DateRange): Boolean =
        if (sessions.isNotEmpty()) sessions.any { it.date in range } else scheduledDate.inRange(range)

    fun hasSessionOn(date: LocalDate): Boolean =
        if (sessions.isNotEmpty()) sessions.any { it.date == date } else scheduledDate == date

    fun plannedMinutesIn(range: DateRange): Int =
        if (sessions.isNotEmpty()) {
            sessions.filter { it.date in range }.sumOf { it.minutes }
        } else {
            scheduledMinutes
        }
}

data class TimingFact(
    val date: LocalDate,
    val subject: String,
    val minutes: Int,
)

data class AttemptFact(
    val date: LocalDate,
    val subject: String,
    val chapter: String,
    val independent: Boolean,
    val correct: Boolean,
    val highestHintLevel: Int,
    val durationMinutes: Int,
    val attemptIndex: Int,
)

data class HintFact(
    val date: LocalDate,
    val level: Int,
    val usedCache: Boolean,
)

data class VocabularyFact(
    val id: Long,
    val word: String,
    val listName: String,
    val createdAt: LocalDate,
    val status: String,
)

data class VocabularyReviewFact(
    val date: LocalDate,
    val recalled: Boolean,
    val rating: String,
)

data class SleepFact(
    val date: LocalDate,
    val durationMinutes: Long?,
    val quality: Int?,
    val nextDayEnergy: Int?,
)

data class SelfControlFact(
    val date: LocalDate,
    val interruptions: Int,
    val voluntaryInterruptions: Int,
    val procrastinationRecorded: Boolean,
)

data class TransactionFact(
    val date: LocalDate,
    val netSpentFen: Long,
    val categoryName: String,
)

data class GoalFact(
    val id: Long,
    val title: String,
    val category: String,
    val status: String,
    val startDate: LocalDate,
    val targetDate: LocalDate?,
)

data class MilestoneFact(
    val goalId: Long,
    val completed: Boolean,
    val doneDate: LocalDate?,
    val weight: Int,
)

/** 跨周期（月/学期/学年）汇总使用的通用桶。 */
data class PeriodBucket(
    val label: String,
    val start: LocalDate,
    val end: LocalDate,
    val studyMinutes: Int,
    val completedTasks: Int,
    val plannedTasks: Int,
    val exerciseAttempts: Int,
    val independentExercises: Int,
    val vocabularyReviews: Int,
    val netSpentFen: Long,
) {
    val completionRatePercent: Int?
        get() = if (plannedTasks == 0) null else (completedTasks * 100 / plannedTasks).coerceIn(0, 100)
    val independentRatePercent: Int?
        get() = if (exerciseAttempts == 0) null else {
            (independentExercises * 100 / exerciseAttempts).coerceIn(0, 100)
        }
}

fun monthRange(month: YearMonth): DateRange = DateRange(month.atDay(1), month.atEndOfMonth())

fun LocalDate?.inRange(range: DateRange): Boolean = this != null && this in range
