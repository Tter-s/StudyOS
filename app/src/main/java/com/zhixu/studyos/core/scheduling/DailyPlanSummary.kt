package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.StudyTask
import java.time.LocalDate

data class DailyPlanSummary(
    val date: LocalDate,
    val openTaskCount: Int,
    val completedTaskCount: Int,
    val openMinutes: Int,
    val firstStartMinute: Int?,
    val firstTaskTitle: String?,
) {
    val hasOpenTasks: Boolean get() = openTaskCount > 0
}

object DailyPlanSummaryBuilder {
    fun build(tasks: List<StudyTask>, date: LocalDate): DailyPlanSummary {
        var openCount = 0
        var completedCount = 0
        var openMinutes = 0
        var firstStart: Int? = null
        var firstTitle: String? = null

        tasks.forEach { task ->
            val segments = task.sessionsVisibleOn(date)
            if (segments.isEmpty()) return@forEach
            if (task.isCompleted) {
                completedCount += 1
            } else {
                openCount += 1
                openMinutes += segments.sumOf { it.endMinuteExclusive - it.startMinute }
                val start = segments.minOf { it.startMinute }
                if (firstStart == null || start < firstStart!!) {
                    firstStart = start
                    firstTitle = task.title
                }
            }
        }
        return DailyPlanSummary(
            date = date,
            openTaskCount = openCount,
            completedTaskCount = completedCount,
            openMinutes = openMinutes,
            firstStartMinute = firstStart,
            firstTaskTitle = firstTitle,
        )
    }

    fun notificationBody(summary: DailyPlanSummary): String {
        if (summary.openTaskCount == 0 && summary.completedTaskCount == 0) {
            return "今天还没有安排学习任务。打开学习页可以一键生成计划。"
        }
        if (summary.openTaskCount == 0) {
            return "今天的 ${summary.completedTaskCount} 项学习任务已完成。"
        }
        return buildString {
            append("还有 ${summary.openTaskCount} 项学习任务 · 预计 ${formatMinutes(summary.openMinutes)}")
            summary.firstStartMinute?.let { minute ->
                append("；")
                append(formatMinute(minute))
                append(" 起")
                summary.firstTaskTitle?.let { title ->
                    append("「")
                    append(title)
                    append("」")
                }
            }
            if (summary.completedTaskCount > 0) {
                append("。已完成 ${summary.completedTaskCount} 项")
            }
            append("。")
        }
    }

    private fun formatMinutes(minutes: Int): String {
        if (minutes <= 0) return "0 分钟"
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours == 0 -> "$remainder 分钟"
            remainder == 0 -> "$hours 小时"
            else -> "$hours 小时 $remainder 分钟"
        }
    }

    private fun formatMinute(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)
}
