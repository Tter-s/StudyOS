package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import java.time.LocalDate

data class SchedulingPreview(
    val result: SchedulingResult,
    val blocks: List<ProposedSession>,
    val unscheduled: List<UnscheduledTask>,
    val warnings: List<SchedulingWarning>,
) {
    val totalProposedMinutes: Int get() = blocks.sumOf(ProposedSession::durationMinutes)
    val conflicts: List<SchedulingConflict> get() = result.conflicts
    val proposedBlocksByDate: Map<LocalDate, List<ProposedSession>>
        get() = blocks.groupBy(ProposedSession::date)
}

/**
 * 预览编辑只修改尚未保存的方案，不写数据库。
 * 任何手工调整都会把对应时段标记为锁定，保存后不会被后续自动排程覆盖。
 */
object SchedulingPreviewEditor {
    fun fromResult(result: SchedulingResult): SchedulingPreview {
        val reasons = result.unscheduled.associate { it.taskId to it.reason }
        return rebuild(result, result.blocks, reasons)
    }

    fun toggleLock(preview: SchedulingPreview, blockId: Long): SchedulingPreview {
        val blocks = preview.blocks.map { block ->
            if (block.id == blockId) block.copy(isLocked = !block.isLocked) else block
        }
        return rebuild(
            result = preview.result,
            blocks = blocks,
            unscheduledReasons = preview.unscheduled.associate { it.taskId to it.reason },
        )
    }

    fun removeBlock(preview: SchedulingPreview, blockId: Long): SchedulingPreview {
        val blocks = preview.blocks.filterNot { it.id == blockId }
        return rebuild(preview.result, blocks, emptyMap())
    }

    fun adjustBlock(
        preview: SchedulingPreview,
        blockId: Long,
        date: LocalDate,
        startMinute: Int,
        endMinuteExclusive: Int,
        today: LocalDate,
        nowMinuteOfDay: Int,
    ): AdjustmentOutcome {
        if (preview.blocks.none { it.id == blockId }) {
            return AdjustmentOutcome(preview = null, error = "要调整的时段不存在")
        }
        val error = validate(
            preview = preview,
            blockId = blockId,
            date = date,
            startMinute = startMinute,
            endMinuteExclusive = endMinuteExclusive,
            today = today,
            nowMinuteOfDay = nowMinuteOfDay,
        )
        if (error != null) return AdjustmentOutcome(preview = null, error = error)
        val blocks = preview.blocks.map { block ->
            if (block.id == blockId) {
                block.copy(
                    date = date,
                    startMinute = startMinute,
                    endMinuteExclusive = endMinuteExclusive,
                    isLocked = true,
                )
            } else {
                block
            }
        }.sortedWith(blockComparator)
        return AdjustmentOutcome(
            preview = rebuild(preview.result, blocks, emptyMap()),
            error = null,
        )
    }

    fun validate(
        preview: SchedulingPreview,
        blockId: Long,
        date: LocalDate,
        startMinute: Int,
        endMinuteExclusive: Int,
        today: LocalDate,
        nowMinuteOfDay: Int,
    ): String? {
        if (date.isBefore(preview.result.horizonStart) || date.isAfter(preview.result.horizonEnd)) {
            return "日期必须在本次排程范围内"
        }
        if (startMinute !in 0 until MINUTES_PER_DAY) return "开始时间必须在 00:00–23:59 之间"
        if (endMinuteExclusive !in 1..MINUTES_PER_DAY) return "结束时间必须在 00:01–24:00 之间"
        if (endMinuteExclusive <= startMinute) return "结束时间必须晚于开始时间"
        if (date == today && startMinute < nowMinuteOfDay) return "不能安排到今天已经过去的时段"
        val fixed = preview.result.days.firstOrNull { it.date == date }?.fixedBlocks.orEmpty()
        fixed.firstOrNull { block ->
            startMinute < block.endMinuteExclusive && block.startMinute < endMinuteExclusive
        }?.let { block ->
            return "与固定安排「${block.title}」时间冲突"
        }
        preview.blocks.firstOrNull { block ->
            block.id != blockId &&
                block.date == date &&
                startMinute < block.endMinuteExclusive &&
                block.startMinute < endMinuteExclusive
        }?.let { block ->
            return "与「${block.taskTitle}」的学习时段重叠"
        }
        return null
    }

