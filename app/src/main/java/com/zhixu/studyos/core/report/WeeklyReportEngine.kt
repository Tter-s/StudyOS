package com.zhixu.studyos.core.report

import com.zhixu.studyos.core.finance.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.sqrt
import org.json.JSONObject

enum class ReportMode { OFFLINE, AI }

data class SubjectWeekStat(
    val subject: String,
    val studyMinutes: Int,
    val scheduledTasks: Int,
    val completedTasks: Int,
    val exerciseAttempts: Int,
    val independentExercises: Int,
) {
    val completionRatePercent: Int?
        get() = if (scheduledTasks == 0) null else (completedTasks * 100 / scheduledTasks).coerceIn(0, 100)
    val independentRatePercent: Int?
        get() = if (exerciseAttempts == 0) null else {
            (independentExercises * 100 / exerciseAttempts).coerceIn(0, 100)
        }
}

data class DailyWeekStat(
    val date: LocalDate,
    val studyMinutes: Int,
    val completedTasks: Int,
    val scheduledTasks: Int,
)

data class ReportMetric(
    val label: String,
    val value: String?,
    val definition: String,
) {
    val available: Boolean get() = value != null
}

data class WeeklyReport(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val generatedAt: java.time.Instant,
    val mode: ReportMode,
    val overview: ReportOverview,
    val subjects: List<SubjectWeekStat>,
    val daily: List<DailyWeekStat>,
    val planExecution: PlanExecution,
    val exercise: ExerciseWeek,
    val vocabulary: VocabularyWeek,
    val sleep: SleepWeek,
    val selfControl: SelfControlWeek,
    val finance: FinanceWeek,
    val backlog: BacklogWeek,
    val metrics: List<ReportMetric>,
    val suggestions: List<String>,
    val dataNotes: List<String>,
    val aiSummary: String? = null,
    val aiSections: AiReportSections? = null,
)

data class ReportOverview(
    val studyMinutes: Int,
    val activeDays: Int,
    val scheduledTasks: Int,
    val completedTasks: Int,
    val completionRatePercent: Int?,
    val exerciseAttempts: Int,
    val independentRatePercent: Int?,
    val vocabularyReviews: Int,
    val averageSleepMinutes: Long?,
)

data class PlanExecution(
    val plannedMinutes: Int,
    val actualMinutes: Int,
    val deviationMinutes: Int,
    val deviationPercent: Int?,
) {
    val interpretation: String
        get() = when {
            plannedMinutes == 0 -> "本周没有已保存的计划时长，无法计算执行偏差"
            deviationPercent == null -> "计划时长为 0，无法计算偏差比例"
            deviationPercent > 20 -> "实际学习时长高于计划；可能包含额外学习，也可能计划偏保守"
            deviationPercent < -20 -> "实际学习时长低于计划；可检查任务是否过大或安排过密"
            else -> "实际学习时长与计划接近"
        }
}

data class ExerciseWeek(
    val attempts: Int,
    val firstAttempts: Int,
    val independentAttempts: Int,
    val independentRatePercent: Int?,
    val firstAttemptIndependentRatePercent: Int?,
    val hintUseCount: Int,
    val averageHintLevel: Double?,
    val difficultAttempts: Int,
    val difficultSuccessRatePercent: Int?,
)

data class VocabularyWeek(
    val reviews: Int,
    val recalled: Int,
    val recallRatePercent: Int?,
    val newWords: Int,
)

data class SleepWeek(
    val recordedDays: Int,
    val averageDurationMinutes: Long?,
    val averageQuality: Double?,
    val averageEnergy: Double?,
    val regularityMinutes: Long?,
)

data class SelfControlWeek(
    val interruptions: Int,
    val voluntaryInterruptions: Int,
    val procrastinationRecords: Int,
)

data class FinanceWeek(
    val netSpentFen: Long,
    val recordedDays: Int,
    val averageDailyFen: Long?,
)

data class BacklogWeek(
    val postponedTasks: Int,
    val longBacklogTasks: Int,
    val overdueTasks: Int,
)

data class AiReportSections(
    val facts: List<String>,
    val speculation: List<String>,
    val suggestions: List<String>,
    val disclaimer: String = "AI 内容仅为参考，不能替代你的判断；[推测] 不是已证明的事实。",
)

