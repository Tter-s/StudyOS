package com.zhixu.studyos.core.exam

import com.zhixu.studyos.core.sleep.SleepSchedule
import com.zhixu.studyos.core.sleep.SleepProtectionEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Exam(
    val id: Long = 0,
    val name: String,
    val subject: String,
    val date: LocalDate,
    val startMinute: Int? = null,
    val durationMinutes: Int? = null,
    val scope: String = "",
    val targetScore: String = "",
    val materials: String = "",
    val reminderEnabled: Boolean = true,
    val reminderDaysBefore: Int = 3,
    val revision: Int = 1,
) {
    fun validationError(today: LocalDate? = null): String? = when {
        name.isBlank() -> "请填写考试名称"
        name.length > 60 -> "考试名称不能超过 60 个字符"
        subject.length > 30 -> "科目名称不能超过 30 个字符"
        startMinute != null && startMinute !in 0 until 24 * 60 -> "考试时间无效"
        durationMinutes != null && durationMinutes !in 5..600 -> "考试时长应在 5–600 分钟之间"
        reminderDaysBefore !in 0..30 -> "提前提醒天数应在 0–30 天之间"
        today != null && date.isBefore(today.minusDays(1)) -> "考试日期不能早于今天"
        else -> null
    }

    fun requireValid(today: LocalDate? = null) {
        require(validationError(today) == null) { validationError(today) ?: "考试设置无效" }
    }

    fun daysRemaining(today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)
}

enum class ExamPhase { TODAY, TOMORROW, THIS_WEEK, UPCOMING, PASSED }

data class ExamCountdown(
    val exam: Exam,
    val daysRemaining: Long,
    val phase: ExamPhase,
) {
    val label: String
        get() = when {
            daysRemaining < 0 -> "已结束 ${-daysRemaining} 天"
            daysRemaining == 0L -> "今天"
            daysRemaining == 1L -> "明天"
            else -> "还有 $daysRemaining 天"
        }
}

object ExamCountdownCalculator {
    fun build(exams: List<Exam>, today: LocalDate): List<ExamCountdown> = exams
        .map { exam ->
            val days = exam.daysRemaining(today)
            ExamCountdown(
                exam = exam,
                daysRemaining = days,
                phase = when {
                    days < 0 -> ExamPhase.PASSED
                    days == 0L -> ExamPhase.TODAY
                    days == 1L -> ExamPhase.TOMORROW
                    days <= 7L -> ExamPhase.THIS_WEEK
                    else -> ExamPhase.UPCOMING
                },
            )
        }
        .sortedWith(compareBy({ it.phase == ExamPhase.PASSED }, { it.exam.date }, { it.exam.name }))

    fun next(exams: List<Exam>, today: LocalDate): ExamCountdown? =
        build(exams, today).firstOrNull { it.phase != ExamPhase.PASSED }
}

data class ExamReviewPlanItem(
    val date: LocalDate,
    val title: String,
    val subject: String,
    val estimatedMinutes: Int,
    val reason: String,
    val focus: String,
)

data class ExamReviewPlan(
    val exam: Exam,
    val items: List<ExamReviewPlanItem>,
    val warnings: List<String>,
) {
    val totalMinutes: Int get() = items.sumOf(ExamReviewPlanItem::estimatedMinutes)
}

/**
 * 考试复习任务生成器。只生成“待安排”任务，具体时间仍由本地排程引擎决定；
 * 不根据学习时长预测成绩或排名。
 */
object ExamReviewPlanner {
    private const val MAX_ITEMS = 14
    private const val DEFAULT_DAILY_MINUTES = 45

    data class WeakChapter(
        val chapter: String,
        val failureCount: Int,
        val knowledgeNames: List<String> = emptyList(),
    )

