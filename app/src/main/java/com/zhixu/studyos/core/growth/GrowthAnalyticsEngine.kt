package com.zhixu.studyos.core.growth

import com.zhixu.studyos.core.finance.Money
import com.zhixu.studyos.core.report.DateRange
import com.zhixu.studyos.core.report.LearningFactBundle
import com.zhixu.studyos.core.report.PeriodBucket
import com.zhixu.studyos.core.report.WeeklyReportEngine
import com.zhixu.studyos.core.report.inRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.sqrt

enum class GrowthRangeType(val label: String) {
    DAY("今日"),
    WEEK("本周"),
    MONTH("本月"),
    SEMESTER("本学期"),
    ACADEMIC_YEAR("本学年"),
    CUSTOM("自定义"),
}

data class GrowthRange(
    val type: GrowthRangeType,
    val start: LocalDate,
    val end: LocalDate,
) {
    val label: String
        get() = when (type) {
            GrowthRangeType.DAY -> "今日"
            GrowthRangeType.WEEK -> "本周（${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}）"
            GrowthRangeType.MONTH -> "${start.year} 年 ${start.monthValue} 月"
            GrowthRangeType.SEMESTER -> "本学期（${start} 起）"
            GrowthRangeType.ACADEMIC_YEAR -> "本学年（${start.year} 秋季起）"
            GrowthRangeType.CUSTOM -> "${start.monthValue}/${start.dayOfMonth}–${end.monthValue}/${end.dayOfMonth}"
        }
}

data class GrowthMetric(
    val key: String,
    val label: String,
    val value: String?,
    val definition: String,
    val detail: String = "",
) {
    val dataSufficient: Boolean get() = value != null
}

data class SubjectShare(
    val subject: String,
    val minutes: Int,
    val sharePercent: Int,
)

data class ExerciseTrendPoint(
    val label: String,
    val attempts: Int,
    val independentRatePercent: Int?,
    val averageHintLevel: Double?,
)

data class VocabularyGrowthPoint(
    val label: String,
    val reviews: Int,
    val recalled: Int,
    val newWords: Int,
)

data class SleepTrendPoint(
    val label: String,
    val averageMinutes: Long?,
    val recordedDays: Int,
    val regularityMinutes: Long?,
)

data class FinanceGrowthPoint(
    val label: String,
    val netSpentFen: Long,
    val averageDailyFen: Long?,
)

data class GoalGrowthSummary(
    val activeGoals: Int,
    val completedGoals: Int,
    val milestonesCompletedInRange: Int,
    val totalMilestones: Int,
    val progressPercent: Int?,
)

data class SemesterBucket(
    val label: String,
    val bucket: PeriodBucket,
    val notes: List<String>,
)

data class GrowthAnalytics(
    val range: GrowthRange,
    val metrics: List<GrowthMetric>,
    val subjectShares: List<SubjectShare>,
    val monthlyTrend: List<PeriodBucket>,
    val exerciseTrend: List<ExerciseTrendPoint>,
    val vocabularyTrend: List<VocabularyGrowthPoint>,
    val sleepTrend: List<SleepTrendPoint>,
    val financeTrend: List<FinanceGrowthPoint>,
    val goals: GoalGrowthSummary,
    val semesterComparison: List<SemesterBucket>,
    val notes: List<String>,
)

/**
 * 跨学期个人成长分析。所有指标都有明确定义；样本不足时返回 null 并显示“数据不足”，
 * 不用 0 或虚构趋势填充。所有趋势都可以回溯到本地真实记录。
 */
object GrowthAnalyticsEngine {

    fun currentRange(
        type: GrowthRangeType,
        today: LocalDate,
        semesterStart: LocalDate? = null,
        customStart: LocalDate? = today.minusDays(29),
        customEnd: LocalDate? = today,
    ): GrowthRange {
        val (start, end) = when (type) {
            GrowthRangeType.DAY -> today to today
            GrowthRangeType.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
            GrowthRangeType.MONTH -> today.withDayOfMonth(1) to today
            GrowthRangeType.SEMESTER -> (semesterStart?.takeIf { !it.isAfter(today) } ?: naturalSemesterStart(today)) to today
            GrowthRangeType.ACADEMIC_YEAR -> naturalAcademicYearStart(today) to today
            GrowthRangeType.CUSTOM -> {
                val startValue = customStart ?: today.minusDays(29)
                val endValue = customEnd ?: today
                if (endValue.isBefore(startValue)) startValue to startValue else startValue to endValue
            }
        }
        return GrowthRange(type = type, start = start, end = end)
    }

