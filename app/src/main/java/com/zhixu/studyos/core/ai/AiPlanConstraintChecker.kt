package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AiIssueSeverity
import com.zhixu.studyos.core.model.AiPlan
import com.zhixu.studyos.core.model.AiPlanIssue
import com.zhixu.studyos.core.model.AiPlanReview
import com.zhixu.studyos.core.model.AiSubjectAllocation
import com.zhixu.studyos.core.model.AiTaskEstimate
import com.zhixu.studyos.core.model.AiTaskStat
import com.zhixu.studyos.core.model.TaskStatus

/**
 * 本地约束检查（V0.9 规划流程的第二步）。
 *
 * AI 输出永远不是直接可执行数据：
 * - 只接受来自本机快照的 taskId 与科目名称；
 * - 已完成/不存在的任务拒绝；
 * - 用时在 5–300 分钟内；
 * - 每日科目分配总和不得超过本地每日学习上限；
 * - 未发送科目名称时（掩码隐私模式）所有科目分配都拒绝，因为无法映射回本地科目。
 *
 * 被拒绝的项保留在 reviewed plan 中并给出原因，用户可以看到 AI 与本地约束的差异。
 */
object AiPlanConstraintChecker {

    const val MIN_ESTIMATE_MINUTES = 5
    const val MAX_ESTIMATE_MINUTES = 300

    fun check(
        plan: AiPlan,
        upcomingTasks: List<AiTaskStat>,
        knownSubjects: Set<String>,
        dailyLimitMinutes: Int,
        subjectNamesAvailable: Boolean,
    ): AiPlanReview {
        val issues = mutableListOf<AiPlanIssue>()
        val tasksById = upcomingTasks.associateBy { it.taskId }
        val acceptedEstimates = mutableListOf<AiTaskEstimate>()
        val rejectedEstimates = mutableListOf<AiTaskEstimate>()
        val seenTaskIds = mutableSetOf<Long>()

        plan.taskEstimates.forEach { estimate ->
            val label = "任务 #${estimate.taskId}"
            when {
                estimate.taskId in seenTaskIds -> {
                    rejectedEstimates += estimate
                    issues += error(label, "同一任务在本次建议中出现多次，仅采用第一条")
                }
                tasksById[estimate.taskId] == null -> {
                    rejectedEstimates += estimate
                    issues += error(label, "该任务不在本次发送的任务列表中，无法本地核对")
                }
                estimate.estimatedMinutes !in MIN_ESTIMATE_MINUTES..MAX_ESTIMATE_MINUTES -> {
                    rejectedEstimates += estimate
                    issues += error(label, "预计用时应在 $MIN_ESTIMATE_MINUTES–$MAX_ESTIMATE_MINUTES 分钟")
                }
                else -> {
                    seenTaskIds += estimate.taskId
                    acceptedEstimates += estimate
                }
            }
        }

        val acceptedAllocations = mutableListOf<AiSubjectAllocation>()
        val rejectedAllocations = mutableListOf<AiSubjectAllocation>()
        var allocatedMinutes = 0
        plan.subjectAllocations.forEach { allocation ->
            val label = "科目「${allocation.subject}」"
            when {
                !subjectNamesAvailable -> {
                    rejectedAllocations += allocation
                    issues += error(label, "当前隐私设置未发送科目名称，无法把分配映射到本地科目")
                }
                allocation.subject !in knownSubjects -> {
                    rejectedAllocations += allocation
                    issues += error(label, "本地没有该科目，AI 可能使用了输入中没有的名称")
                }
                allocation.minutesPerDay !in 0..dailyLimitMinutes -> {
                    rejectedAllocations += allocation
                    issues += error(label, "每日分钟数应在 0–$dailyLimitMinutes 之间")
                }
                allocatedMinutes + allocation.minutesPerDay > dailyLimitMinutes -> {
                    rejectedAllocations += allocation
                    issues += error(
                        label,
                        "所有科目分配合计超过每日学习上限 $dailyLimitMinutes 分钟",
                    )
                }
                else -> {
                    allocatedMinutes += allocation.minutesPerDay
                    acceptedAllocations += allocation
                }
            }
        }

        if (plan.taskEstimates.isEmpty() && plan.subjectAllocations.isEmpty()) {
            issues += AiPlanIssue(
                severity = AiIssueSeverity.WARNING,
                itemLabel = "计划",
                message = "AI 没有给出可执行的用时估计或科目分配，仅包含文字建议",
            )
        }

        return AiPlanReview(
            plan = plan,
            acceptedTaskEstimates = acceptedEstimates,
            rejectedTaskEstimates = rejectedEstimates,
            acceptedAllocations = acceptedAllocations,
            rejectedAllocations = rejectedAllocations,
            issues = issues,
        )
    }

    /** 仅用于「确认后应用」时的二次防护：以最新任务状态复核。 */
    fun stillApplicable(
        estimate: AiTaskEstimate,
        currentStatus: TaskStatus,
        currentTaskId: Long,
    ): String? = when {
        currentTaskId != estimate.taskId -> "任务已变化"
        currentStatus == TaskStatus.COMPLETED -> "任务已完成，不再修改用时"
        estimate.estimatedMinutes !in MIN_ESTIMATE_MINUTES..MAX_ESTIMATE_MINUTES -> "预计用时超出允许范围"
        else -> null
    }

    private fun error(label: String, message: String) = AiPlanIssue(
        severity = AiIssueSeverity.ERROR,
        itemLabel = label,
        message = message,
    )
}
