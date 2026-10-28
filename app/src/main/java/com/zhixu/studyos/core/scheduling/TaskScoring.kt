package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.TaskPriority
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 排程评分只使用可解释的固定规则，不含随机因素：
 * - 截止日期越近分数越高，逾期任务最高；
 * - 高优先级加分；
 * - 科目偏好“提高/降低”加减分；
 * - 上次计划中已过去但未完成的时段给予顺延加分。
 */
object TaskScoring {
    fun deadlineScore(today: LocalDate, dueDate: LocalDate?): Pair<Int, String> {
        if (dueDate == null) return 0 to "无截止日期"
        val days = ChronoUnit.DAYS.between(today, dueDate)
        return when {
            days < 0L -> (100 + minOf(30L, -days).toInt()) to "已逾期 ${-days} 天"
            days == 0L -> 90 to "今天截止"
            days == 1L -> 70 to "明天截止"
            days == 2L -> 55 to "2 天后截止"
            days == 3L -> 40 to "3 天后截止"
            days in 4L..7L -> (30 - (days - 4L) * 3).toInt() to "$days 天后截止"
            days in 8L..14L -> 10 to "$days 天后截止"
            else -> 0 to "截止日期较远"
        }
    }

    fun priorityScore(priority: TaskPriority): Pair<Int, String> = when (priority) {
        TaskPriority.HIGH -> 30 to "高优先级"
        TaskPriority.MEDIUM -> 15 to "中优先级"
        TaskPriority.LOW -> 0 to "低优先级"
    }

    fun subjectScore(
        subject: String,
        preferences: Map<String, SubjectPreference>,
    ): Pair<Int, String?> {
        val key = subject.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return 0 to null
        return when (preferences[key]) {
            SubjectPreference.HIGHER -> 20 to "科目偏好：提高"
            SubjectPreference.LOWER -> -15 to "科目偏好：降低"
            SubjectPreference.NORMAL, null -> 0 to null
        }
    }

    fun isCarryOver(task: StudyTask, today: LocalDate, nowMinuteOfDay: Int): Boolean {
        val nowLocal = today.toEpochDay() * 1440L + nowMinuteOfDay
        return task.sessions.any { session ->
            session.endDate.toEpochDay() * 1440L + session.endMinuteOfDay <= nowLocal
        }
    }

    fun score(
        task: StudyTask,
        today: LocalDate,
        nowMinuteOfDay: Int,
        preferences: Map<String, SubjectPreference>,
    ): TaskScoreBreakdown {
        val (deadline, deadlineReason) = deadlineScore(today, task.dueDate)
        val (priority, priorityReason) = priorityScore(task.priority)
        val (subject, subjectReason) = subjectScore(task.subject, preferences)
        val carryOver = isCarryOver(task, today, nowMinuteOfDay)
        val carryScore = if (carryOver) 25 else 0
        val reasons = buildList {
            add(deadlineReason)
            add(priorityReason)
            subjectReason?.let(::add)
            if (carryOver) add("往日未完成，优先顺延")
        }
        return TaskScoreBreakdown(
            deadlineScore = deadline,
            priorityScore = priority,
            subjectScore = subject,
            carryOverScore = carryScore,
            total = deadline + priority + subject + carryScore,
            reasons = reasons,
        )
    }

    val planComparator: Comparator<SchedulingTaskPlan> =
        compareByDescending<SchedulingTaskPlan> { it.score.total }
            .thenBy { it.dueDate == null }
            .thenBy { it.dueDate }
            .thenByDescending { it.priority.ordinal }
            .thenBy { it.taskId }
}