    fun naturalSemesterStart(today: LocalDate): LocalDate {
        val month = today.monthValue
        return when {
            month >= 9 -> LocalDate.of(today.year, 9, 1)
            month == 1 -> LocalDate.of(today.year - 1, 9, 1)
            month in 2..7 -> LocalDate.of(today.year, 2, 1)
            else -> LocalDate.of(today.year, 2, 1) // 8 月：当前学期尚未开始，回退到春季学期
        }
    }

    fun naturalAcademicYearStart(today: LocalDate): LocalDate {
        val year = if (today.monthValue >= 9) today.year else today.year - 1
        return LocalDate.of(year, 9, 1)
    }

    fun build(
        facts: LearningFactBundle,
        range: GrowthRange,
        today: LocalDate,
        semesterStart: LocalDate? = null,
    ): GrowthAnalytics {
        val dateRange = DateRange(range.start, range.end)
        val scheduled = facts.tasks.filter { it.hasScheduleIn(dateRange) }
        val timings = facts.timings.filter { it.date in dateRange }
        val attempts = facts.attempts.filter { it.date in dateRange }
        val reviews = facts.vocabularyReviews.filter { it.date in dateRange }
        val sleep = facts.sleep.filter { it.date in dateRange }
        val transactions = facts.transactions.filter { it.date in dateRange }
        val milestones = facts.milestones.filter { it.doneDate.inRange(dateRange) }
        val days = dateRange.days.coerceAtLeast(1)
        val studyMinutes = timings.sumOf { it.minutes }
        val activeDays = timings.map { it.date }.distinct().size
        val completionRate = if (scheduled.isEmpty()) null else {
            (scheduled.count { it.completed } * 100 / scheduled.size).coerceIn(0, 100)
        }
        val independentRate = if (attempts.isEmpty()) null else {
            (attempts.count { it.independent } * 100 / attempts.size).coerceIn(0, 100)
        }
        val hintUseRate = if (attempts.isEmpty()) null else {
            (attempts.count { it.highestHintLevel > 0 } * 100 / attempts.size).coerceIn(0, 100)
        }
        val avgHintLevel = if (attempts.isEmpty()) null else {
            Math.round(attempts.sumOf { it.highestHintLevel }.toDouble() / attempts.size * 10) / 10.0
        }
        val plannedMinutes = scheduled.sumOf { it.plannedMinutesIn(dateRange) }
        val estimationAccuracy = if (plannedMinutes == 0) null else {
            val deviationPercent = abs(studyMinutes - plannedMinutes) * 100.0 / plannedMinutes
            (100 - deviationPercent).coerceIn(0.0, 100.0).let { Math.round(it).toInt() }
        }
        val vocabularyTotal = facts.vocabulary.size
        val learnedWords = facts.vocabulary.count { it.status != "NEW" }
        val recallRate = if (reviews.isEmpty()) null else {
            (reviews.count { it.recalled } * 100 / reviews.size).coerceIn(0, 100)
        }
        val sleepDurations = sleep.mapNotNull { it.durationMinutes }
        val avgSleep = sleepDurations.takeIf { it.size >= 2 }?.average()?.toLong()
        val sleepRegularity = if (sleepDurations.size < 3) null else {
            val mean = sleepDurations.average()
            sqrt(sleepDurations.sumOf { (it - mean) * (it - mean) } / sleepDurations.size).toLong()
        }
        val netSpent = transactions.sumOf { it.netSpentFen }
        val financeDays = transactions.map { it.date }.distinct().size
        val goalProgress = buildGoalSummary(facts, dateRange)
        val subjectMinutes = timings.groupBy { it.subject }
            .mapValues { (_, values) -> values.sumOf { it.minutes } }
        val totalSubjectMinutes = subjectMinutes.values.sum()
        val subjectShares = subjectMinutes.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { (subject, minutes) ->
                SubjectShare(
                    subject = subject,
                    minutes = minutes,
                    sharePercent = if (totalSubjectMinutes == 0) 0 else {
                        (minutes * 100 + totalSubjectMinutes / 2) / totalSubjectMinutes
                    },
                )
            }
        val programmingMinutes = subjectMinutes.entries
            .filter { it.key.contains("编程") || it.key.contains("程序") }
            .sumOf { it.value }
        val mathSubjects = setOf("数学分析", "高等代数", "空间解析几何")
        val mathAttempts = attempts.filter { it.subject in mathSubjects }
        val mathIndependentRate = if (mathAttempts.isEmpty()) null else {
            (mathAttempts.count { it.independent } * 100 / mathAttempts.size).coerceIn(0, 100)
        }
        val metrics = listOf(
            GrowthMetric(
                key = "study_minutes",
                label = "学习总时长",
                value = "$studyMinutes 分钟",
                definition = "所选区间内所有已完成计时的累计时长（含番茄钟与补录），不含未开始的学习计划。",
                detail = "活跃 $activeDays 天，日均 ${studyMinutes / days} 分钟",
            ),
            GrowthMetric(
                key = "task_completion",
                label = "任务完成率",
                value = completionRate?.let { "$it%" },
                definition = "区间内已安排任务中完成数 / 已安排任务数；未安排任务不计入分母。",
                detail = "已安排 ${scheduled.size}，完成 ${scheduled.count { it.completed }}",
            ),
            GrowthMetric(
                key = "independent_rate",
                label = "独立完成率",
                value = independentRate?.let { "$it%" },
                definition = "未使用提示且独立完成的习题尝试数 / 有效习题尝试总数；没有尝试时显示数据不足。",
                detail = "尝试 ${attempts.size} 次，使用提示的尝试 ${
                    attempts.count { it.highestHintLevel > 0 }
                } 次",
            ),
            GrowthMetric(
                key = "hint_dependency",
                label = "提示使用率",
                value = hintUseRate?.let { "$it%" },
                definition = "使用过任意等级 AI 提示的习题尝试数 / 有效尝试总数；仅统计用户主动请求的提示。",
                detail = avgHintLevel?.let { "平均最高提示等级 $it" } ?: "没有提示记录",
            ),
            GrowthMetric(
                key = "estimation_accuracy",
                label = "时间估计准确性",
                value = estimationAccuracy?.let { "$it 分" },
                definition = "100 − |实际计时分钟 − 已安排计划分钟| / 计划分钟 × 100；没有计划时显示数据不足。这不是学习效率分数。",
                detail = "计划 $plannedMinutes 分钟，实际 $studyMinutes 分钟",
            ),
            GrowthMetric(
                key = "vocabulary",
                label = "词汇积累",
                value = if (vocabularyTotal == 0) null else "$learnedWords / $vocabularyTotal",
                definition = "已开始学习（状态不是“新词”）的词汇数 / 词库总词数；仅统计本地词表。",
                detail = "区间内复习 ${reviews.size} 次" +
                    (recallRate?.let { "，回忆率 $it%" } ?: ""),
            ),
            GrowthMetric(
                key = "math_independent",
                label = "数学独立解题能力",
                value = mathIndependentRate?.let { "$it%" },
                definition = "数学分析、高等代数、空间解析几何习题中独立完成的尝试比例；没有尝试时显示数据不足。",
                detail = "数学尝试 ${mathAttempts.size} 次",
            ),
            GrowthMetric(
                key = "sleep_average",
                label = "平均睡眠",
                value = avgSleep?.let { "${it / 60} 小时 ${it % 60} 分" },
                definition = "区间内有实际入睡/起床记录的睡眠平均时长；未记录的天数不参与计算。",
                detail = sleepRegularity?.let { "睡眠时长波动约 ±$it 分钟" } ?: "记录不足以分析规律",
            ),
            GrowthMetric(
                key = "finance",
                label = "生活费净支出",
                value = if (financeDays == 0) null else Money.formatFen(netSpent),
                definition = "区间内支出减退款；收入不计入。仅统计记账记录，不是实际银行卡流水。",
                detail = if (financeDays == 0) "没有记账记录" else {
                    "有记录 $financeDays 天，日均 ${Money.formatFen(netSpent / financeDays)}"
                },
            ),
            GrowthMetric(
                key = "programming",
                label = "编程学习投入",
                value = if (programmingMinutes == 0) null else "$programmingMinutes 分钟",
                definition = "科目名称包含“编程”或“程序”的计时时长；没有此类科目时显示数据不足。",
                detail = "来自真实计时记录",
            ),
            GrowthMetric(
                key = "goal_progress",
                label = "长期目标进度",
                value = goalProgress.progressPercent?.let { "$it%" },
                definition = "已完成里程碑权重 / 全部里程碑权重；没有里程碑时显示数据不足。",
                detail = "活跃目标 ${goalProgress.activeGoals} 个",
            ),
        )
        val months = (0..11).map { offset ->
            YearMonth.from(range.end).minusMonths(offset.toLong())
        }.reversed()
        val monthlyTrend = months.map { month ->
            WeeklyReportEngine.customBucket(
                DateRange(month.atDay(1), month.atEndOfMonth()),
                "${month.year}-${"%02d".format(month.monthValue)}",
                facts,
            )
        }
        val exerciseTrend = bucketTrend(dateRange, facts) { subRange ->
            val bucketAttempts = facts.attempts.filter { it.date in subRange }
            ExerciseTrendPoint(
                label = "${subRange.start.monthValue}/${subRange.start.dayOfMonth}",
                attempts = bucketAttempts.size,
                independentRatePercent = if (bucketAttempts.isEmpty()) null else {
                    (bucketAttempts.count { it.independent } * 100 / bucketAttempts.size).coerceIn(0, 100)
                },
                averageHintLevel = if (bucketAttempts.isEmpty()) null else {
                    Math.round(bucketAttempts.sumOf { it.highestHintLevel }.toDouble() /
                        bucketAttempts.size * 10) / 10.0
                },
            )
        }
        val vocabularyTrend = bucketTrend(dateRange, facts) { subRange ->
            val bucketReviews = facts.vocabularyReviews.filter { it.date in subRange }
            VocabularyGrowthPoint(
                label = "${subRange.start.monthValue}/${subRange.start.dayOfMonth}",
                reviews = bucketReviews.size,
                recalled = bucketReviews.count { it.recalled },
                newWords = facts.vocabulary.count { it.createdAt in subRange },
            )
        }
        val sleepTrend = bucketTrend(dateRange, facts) { subRange ->
            val bucketSleep = facts.sleep.filter { it.date in subRange }.mapNotNull { it.durationMinutes }
            SleepTrendPoint(
                label = "${subRange.start.monthValue}/${subRange.start.dayOfMonth}",
                averageMinutes = bucketSleep.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                recordedDays = bucketSleep.size,
                regularityMinutes = if (bucketSleep.size < 2) null else {
                    val mean = bucketSleep.average()
                    sqrt(bucketSleep.sumOf { (it - mean) * (it - mean) } / bucketSleep.size).toLong()
                },
            )
        }
        val financeTrend = bucketTrend(dateRange, facts) { subRange ->
            val bucketTransactions = facts.transactions.filter { it.date in subRange }
            val daysWithRecords = bucketTransactions.map { it.date }.distinct().size
            val net = bucketTransactions.sumOf { it.netSpentFen }
            FinanceGrowthPoint(
                label = "${subRange.start.monthValue}/${subRange.start.dayOfMonth}",
                netSpentFen = net,
                averageDailyFen = if (daysWithRecords == 0) null else net / daysWithRecords,
            )
        }
        val semesterComparison = buildSemesterBuckets(facts, today)
        val notes = mutableListOf<String>()
        if (facts.emptyFor(dateRange)) notes += "所选区间没有任何学习、睡眠或消费记录。"
        if (studyMinutes == 0) notes += "所选区间没有学习计时记录；学习时长不等于学习效果。"
        if (attempts.isEmpty()) notes += "没有习题尝试记录，独立完成率与提示使用率显示数据不足。"
        if (sleepDurations.size < 3) notes += "睡眠记录少于 3 天，不生成规律性结论。"
        if (financeDays == 0) notes += "没有记账记录，不生成消费趋势结论。"
        notes += "成长分析不预测成绩、排名或必然结果；相关性不代表因果关系。"
        return GrowthAnalytics(
            range = range,
            metrics = metrics,
            subjectShares = subjectShares,
            monthlyTrend = monthlyTrend,
            exerciseTrend = exerciseTrend,
            vocabularyTrend = vocabularyTrend,
            sleepTrend = sleepTrend,
            financeTrend = financeTrend,
            goals = goalProgress,
            semesterComparison = semesterComparison,
            notes = notes,
        )
    }

    private fun buildGoalSummary(facts: LearningFactBundle, range: DateRange): GoalGrowthSummary {
        val active = facts.goals.count { it.status == "ACTIVE" }
        val completed = facts.goals.count { it.status == "COMPLETED" }
        val completedMilestones = facts.milestones.filter { it.completed }.sumOf { it.weight.coerceAtLeast(1) }
        val totalMilestones = facts.milestones.sumOf { it.weight.coerceAtLeast(1) }
        val inRange = facts.milestones.count { it.doneDate.inRange(range) }
        return GoalGrowthSummary(
            activeGoals = active,
            completedGoals = completed,
            milestonesCompletedInRange = inRange,
            totalMilestones = facts.milestones.size,
            progressPercent = if (totalMilestones == 0) null else {
                (completedMilestones * 100 / totalMilestones).coerceIn(0, 100)
            },
        )
    }

    private fun <T> bucketTrend(
        range: DateRange,
        facts: LearningFactBundle,
        bucketSizeDays: Int = 1,
        builder: (DateRange) -> T,
    ): List<T> {
        val days = range.days
        val size = if (days <= 14) 1 else if (days <= 60) 7 else 30
        val result = mutableListOf<T>()
        var cursor = range.start
        while (!cursor.isAfter(range.end)) {
            val end = minOf(cursor.plusDays((size - 1).toLong()), range.end)
            result += builder(DateRange(cursor, end))
            cursor = end.plusDays(1)
        }
        return result
    }

    /**
     * 学期划分：9 月–次年 1 月为秋季学期，2 月–7 月为春季学期，8 月不计入学期。
     * 仅用于观察长期趋势，不作为学校教务口径。
     */
    fun buildSemesterBuckets(facts: LearningFactBundle, today: LocalDate): List<SemesterBucket> {
        val dates = buildList {
            addAll(facts.timings.map { it.date })
            facts.tasks.forEach { task ->
                if (task.sessions.isNotEmpty()) addAll(task.sessions.map { it.date }) else task.scheduledDate?.let(::add)
            }
            addAll(facts.attempts.map { it.date })
            addAll(facts.vocabularyReviews.map { it.date })
            addAll(facts.sleep.map { it.date })
        }
        if (dates.isEmpty()) return emptyList()
        val earliest = dates.min()
        val buckets = mutableListOf<SemesterBucket>()
        var year = earliest.year
        while (true) {
            val fallStart = LocalDate.of(year, 9, 1)
            val fallEnd = LocalDate.of(year + 1, 1, 31)
            val springStart = LocalDate.of(year, 2, 1)
            val springEnd = LocalDate.of(year, 7, 31)
            listOf(
                Triple("${year} 春季学期", springStart, springEnd),
                Triple("${year} 秋季学期", fallStart, fallEnd),
            ).forEach { (label, start, end) ->
                if (start.isAfter(today)) return@forEach
                val clippedEnd = minOf(end, today)
                if (!clippedEnd.isBefore(start) && !clippedEnd.isBefore(earliest)) {
                    val range = DateRange(start, clippedEnd)
                    buckets += SemesterBucket(
                        label = label,
                        bucket = WeeklyReportEngine.customBucket(range, label, facts),
                        notes = if (start.isBefore(earliest)) {
                            listOf("该学期只包含 ${earliest} 之后的记录")
                        } else {
                            emptyList()
                        },
                    )
                }
            }
            if (fallEnd.isAfter(today)) break
            year += 1
            if (buckets.size > 20) break
        }
        return buckets.distinctBy { it.label }.sortedBy { it.bucket.start }
    }

    fun daysBetween(start: LocalDate, end: LocalDate): Long = ChronoUnit.DAYS.between(start, end)
}
