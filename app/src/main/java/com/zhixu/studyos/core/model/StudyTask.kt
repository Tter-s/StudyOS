package com.zhixu.studyos.core.model

import java.time.Instant
import java.time.LocalDate

data class StudyTask(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val subject: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val estimatedMinutes: Int = 60,
    val dueDate: LocalDate? = null,
    val status: TaskStatus = TaskStatus.BACKLOG,
    val completedAt: Instant? = null,
    val seriesId: String? = null,
    val recurrence: TaskRecurrence = TaskRecurrence.NONE,
    val recurrenceInterval: Int = 1,
    val recurrenceUntil: LocalDate? = null,
    val sortOrder: Int = 0,
    /** 用户主动延期的累计次数；用于排程评分与 AI 分析识别经常延期的任务。 */
    val postponeCount: Int = 0,
    val sessions: List<TaskSession> = emptyList(),
    /** 跨设备稳定标识；V1.0 任务在升级时由数据库回填。 */
    val globalId: String = "",
    /** 观察来源：USER、VOCABULARY、EXAM_REVIEW、AI_SUGGESTION。 */
    val sourceType: String = "USER",
    val sourceId: String? = null,
    /** 同步版本号，每次本地业务修改 +1。 */
    val revision: Int = 1,
) {
    val isCompleted: Boolean get() = status == TaskStatus.COMPLETED
    val isScheduled: Boolean get() = sessions.isNotEmpty()

    val plannedStart: TaskTime?
        get() = sessions.minWithOrNull(compareBy<TaskSession>({ it.date }, { it.startMinute }))
            ?.let { TaskTime(it.date, it.startMinute) }

    val plannedEnd: TaskTime?
        get() = sessions.maxWithOrNull(compareBy<TaskSession>({ it.endDate }, { it.endMinuteOfDay }))
            ?.let { TaskTime(it.endDate, it.endMinuteOfDay) }

    val plannedMinutes: Int
        get() = sessions.sumOf { session ->
            (session.endMinuteExclusive - session.startMinute).coerceAtLeast(0)
        }

    fun sessionsVisibleOn(date: LocalDate): List<TaskSegment> = sessions.mapNotNull { session ->
        when (date) {
            session.date -> {
                val start = session.startMinute.coerceAtMost(MINUTES_PER_DAY)
                val end = session.endMinuteExclusive.coerceAtMost(MINUTES_PER_DAY)
                if (start < end) TaskSegment(session, start, end, isContinuation = false) else null
            }
            session.date.plusDays(1L) -> {
                val end = (session.endMinuteExclusive - MINUTES_PER_DAY).coerceAtMost(MINUTES_PER_DAY)
                if (session.endMinuteExclusive > MINUTES_PER_DAY && end > 0) {
                    TaskSegment(session, 0, end, isContinuation = true)
                } else {
                    null
                }
            }
            else -> null
        }
    }
}
