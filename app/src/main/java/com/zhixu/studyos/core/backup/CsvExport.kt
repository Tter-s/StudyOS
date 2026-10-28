package com.zhixu.studyos.core.backup

import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.Problem
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.Subject
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.model.TaskRecurrence
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.model.TimingSession

/**
 * 面向人的 CSV 导出（任务 / 计时记录 / 习题记录）。
 *
 * 与 [com.zhixu.studyos.core.finance.FinanceCsv] 保持同样的约定：
 * UTF-8 BOM、RFC 4180 引号转义、CRLF 行尾、中文表头。
 * 这是数据导出的快照，不是恢复格式；完整恢复请使用备份 ZIP。
 */
object CsvExport {

    const val UTF8_BOM = "\uFEFF"
    private const val CRLF = "\r\n"

    fun tasks(tasks: List<StudyTask>): String {
        val header = listOf(
            "任务ID", "标题", "科目", "优先级", "预计分钟", "截止日期", "状态",
            "完成时间", "延期次数", "重复", "重复间隔", "学习时段",
        )
        val rows = tasks.sortedWith(compareBy({ it.dueDate ?: java.time.LocalDate.MAX }, { it.id }))
            .map { task ->
                listOf(
                    task.id.toString(),
                    task.title,
                    task.subject,
                    priorityLabel(task.priority),
                    task.estimatedMinutes.toString(),
                    task.dueDate?.toString().orEmpty(),
                    taskStatusLabel(task.status),
                    task.completedAt?.toString().orEmpty(),
                    task.postponeCount.toString(),
                    recurrenceLabel(task.recurrence),
                    task.recurrenceInterval.toString(),
                    task.sessions.joinToString("; ") { session ->
                        "${session.date} ${clock(session.startMinute)}-${clock(session.endMinuteExclusive)}" +
                            if (session.isLocked) "(锁定)" else ""
                    },
                )
            }
        return csv(header, rows)
    }

    fun timings(timings: List<TimingSession>, subjects: List<Subject>): String {
        val names = subjects.associate { it.id to it.name }
        val header = listOf(
            "记录ID", "日期", "科目", "方式", "状态", "时长(分钟)", "计划分钟",
            "中断次数", "任务ID", "开始时间", "结束时间", "备注",
        )
        val rows = timings.sortedWith(compareBy({ it.studyDate }, { it.startedAt })).map { timing ->
            listOf(
                timing.id.toString(),
                timing.studyDate.toString(),
                names[timing.subjectId] ?: "已删除科目",
                timerModeLabel(timing.mode.name),
                timerStatusLabel(timing.status.name),
                (timing.accumulatedMillis / 60_000L).toString(),
                timing.plannedDurationMinutes?.toString().orEmpty(),
                timing.interruptionCount.toString(),
                timing.taskId?.toString().orEmpty(),
                timing.startedAt.toString(),
                timing.endedAt?.toString().orEmpty(),
                timing.note,
            )
        }
        return csv(header, rows)
    }

    fun manualProblems(problems: List<Problem>, subjects: List<Subject>): String {
        val names = subjects.associate { it.id to it.name }
        val header = listOf(
            "记录ID", "科目", "教材", "章节", "题号", "知识点", "状态",
            "独立思考分钟", "使用AI提示", "卡点原因", "是否错题", "是否困难",
            "独立完成", "重练次数", "二次独立完成", "完成时间", "描述",
        )
        val rows = problems.sortedWith(compareBy({ it.createdAt }, { it.id })).map { problem ->
            listOf(
                problem.id.toString(),
                names[problem.subjectId] ?: "已删除科目",
                problem.textbook,
                problem.chapter,
                problem.problemNumber,
                problem.knowledgePoint,
                problemStatusLabel(problem.status),
                problem.independentThinkingMinutes.toString(),
                yesNo(problem.usedAiHint),
                problem.blockerReason?.let(::blockerLabel).orEmpty(),
                yesNo(problem.isMistake),
                yesNo(problem.isDifficult),
                problem.independentCompleted?.let(::yesNo).orEmpty(),
                problem.retryCount.toString(),
                problem.secondAttemptIndependent?.let(::yesNo).orEmpty(),
                problem.completedAt?.toString().orEmpty(),
                problem.description,
            )
        }
        return csv(header, rows)
    }

    private fun csv(header: List<String>, rows: List<List<String>>): String = buildString {
        append(UTF8_BOM)
        append(header.joinToString(",") { quote(it) })
        append(CRLF)
        rows.forEach { row ->
            append(row.joinToString(",") { quote(it) })
            append(CRLF)
        }
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun clock(minuteOfDay: Int): String {
        val normalized = minuteOfDay.coerceIn(0, 24 * 60)
        return "%02d:%02d".format(normalized / 60, normalized % 60)
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private fun priorityLabel(priority: TaskPriority): String = when (priority) {
        TaskPriority.LOW -> "低"
        TaskPriority.MEDIUM -> "中"
        TaskPriority.HIGH -> "高"
    }

    private fun taskStatusLabel(status: TaskStatus): String = when (status) {
        TaskStatus.BACKLOG -> "待安排"
        TaskStatus.SCHEDULED -> "已安排"
        TaskStatus.IN_PROGRESS -> "进行中"
        TaskStatus.COMPLETED -> "已完成"
    }

    private fun recurrenceLabel(recurrence: TaskRecurrence): String = when (recurrence) {
        TaskRecurrence.NONE -> "无"
        TaskRecurrence.DAILY -> "每天"
        TaskRecurrence.WEEKLY -> "每周"
    }

    private fun problemStatusLabel(status: ProblemStatus): String = when (status) {
        ProblemStatus.TODO -> "待做"
        ProblemStatus.IN_PROGRESS -> "进行中"
        ProblemStatus.COMPLETED -> "已完成"
    }

    private fun blockerLabel(reason: BlockerReason): String = when (reason) {
        BlockerReason.CONCEPT -> "概念不清"
        BlockerReason.THEOREM -> "定理不熟"
        BlockerReason.PROOF_CONSTRUCTION -> "证明构造困难"
        BlockerReason.CALCULATION -> "计算出错"
        BlockerReason.REPRODUCTION -> "复现困难"
        BlockerReason.OTHER -> "其他"
    }

    private fun timerModeLabel(mode: String): String = when (mode) {
        "STOPWATCH" -> "正计时"
        "POMODORO" -> "番茄钟"
        "MANUAL" -> "手动补录"
        else -> mode
    }

    private fun timerStatusLabel(status: String): String = when (status) {
        "RUNNING" -> "进行中"
        "PAUSED" -> "已暂停"
        "COMPLETED" -> "已完成"
        else -> status
    }
}
