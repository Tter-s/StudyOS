package com.zhixu.studyos.core.model

import java.time.LocalDate

/**
 * V3.1 的每日待办：只有日期、标题和完成状态。
 *
 * 不包含科目、优先级、预计时长、会话或周期规则；未完成任务不会自动滚入第二天。
 */
data class DailyTask(
    val id: Long = 0,
    val date: LocalDate,
    val title: String,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val sortOrder: Int = 0,
)

/** 每日总结只记录事实，不做评分或分析。 */
data class DailySummary(
    val date: LocalDate,
    val content: String = "",
)

/** 手动填写的娱乐时间（玩手机、游戏等），单位为分钟。 */
data class EntertainmentRecord(
    val date: LocalDate,
    val minutes: Int,
)

/** 历史列表中的一天：只包含当日已存在的事实。 */
data class DayRecord(
    val date: LocalDate,
    val totalTasks: Int,
    val completedTasks: Int,
    val summary: String?,
    val entertainmentMinutes: Int?,
    val sleepMinutes: Long? = null,
) {
    val hasAnyRecord: Boolean
        get() = totalTasks > 0 || !summary.isNullOrBlank() || entertainmentMinutes != null || sleepMinutes != null
}

const val MAX_DAILY_TASK_TITLE_LENGTH = 200
const val MAX_DAILY_SUMMARY_LENGTH = 2_000
const val MAX_ENTERTAINMENT_MINUTES = 24 * 60
