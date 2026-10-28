package com.zhixu.studyos.core.task

import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.TaskStatus
import java.time.Instant

object TaskStateMachine {
    private val allowedTransitions: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.BACKLOG to setOf(TaskStatus.SCHEDULED, TaskStatus.COMPLETED),
        TaskStatus.SCHEDULED to setOf(TaskStatus.BACKLOG, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED),
        TaskStatus.IN_PROGRESS to setOf(TaskStatus.SCHEDULED, TaskStatus.COMPLETED),
        TaskStatus.COMPLETED to setOf(TaskStatus.BACKLOG, TaskStatus.SCHEDULED),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean =
        from == to || allowedTransitions[from]?.contains(to) == true

    fun complete(task: StudyTask, completedAt: Instant): StudyTask {
        require(task.status != TaskStatus.COMPLETED) { "任务已经完成" }
        require(canTransition(task.status, TaskStatus.COMPLETED)) { "当前状态不能直接完成" }
        return task.copy(status = TaskStatus.COMPLETED, completedAt = completedAt)
    }

    fun undoCompletion(task: StudyTask): StudyTask {
        require(task.status == TaskStatus.COMPLETED) { "任务尚未完成" }
        val next = if (task.sessions.isEmpty()) TaskStatus.BACKLOG else TaskStatus.SCHEDULED
        return task.copy(status = next, completedAt = null)
    }

    fun start(task: StudyTask): StudyTask {
        require(task.status == TaskStatus.SCHEDULED) { "只有已安排任务可以开始" }
        require(task.sessions.isNotEmpty()) { "未安排时间的任务不能开始" }
        return task.copy(status = TaskStatus.IN_PROGRESS)
    }

    fun pause(task: StudyTask): StudyTask {
        require(task.status == TaskStatus.IN_PROGRESS) { "任务不在进行中" }
        return task.copy(status = TaskStatus.SCHEDULED)
    }

    /** Re-derives a non-completed status after sessions or task times change. */
    fun afterReplan(task: StudyTask): StudyTask {
        if (task.status == TaskStatus.COMPLETED) return task
        val next = when {
            task.sessions.isEmpty() -> TaskStatus.BACKLOG
            task.status == TaskStatus.IN_PROGRESS -> TaskStatus.IN_PROGRESS
            else -> TaskStatus.SCHEDULED
        }
        return task.copy(status = next, completedAt = null)
    }

    fun validateInvariants(task: StudyTask) {
        when (task.status) {
            TaskStatus.COMPLETED -> requireNotNull(task.completedAt) { "已完成任务必须有实际完成时间" }
            TaskStatus.SCHEDULED, TaskStatus.IN_PROGRESS -> {
                require(task.completedAt == null) { "未完成任务不能有实际完成时间" }
                require(task.sessions.isNotEmpty()) { "已安排任务必须有学习会话" }
            }
            TaskStatus.BACKLOG -> {
                require(task.completedAt == null) { "待安排任务不能有实际完成时间" }
                require(task.sessions.isEmpty()) { "有学习会话的任务应进入已安排状态" }
            }
        }
    }
}
