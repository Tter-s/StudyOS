package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.MAX_RECURRENCE_OCCURRENCES
import com.zhixu.studyos.core.model.MAX_TASK_SESSIONS
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.schedule.ScheduleValidation

object TaskValidation {
    fun validate(task: StudyTask) {
        val error = errorOrNull(task)
        require(error == null) { error ?: "任务数据无效" }
    }

    fun errorOrNull(task: StudyTask): String? {
        if (task.title.isBlank()) return "请输入任务标题"
        if (task.estimatedMinutes !in 1..24 * 60) return "预计时长应在 1–1440 分钟之间"
        if (task.sessions.size > MAX_TASK_SESSIONS) return "单个任务最多拆分为 $MAX_TASK_SESSIONS 个学习会话"
        if (task.recurrenceInterval !in 1..30) return "重复间隔应在 1–30 之间"
        if (task.recurrence != TaskRecurrence.NONE) {
            if (task.sessions.isEmpty()) return "周期任务需要至少一个学习会话"
            val until = task.recurrenceUntil ?: return "请选择周期结束日期"
            val firstDate = task.sessions.minOf { it.date }
            if (until.isBefore(firstDate)) return "周期结束日期不能早于第一次学习"
            val periodDays = when (task.recurrence) {
                TaskRecurrence.DAILY -> task.recurrenceInterval.toLong()
                TaskRecurrence.WEEKLY -> task.recurrenceInterval.toLong() * 7L
                TaskRecurrence.NONE -> 1L
            }
            val occurrences = (until.toEpochDay() - firstDate.toEpochDay()) / periodDays + 1
            if (occurrences > MAX_RECURRENCE_OCCURRENCES) {
                return "周期任务最多生成 $MAX_RECURRENCE_OCCURRENCES 次，请缩短结束日期或增大间隔"
            }
        }

        task.sessions.forEach { session ->
            val error = ScheduleValidation.timeRangeError(session.startMinute, session.endMinuteExclusive)
            if (error != null) return error
        }
        val sorted = task.sessions.sortedWith(compareBy({ it.date }, { it.startMinute }))
        sorted.zipWithNext().forEach { (first, second) ->
            val firstEnd = first.date.toEpochDay() * 1440L + first.endMinuteExclusive
            val secondStart = second.date.toEpochDay() * 1440L + second.startMinute
            if (secondStart < firstEnd) return "同一任务的学习会话不能互相重叠"
        }

        try {
            TaskStateMachine.validateInvariants(task)
        } catch (error: IllegalArgumentException) {
            return error.message
        }
        if (task.status == TaskStatus.BACKLOG && task.sessions.isNotEmpty()) {
            return "有学习会话的任务应进入已安排状态"
        }
        return null
    }
}