object WeeklyReportEngine {
    fun weekRange(date: LocalDate): DateRange {
        val start = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return DateRange(start, start.plusDays(6))
    }

    fun build(
        facts: LearningFactBundle,
        weekStart: LocalDate,
        generatedAt: java.time.Instant = java.time.Instant.now(),
        mode: ReportMode = ReportMode.OFFLINE,
    ): WeeklyReport {
        val range = DateRange(weekStart, weekStart.plusDays(6))
        val timings = facts.timings.filter { it.date in range }
        val attempts = facts.attempts.filter { it.date in range }
        val hints = facts.hints.filter { it.date in range }
        val reviews = facts.vocabularyReviews.filter { it.date in range }
        val sleep = facts.sleep.filter { it.date in range }
        val selfControl = facts.selfControl.filter { it.date in range }
        val transactions = facts.transactions.filter { it.date in range }

        val scheduledTasks = facts.tasks.filter { it.hasScheduleIn(range) }
        val completedTasks = scheduledTasks.count { it.completed }
        val completionRate = if (scheduledTasks.isEmpty()) {
            null
        } else {
            (completedTasks * 100 / scheduledTasks.size).coerceIn(0, 100)
        }
        val studyMinutes = timings.sumOf { it.minutes }
        val activeDays = timings.map { it.date }.distinct().size
        val independentAttempts = attempts.count { it.independent }
        val independentRate = if (attempts.isEmpty()) NonePercent else {
            (independentAttempts * 100 / attempts.size).coerceIn(0, 100)
        }
        val firstAttempts = attempts.filter { it.attemptIndex == 1 }
        val firstIndependent = firstAttempts.count { it.independent }
        val firstIndependentRate = if (firstAttempts.isEmpty()) NonePercent else {
            (firstIndependent * 100 / firstAttempts.size).coerceIn(0, 100)
        }
        val hintUse = attempts.count { it.highestHintLevel > 0 }
        val avgHintLevel = if (attempts.isEmpty()) NoneDouble else {
            roundOne(attempts.sumOf { it.highestHintLevel }.toDouble() / attempts.size)
        }
        val difficult = attempts.filter { it.highestHintLevel >= 3 }
        val difficultSuccess = if (difficult.isEmpty()) NonePercent else {
            (difficult.count { it.correct } * 100 / difficult.size).coerceIn(0, 100)
        }
        val recalled = reviews.count { it.recalled }
        val recallRate = if (reviews.isEmpty()) NonePercent else {
            (recalled * 100 / reviews.size).coerceIn(0, 100)
        }
        val newWords = facts.vocabulary.count { it.createdAt in range }
        val sleepDurations = sleep.mapNotNull { it.durationMinutes }
        val sleepAverage = sleepDurations.takeIf { it.isNotEmpty() }?.average()?.toLong()
        val sleepQuality = sleep.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average()
        val sleepEnergy = sleep.mapNotNull { it.nextDayEnergy }.takeIf { it.isNotEmpty() }?.average()
        val sleepRegularity = if (sleepDurations.size < 3) {
            null
        } else {
            val mean = sleepDurations.average()
            sqrt(sleepDurations.sumOf { (it - mean) * (it - mean) } / sleepDurations.size).toLong()
        }
        val plannedMinutes = scheduledTasks.sumOf { it.plannedMinutesIn(range) }
        val netSpent = transactions.sumOf { it.netSpentFen }
        val recordedDays = transactions.map { it.date }.distinct().size
        val daily = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            DailyWeekStat(
                date = date,
                studyMinutes = timings.filter { it.date == date }.sumOf { it.minutes },
                completedTasks = scheduledTasks.count { it.completed && it.completedDate == date },
                scheduledTasks = scheduledTasks.count { it.hasSessionOn(date) },
            )
        }
        val subjects = (timings.map { it.subject } + scheduledTasks.map { it.subject } +
            attempts.map { it.subject }).filter(String::isNotBlank).distinct().sorted().map { subject ->
            val subjectTimings = timings.filter { it.subject == subject }
            val subjectTasks = scheduledTasks.filter { it.subject == subject }
            val subjectAttempts = attempts.filter { it.subject == subject }
            SubjectWeekStat(
                subject = subject,
                studyMinutes = subjectTimings.sumOf { it.minutes },
                scheduledTasks = subjectTasks.size,
                completedTasks = subjectTasks.count { it.completed },
                exerciseAttempts = subjectAttempts.size,
                independentExercises = subjectAttempts.count { it.independent },
            )
        }
        val overview = ReportOverview(
            studyMinutes = studyMinutes,
            activeDays = activeDays,
            scheduledTasks = scheduledTasks.size,
            completedTasks = completedTasks,
            completionRatePercent = completionRate,
            exerciseAttempts = attempts.size,
            independentRatePercent = independentRate,
            vocabularyReviews = reviews.size,
            averageSleepMinutes = sleepAverage,
        )
        val planExecution = PlanExecution(
            plannedMinutes = plannedMinutes,
            actualMinutes = studyMinutes,
            deviationMinutes = studyMinutes - plannedMinutes,
            deviationPercent = if (plannedMinutes == 0) null else {
                ((studyMinutes - plannedMinutes) * 100.0 / plannedMinutes).let {
                    if (it >= 0) Math.round(it).toInt() else -Math.round(-it).toInt()
                }
            },
        )
        val exercise = ExerciseWeek(
            attempts = attempts.size,
            firstAttempts = firstAttempts.size,
            independentAttempts = independentAttempts,
            independentRatePercent = independentRate,
            firstAttemptIndependentRatePercent = firstIndependentRate,
            hintUseCount = hintUse,
            averageHintLevel = avgHintLevel,
            difficultAttempts = difficult.size,
            difficultSuccessRatePercent = difficultSuccess,
        )
        val vocabulary = VocabularyWeek(
            reviews = reviews.size,
            recalled = recalled,
            recallRatePercent = recallRate,
            newWords = newWords,
        )
        val sleepWeek = SleepWeek(
            recordedDays = sleepDurations.size,
            averageDurationMinutes = sleepAverage,
            averageQuality = sleepQuality?.let(::roundOne),
            averageEnergy = sleepEnergy?.let(::roundOne),
            regularityMinutes = sleepRegularity,
        )
        val selfControlWeek = SelfControlWeek(
            interruptions = selfControl.sumOf { it.interruptions },
            voluntaryInterruptions = selfControl.sumOf { it.voluntaryInterruptions },
            procrastinationRecords = selfControl.count { it.procrastinationRecorded },
        )
        val financeWeek = FinanceWeek(
            netSpentFen = netSpent,
            recordedDays = recordedDays,
            averageDailyFen = if (recordedDays == 0) null else netSpent / recordedDays,
        )
        val allPostponed = facts.tasks.filter { it.hasScheduleIn(range) || it.completedDate.inRange(range) }
        val backlog = BacklogWeek(
            postponedTasks = allPostponed.count { it.postponeCount > 0 },
            longBacklogTasks = facts.tasks.count { !it.completed && it.postponeCount >= 3 },
            overdueTasks = facts.tasks.count {
                !it.completed && it.dueDate != null && it.dueDate.isBefore(weekStart.plusDays(7))
            },
        )
        val metrics = listOf(
            ReportMetric(
                label = "计划完成率",
                value = completionRate?.let { "$it%" },
                definition = "本周已安排任务中标记为完成的数量 / 本周已安排任务总数；没有已安排任务时显示数据不足。",
            ),
            ReportMetric(
                label = "独立完成率",
                value = independentRate?.let { "$it%" },
                definition = "未使用提示且独立完成的习题尝试次数 / 有效习题尝试总次数；没有尝试时显示数据不足。",
            ),
            ReportMetric(
                label = "首次尝试独立率",
                value = firstIndependentRate?.let { "$it%" },
                definition = "每道题第一次尝试中未使用提示且独立完成的比例。",
            ),
            ReportMetric(
                label = "计划时长偏差",
                value = planExecution.deviationPercent?.let { "$it%" },
                definition = "（本周计时学习分钟 − 本周已安排任务计划分钟） / 计划分钟；仅统计已保存计划与真实计时，不等同于效率。",
            ),
            ReportMetric(
                label = "词汇回忆率",
                value = recallRate?.let { "$it%" },
                definition = "本周词汇复习中评分不是“忘记”的次数 / 本周复习总次数。",
            ),
            ReportMetric(
                label = "平均睡眠时长",
                value = sleepAverage?.let { "${it / 60} 小时 ${it % 60} 分" },
                definition = "本周有手动记录的睡眠记录的平均实际时长；未记录的天数不参与计算。",
            ),
        )
        val suggestions = buildSuggestions(
            completionRate = completionRate,
            attempts = attempts,
            independentRate = independentRate,
            hintUse = hintUse,
            sleepAverage = sleepAverage,
            backlog = backlog,
            studyMinutes = studyMinutes,
            reviews = reviews.size,
            recallRate = recallRate,
        )
        val notes = buildNotes(
            scheduledTasks = scheduledTasks.size,
            attempts = attempts.size,
            reviews = reviews.size,
            sleepDurations = sleepDurations.size,
            timings = timings.size,
        )
        return WeeklyReport(
            weekStart = weekStart,
            weekEnd = weekStart.plusDays(6),
            generatedAt = generatedAt,
            mode = mode,
            overview = overview,
            subjects = subjects,
            daily = daily,
            planExecution = planExecution,
            exercise = exercise,
            vocabulary = vocabulary,
            sleep = sleepWeek,
            selfControl = selfControlWeek,
            finance = financeWeek,
            backlog = backlog,
            metrics = metrics,
            suggestions = suggestions,
            dataNotes = notes,
        )
    }

    fun parseAiSections(text: String): AiReportSections {
        val factsList = mutableListOf<String>()
        val speculation = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("-").trim()
            if (line.isBlank()) return@forEach
            when {
                line.contains("[数据]") || line.contains("【数据】") ->
                    factsList += line.replace("[数据]", "").replace("【数据】", "").trim()
                line.contains("[推测]") || line.contains("【推测】") ->
                    speculation += line.replace("[推测]", "").replace("【推测】", "").trim()
                line.contains("[建议]") || line.contains("【建议】") ->
                    suggestions += line.replace("[建议]", "").replace("【建议】", "").trim()
                else -> suggestions += line
            }
        }
        return AiReportSections(
            facts = factsList,
            speculation = speculation,
            suggestions = suggestions,
        )
    }

    private fun buildSuggestions(
        completionRate: Int?,
        attempts: List<AttemptFact>,
        independentRate: Int?,
        hintUse: Int,
        sleepAverage: Long?,
        backlog: BacklogWeek,
        studyMinutes: Int,
        reviews: Int,
        recallRate: Int?,
    ): List<String> {
        val result = mutableListOf<String>()
        if (completionRate != null && completionRate < 60) {
            result += "本周计划完成率为 $completionRate%。可以把大任务拆成更小的时段，或减少同一天的任务数量，不需要靠延长学习时间解决。"
        }
        if (attempts.size >= 3 && independentRate != null && independentRate < 50) {
            result += "本周独立完成率为 $independentRate%，提示使用 $hintUse 次。可以先给自己一段独立的思考时间，再按级查看提示。"
        }
        if (sleepAverage != null && sleepAverage < 7 * 60) {
            result += "本周已记录的平均睡眠为 ${sleepAverage / 60} 小时 ${sleepAverage % 60} 分。睡眠与学习表现的关系仍需你自己观察，建议优先保护睡眠时段。"
        } else if (sleepAverage == null) {
            result += "本周尚未记录睡眠，成长分析中的睡眠关联会显示数据不足；可在睡眠页手动补录。"
        }
        if (backlog.longBacklogTasks > 0) {
            result += "有 ${backlog.longBacklogTasks} 项任务已多次延期。可以选择完成、改期或删除，而不是继续堆积。"
        }
        if (studyMinutes == 0 && attempts.isEmpty()) {
            result += "本周没有计时或习题记录。下周可以只设一个很小的起点，例如一次 20 分钟的专注。"
        }
        if (reviews == 0 && attempts.size > 0) {
            result += "本周没有英语词汇复习记录；如果这不是有意安排，可设置每日新词/复习上限后生成一个小任务。"
        } else if (recallRate != null && recallRate < 60) {
            result += "词汇回忆率为 $recallRate%，可适当减少每日新词数量，把时间留给复习。"
        }
        if (result.isEmpty()) {
            result += "保留当前节奏即可；下周继续观察计划完成率与独立完成率的变化，不需要额外加码。"
        }
        return result
    }

    private fun buildNotes(
        scheduledTasks: Int,
        attempts: Int,
        reviews: Int,
        sleepDurations: Int,
        timings: Int,
    ): List<String> {
        val notes = mutableListOf<String>()
        if (scheduledTasks == 0) notes += "本周没有已保存的任务安排，计划完成率显示为数据不足。"
        if (attempts == 0) notes += "本周没有习题尝试，独立完成率与提示使用无法计算。"
        if (reviews == 0) notes += "本周没有词汇复习记录。"
        if (sleepDurations == 0) notes += "本周没有睡眠记录。"
        if (timings == 0) notes += "本周没有学习计时记录。"
        notes += "周报只使用本地记录；未记录的学习不会自动出现在统计中。"
        return notes
    }

    private val NonePercent: Int? = null
    private val NoneDouble: Double? = null

    private fun roundOne(value: Double): Double = Math.round(value * 10.0) / 10.0

    fun monthBuckets(facts: LearningFactBundle, months: List<YearMonth>): List<PeriodBucket> =
        months.map { month -> bucketFor(monthRange(month), month.toString(), facts) }

    fun customBucket(range: DateRange, label: String, facts: LearningFactBundle): PeriodBucket =
        bucketFor(range, label, facts)

    private fun bucketFor(range: DateRange, label: String, facts: LearningFactBundle): PeriodBucket {
        val timings = facts.timings.filter { it.date in range }
        val scheduled = facts.tasks.filter { it.hasScheduleIn(range) }
        val attempts = facts.attempts.filter { it.date in range }
        val reviews = facts.vocabularyReviews.filter { it.date in range }
        val spent = facts.transactions.filter { it.date in range }.sumOf { it.netSpentFen }
        return PeriodBucket(
            label = label,
            start = range.start,
            end = range.end,
            studyMinutes = timings.sumOf { it.minutes },
            completedTasks = scheduled.count { it.completed },
            plannedTasks = scheduled.size,
            exerciseAttempts = attempts.size,
            independentExercises = attempts.count { it.independent },
            vocabularyReviews = reviews.size,
            netSpentFen = spent,
        )
    }

    fun daysBetween(start: LocalDate, end: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, end) + 1).toInt()
}