    fun build(
        exam: Exam,
        today: LocalDate,
        weakChapters: List<WeakChapter> = emptyList(),
        unfinishedProblemCount: Int = 0,
        materials: String = "",
        availableDaysHint: Int? = null,
    ): ExamReviewPlan {
        val days = exam.daysRemaining(today)
        val warnings = mutableListOf<String>()
        if (days < 0) {
            return ExamReviewPlan(exam, emptyList(), listOf("考试日期已经过去，无法生成复习任务"))
        }
        val span = minOf(
            if (days == 0L) 1 else days.toInt(),
            MAX_ITEMS,
            availableDaysHint?.coerceAtLeast(1) ?: Int.MAX_VALUE,
        )
        val items = mutableListOf<ExamReviewPlanItem>()
        val focusChapters = weakChapters.sortedByDescending { it.failureCount }.take(5)
        for (index in 0 until span) {
            val date = if (days == 0L) today else today.plusDays(index.toLong())
            val dayRatio = if (span == 1) 1.0 else index.toDouble() / (span - 1)
            // 只要还有未完成习题，就至少保留最后一个槽位做收尾；
            // 否则 1–3 天的短期考试永远不会出现“未完成习题”任务。
            val unfinishedSlot = unfinishedProblemCount > 0 && index >= span - 1
            val focus = when {
                !unfinishedSlot && index < (span + 1) / 2 && focusChapters.isNotEmpty() -> {
                    val chapter = focusChapters[index % focusChapters.size]
                    "薄弱章节" to chapter
                }
                unfinishedSlot -> "未完成习题" to null
                else -> "综合回顾" to null
            }
            val title = when {
                focus.first == "薄弱章节" -> "考试复习：${focus.second?.chapter}（${exam.name}）"
                focus.first == "未完成习题" -> "考试习题收尾（${exam.name}）"
                else -> "考前综合回顾（${exam.name}）"
            }
            val reason = when {
                focus.first == "薄弱章节" -> {
                    val chapter = focus.second
                    if (chapter != null) {
                        "「${chapter.chapter}」在近期练习中出现 ${chapter.failureCount} 次未通过记录"
                    } else {
                        "针对薄弱章节进行复习"
                    }
                }
                focus.first == "未完成习题" -> "考试范围内仍有 $unfinishedProblemCount 道题未完成"
                else -> if (materials.isNotBlank()) "结合复习资料：${materials.take(40)}" else "整体回顾考试范围"
            }
            items += ExamReviewPlanItem(
                date = date,
                title = title,
                subject = exam.subject.ifBlank { "未指定科目" },
                estimatedMinutes = DEFAULT_DAILY_MINUTES,
                reason = reason,
                focus = focus.first,
            )
        }
        if (days > 0 && span < minOf(days.toInt(), MAX_ITEMS)) {
            warnings += "可用天数较多，只生成最近 $span 天的高优先级复习任务；后续可再次生成"
        }
        if (exam.scope.isBlank()) warnings += "尚未填写考试范围，复习任务只能按薄弱章节/未完成习题安排"
        if (focusChapters.isEmpty() && unfinishedProblemCount == 0) {
            warnings += "没有薄弱章节或未完成习题记录，生成的是通用回顾任务"
        }
        return ExamReviewPlan(exam = exam, items = items, warnings = warnings)
    }

    /**
     * 考试时段与睡眠保护冲突时提示，不自动修改考试或睡眠设置。
     */
    fun sleepConflicts(
        exam: Exam,
        schedule: SleepSchedule,
        today: LocalDate,
    ): List<String> {
        val conflicts = mutableListOf<String>()
        val start = exam.startMinute ?: return conflicts
        val duration = exam.durationMinutes ?: return conflicts
        val end = start + duration
        val dayIntervals = SleepProtectionEngine.intervalsOn(exam.date, schedule)
        dayIntervals.forEach { interval ->
            val overlapStart = maxOf(interval.startMinute, start)
            val overlapEnd = minOf(interval.endMinuteExclusive, end)
            if (overlapEnd > overlapStart) {
                conflicts += "考试「${exam.name}」与睡眠/午休时段重叠，" +
                    "请注意这属于固定安排冲突，需要你自行调整"
            }
        }
        if (exam.date.isBefore(today)) conflicts.clear()
        return conflicts
    }
}

data class LongTermGoal(
    val id: Long = 0,
    val title: String,
    val category: String = "自定义",
    val description: String = "",
    val startDate: LocalDate,
    val targetDate: LocalDate? = null,
    val status: String = "ACTIVE",
    val progressSource: String = "MILESTONE",
    val revision: Int = 1,
) {
    val isActive: Boolean get() = status == "ACTIVE"

    fun validationError(): String? = when {
        title.isBlank() -> "请填写目标名称"
        title.length > 80 -> "目标名称不能超过 80 个字符"
        targetDate != null && targetDate.isBefore(startDate) -> "目标日期不能早于开始日期"
        status !in setOf("ACTIVE", "PAUSED", "COMPLETED") -> "目标状态无效"
        else -> null
    }
}

data class GoalMilestone(
    val id: Long = 0,
    val goalId: Long,
    val title: String,
    val dueDate: LocalDate? = null,
    val weight: Int = 1,
    val status: String = "PENDING",
    val completedAt: java.time.Instant? = null,
    val sortOrder: Int = 0,
) {
    val isCompleted: Boolean get() = status == "COMPLETED"
}

data class GoalProgress(
    val goal: LongTermGoal,
    val milestones: List<GoalMilestone>,
    val completedWeight: Int,
    val totalWeight: Int,
    val percent: Int?,
    val overdueMilestones: Int,
    val dataSufficient: Boolean,
)

object GoalProgressCalculator {
    fun calculate(
        goal: LongTermGoal,
        milestones: List<GoalMilestone>,
        today: LocalDate,
    ): GoalProgress {
        val total = milestones.sumOf { it.weight.coerceAtLeast(1) }
        val completed = milestones.filter(GoalMilestone::isCompleted).sumOf { it.weight.coerceAtLeast(1) }
        val overdue = milestones.count { !it.isCompleted && it.dueDate?.isBefore(today) == true }
        return GoalProgress(
            goal = goal,
            milestones = milestones.sortedWith(compareBy({ it.sortOrder }, { it.id })),
            completedWeight = completed,
            totalWeight = total,
            percent = if (total == 0) null else (completed * 100 / total).coerceIn(0, 100),
            overdueMilestones = overdue,
            dataSufficient = milestones.isNotEmpty(),
        )
    }
}