    private fun rebuild(
        result: SchedulingResult,
        blocks: List<ProposedSession>,
        unscheduledReasons: Map<Long, String>,
    ): SchedulingPreview {
        val sortedBlocks = blocks.sortedWith(blockComparator)
        val scheduledByTask = sortedBlocks.groupBy(ProposedSession::taskId)
            .mapValues { (_, sessions) -> sessions.sumOf(ProposedSession::durationMinutes) }
        val unscheduled = result.taskPlans.mapNotNull { plan ->
            val scheduled = scheduledByTask[plan.taskId] ?: 0
            val remaining = (plan.remainingMinutes - scheduled).coerceAtLeast(0)
            if (remaining <= 0) {
                null
            } else {
                UnscheduledTask(
                    taskId = plan.taskId,
                    title = plan.title,
                    remainingMinutes = remaining,
                    reason = unscheduledReasons[plan.taskId]
                        ?: "手动调整后仍有 $remaining 分钟未安排",
                )
            }
        }
        val warnings = rebuildWarnings(result, sortedBlocks, unscheduled)
        return SchedulingPreview(
            result = result,
            blocks = sortedBlocks,
            unscheduled = unscheduled,
            warnings = warnings,
        )
    }

    private fun rebuildWarnings(
        result: SchedulingResult,
        blocks: List<ProposedSession>,
        unscheduled: List<UnscheduledTask>,
    ): List<SchedulingWarning> {
        if (unscheduled.isEmpty()) {
            return result.warnings.filter {
                it.type == SchedulingWarningType.FIXED_OVER_LIMIT ||
                    it.type == SchedulingWarningType.LOCKED_AFTER_DUE ||
                    it.type == SchedulingWarningType.CONFLICT
            }
        }
        val proposedByDate = blocks.groupBy(ProposedSession::date)
            .mapValues { (_, sessions) -> sessions.sumOf(ProposedSession::durationMinutes) }
        val warnings = result.warnings.filter {
            it.type == SchedulingWarningType.FIXED_OVER_LIMIT ||
                it.type == SchedulingWarningType.LOCKED_AFTER_DUE ||
                it.type == SchedulingWarningType.CONFLICT
        }.toMutableList()
        if (result.days.all { it.availableMinutes == 0 }) {
            warnings += SchedulingWarning(
                type = SchedulingWarningType.NO_FREE_TIME,
                message = "所选范围内没有可用于新学习任务的空闲时间（已扣除固定安排与缓冲）",
            )
        } else {
            result.days.forEach { day ->
                val proposed = proposedByDate[day.date] ?: 0
                val fixedStudy = day.fixedStudyMinutes
                val limit = day.dailyLimitMinutes
                if (fixedStudy <= limit &&
                    day.availableMinutes > 0 &&
                    fixedStudy + proposed >= limit
                ) {
                    warnings += SchedulingWarning(
                        type = SchedulingWarningType.DAILY_LIMIT,
                        message = "该日学习安排已达到每日上限 $limit 分钟",
                        date = day.date,
                    )
                }
            }
        }
        warnings += SchedulingWarning(
            type = SchedulingWarningType.PARTIAL,
            message = "有 ${unscheduled.size} 项任务未完全安排，共 " +
                "${unscheduled.sumOf(UnscheduledTask::remainingMinutes)} 分钟",
        )
        return warnings.sortedWith(
            compareBy<SchedulingWarning>({ it.date == null }, { it.date }, { it.message }),
        )
    }

    private val blockComparator: Comparator<ProposedSession> =
        compareBy({ it.date }, { it.startMinute }, { it.endMinuteExclusive }, { it.taskId })
}

data class AdjustmentOutcome(
    val preview: SchedulingPreview?,
    val error: String?,
)