/** 周报持久化只保存摘要与 AI 总结，原始统计始终可以重新计算。 */
object WeeklyReportJson {
    fun encode(report: WeeklyReport): String = JSONObject()
        .put("weekStart", report.weekStart.toString())
        .put("weekEnd", report.weekEnd.toString())
        .put("generatedAt", report.generatedAt.toString())
        .put("mode", report.mode.name)
        .put("studyMinutes", report.overview.studyMinutes)
        .put("completedTasks", report.overview.completedTasks)
        .put("scheduledTasks", report.overview.scheduledTasks)
        .put("completionRate", report.overview.completionRatePercent ?: JSONObject.NULL)
        .put("exerciseAttempts", report.overview.exerciseAttempts)
        .put("independentRate", report.overview.independentRatePercent ?: JSONObject.NULL)
        .put("vocabularyReviews", report.overview.vocabularyReviews)
        .put("averageSleepMinutes", report.overview.averageSleepMinutes ?: JSONObject.NULL)
        .put("netSpentFen", report.finance.netSpentFen)
        .put("suggestions", org.json.JSONArray(report.suggestions))
        .put("aiSummary", report.aiSummary ?: JSONObject.NULL)
        .toString()

    fun summaryText(report: WeeklyReport): String = buildString {
        append("周报 ${report.weekStart} 至 ${report.weekEnd}：")
        append("学习 ${report.overview.studyMinutes} 分钟，")
        append("计划完成 ${report.overview.completedTasks}/${report.overview.scheduledTasks}")
        report.overview.completionRatePercent?.let { append("（$it%）") }
        append("；习题尝试 ${report.overview.exerciseAttempts} 次")
        report.overview.independentRatePercent?.let { append("，独立完成率 $it%") }
        append("；词汇复习 ${report.overview.vocabularyReviews} 次")
        report.sleep.averageDurationMinutes?.let { append("；平均睡眠 ${it / 60} 小时 ${it % 60} 分") }
        append("。")
    }

    fun moneySummary(fen: Long): String = Money.formatFen(fen)
}
