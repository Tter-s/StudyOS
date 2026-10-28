package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.MAX_TASK_SESSIONS
import com.zhixu.studyos.core.model.MINUTES_PER_DAY
import com.zhixu.studyos.core.model.ScheduleItemType
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.REST_COLOR_ARGB
import com.zhixu.studyos.core.model.TASK_COLOR_ARGB
import com.zhixu.studyos.core.model.TASK_COMPLETED_COLOR_ARGB
import com.zhixu.studyos.core.model.TaskStatus
import com.zhixu.studyos.core.schedule.ScheduleEngine
import java.time.LocalDate
import java.util.Locale

/**
 * 确定性、可解释的本地启发式排程器。
 *
 * 硬约束：
 * - 课程、临时事务、睡眠/用餐/固定事务、锁定或进行中的任务时段不可占用；
 * - 自动时段互不重叠、不跨越午夜、不安排到今天已过去的时段；
 * - 任务不安排到截止日期之后（逾期任务从今天起尽快安排）；
 * - 服从每日学习时长上限和单任务会话数上限。
 *
 * 软约束：
 * - 固定评分（截止日期、优先级、科目偏好、顺延未完成）决定任务顺序；
 * - 每个学习块最多 50 分钟，块之间至少保留用户缓冲；
 * - 优先使用较早日期和较早时段，大任务按需拆分。
 *
 * 不引入线性规划、遗传算法或外部优化框架；结果只取决于输入和固定规则。
 */
object AutoScheduler {
    private const val MAX_CONFLICT_RESULTS = 100

    fun schedule(input: SchedulingInput): SchedulingResult {
        require(input.horizonDays in 1..7) { "排程范围应为 1–7 天" }
        require(input.nowMinuteOfDay in 0..MINUTES_PER_DAY) { "当前分钟无效" }
        input.settings.requireValid()
        val today = input.today
        val nowMinute = ceilToStep(input.nowMinuteOfDay.coerceIn(0, MINUTES_PER_DAY))
        val horizonEnd = today.plusDays(input.horizonDays.toLong() - 1L)
        val preferences = normalizePreferences(input.subjectPreferences)

        val days = (0 until input.horizonDays).map { offset ->
            buildDay(
                date = today.plusDays(offset.toLong()),
                today = today,
                nowMinute = nowMinute,
                input = input,
            )
        }

        val plans = input.tasks
            .filter { it.status != TaskStatus.COMPLETED && it.status != TaskStatus.IN_PROGRESS }
            .map { task -> toPlan(task, today, nowMinute, horizonEnd, preferences) }
            .sortedWith(TaskScoring.planComparator)

        val allocation = allocate(plans = plans, days = days, settings = input.settings)
        val blocks = allocation.blocks.sortedWith(
            compareBy({ it.date }, { it.startMinute }, { it.endMinuteExclusive }, { it.taskId }),
        )
        val unscheduled = plans.mapNotNull { plan ->
            val scheduled = allocation.allocatedByTask[plan.taskId] ?: 0
            val remaining = (plan.remainingMinutes - scheduled).coerceAtLeast(0)
            if (remaining <= 0) {
                null
            } else {
                UnscheduledTask(
                    taskId = plan.taskId,
                    title = plan.title,
                    remainingMinutes = remaining,
                    reason = allocation.unscheduledReasons[plan.taskId]
                        ?: "截止日期前无法安排全部时长",
                )
            }
        }

        val conflicts = findFixedConflicts(days)
        val warnings = buildWarnings(
            days = days,
            blocks = blocks,
            unscheduled = unscheduled,
            conflicts = conflicts,
            tasks = input.tasks,
            settings = input.settings,
            sleepConflicts = days.flatMap { it.sleepConflicts },
        )

        return SchedulingResult(
            horizonStart = today,
            horizonEnd = horizonEnd,
            days = days.map { day ->
                SchedulingDay(
                    date = day.date,
                    fixedBlocks = day.fixedBlocks.sortedWith(
                        compareBy({ it.startMinute }, { it.endMinuteExclusive }, { it.title }),
                    ),
                    availableMinutes = day.availableMinutes,
                    fixedStudyMinutes = day.fixedStudyMinutes,
                    dailyLimitMinutes = day.dailyLimitMinutes,
                )
            },
            taskPlans = plans,
            blocks = blocks,
            unscheduled = unscheduled,
            warnings = warnings,
            conflicts = conflicts,
            candidateTaskIds = plans.map(SchedulingTaskPlan::taskId),
            generatedNowMinuteOfDay = nowMinute,
        )
    }

    private fun normalizePreferences(
        preferences: Map<String, SubjectPreference>,
    ): Map<String, SubjectPreference> = preferences.entries
        .mapNotNull { (subject, preference) ->
            val key = subject.trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) null else key to preference
        }
        .toMap()

    private fun toPlan(
        task: StudyTask,
        today: LocalDate,
        nowMinute: Int,
        horizonEnd: LocalDate,
        preferences: Map<String, SubjectPreference>,
    ): SchedulingTaskPlan {
        val score = TaskScoring.score(task, today, nowMinute, preferences)
        val fixedMinutes = task.sessions
            .filter { it.isLocked && isFutureSession(it, today, nowMinute) }
            .sumOf { it.endMinuteExclusive - it.startMinute }
        val remaining = (task.estimatedMinutes - fixedMinutes).coerceAtLeast(0)
        val latestDate = when {
            task.dueDate == null -> horizonEnd
            task.dueDate.isBefore(today) -> horizonEnd
            else -> minOf(task.dueDate, horizonEnd)
        }
        return SchedulingTaskPlan(
            taskId = task.id,
            title = task.title,
            subject = task.subject,
            priority = task.priority,
            dueDate = task.dueDate,
            estimatedMinutes = task.estimatedMinutes,
            fixedMinutes = fixedMinutes,
            remainingMinutes = remaining,
            lockedSessionCount = task.sessions.count { it.isLocked },
            score = score,
            latestDate = latestDate,
            isCarryOver = score.carryOverScore > 0,
        )
    }

    private fun buildDay(
        date: LocalDate,
        today: LocalDate,
        nowMinute: Int,
        input: SchedulingInput,
    ): DayWork {
        val fixedBlocks = mutableListOf<SchedulingFixedBlock>()
        val busy = mutableListOf<Pair<Int, Int>>()
        var fixedStudyMinutes = 0

        ScheduleEngine.instancesForAnchors(input.schedule, date.minusDays(1L), date).forEach { instance ->
            val segment = ScheduleEngine.visibleSegment(instance, date) ?: return@forEach
            if (segment.first >= segment.second) return@forEach
            val kind = when (instance.type) {
                ScheduleItemType.COURSE -> FixedBlockKind.COURSE
                ScheduleItemType.ONE_OFF_EVENT -> FixedBlockKind.EVENT
                ScheduleItemType.ROUTINE -> FixedBlockKind.ROUTINE
                ScheduleItemType.TASK -> FixedBlockKind.TASK
            }
            val subtitle = when (kind) {
                FixedBlockKind.COURSE -> listOfNotNull(
                    instance.subtitle.takeIf(String::isNotBlank),
                    "调课".takeIf { instance.isMoved },
                ).joinToString(" · ")
                FixedBlockKind.EVENT -> "一次性日程"
                FixedBlockKind.ROUTINE -> instance.subtitle
                FixedBlockKind.TASK -> "固定任务"
            }
            fixedBlocks += SchedulingFixedBlock(
                date = date,
                title = instance.title,
                subtitle = subtitle,
                startMinute = segment.first,
                endMinuteExclusive = segment.second,
                kind = kind,
                colorArgb = instance.colorArgb,
            )
            busy += segment.first to segment.second
        }

        input.tasks.forEach { task ->
            task.sessionsVisibleOn(date).forEach { segment ->
                val session = segment.session
                val included = when {
                    task.isCompleted -> true
                    task.status == TaskStatus.IN_PROGRESS -> true
                    session.isLocked -> true
                    else -> false
                }
                if (!included) return@forEach
                fixedBlocks += SchedulingFixedBlock(
                    date = date,
                    title = task.title,
                    subtitle = when {
                        task.isCompleted -> "已完成"
                        task.status == TaskStatus.IN_PROGRESS -> "进行中"
                        session.isLocked -> "已锁定"
                        else -> "任务"
                    },
                    startMinute = segment.startMinute,
                    endMinuteExclusive = segment.endMinuteExclusive,
                    kind = FixedBlockKind.TASK,
                    colorArgb = if (task.isCompleted) TASK_COMPLETED_COLOR_ARGB else TASK_COLOR_ARGB,
                    taskId = task.id,
                    isCompleted = task.isCompleted,
                    isLocked = session.isLocked,
                )
                busy += segment.startMinute to segment.endMinuteExclusive
                val futureSegment = date.isAfter(today) ||
                    (date == today && segment.endMinuteExclusive > nowMinute)
                if (!task.isCompleted && futureSegment) {
                    fixedStudyMinutes += segment.endMinuteExclusive - segment.startMinute
                }
            }
        }

        val sleepBlocks = input.sleepIntervals[date].orEmpty().map { interval ->
            SchedulingFixedBlock(
                date = date,
                title = "睡眠保护",
                subtitle = "睡眠（含睡前缓冲）",
                startMinute = interval.startMinute,
                endMinuteExclusive = interval.endMinuteExclusive,
                kind = FixedBlockKind.ROUTINE,
                colorArgb = REST_COLOR_ARGB,
            )
        }
        sleepBlocks.forEach { block ->
            fixedBlocks += block
            busy += block.startMinute to block.endMinuteExclusive
        }
        val sleepConflicts = input.sleepIntervals[date].orEmpty().flatMap { interval ->
            fixedBlocks.filter { block ->
                block.title != "睡眠保护" &&
                    block.kind != FixedBlockKind.TASK &&
                    block.startMinute < interval.endMinuteExclusive &&
                    interval.startMinute < block.endMinuteExclusive
            }.map { block ->
                "睡眠保护与「${block.title}」重叠 " +
                    "${formatMinute(maxOf(interval.startMinute, block.startMinute))}–" +
                    "${formatMinute(minOf(interval.endMinuteExclusive, block.endMinuteExclusive))}，" +
                    "请手动处理冲突"
            }
        }

        if (date == today && nowMinute > 0) {
            busy += 0 to nowMinute
        }

        val settings = input.settings
        val slots = freeSlots(
            busy = busy,
            windowStart = settings.studyStartMinute,
            windowEnd = settings.studyEndMinuteExclusive,
            bufferMinutes = settings.bufferMinutes,
        )
        return DayWork(
            date = date,
            fixedBlocks = fixedBlocks,
            slots = slots,
            availableMinutes = slots.sumOf { it.end - it.start },
            fixedStudyMinutes = fixedStudyMinutes,
            remainingCap = (settings.dailyLimitMinutes - fixedStudyMinutes).coerceAtLeast(0),
            dailyLimitMinutes = settings.dailyLimitMinutes,
            sleepConflicts = sleepConflicts,
        )
    }

    private fun freeSlots(
        busy: List<Pair<Int, Int>>,
        windowStart: Int,
        windowEnd: Int,
        bufferMinutes: Int,
    ): MutableList<MutableSlot> {
        val clipped = busy.mapNotNull { (start, end) ->
            val clippedStart = maxOf(start, windowStart)
            val clippedEnd = minOf(end, windowEnd)
            if (clippedEnd > clippedStart) clippedStart to clippedEnd else null
        }.sortedWith(compareBy({ it.first }, { it.second }))

        val merged = mutableListOf<Pair<Int, Int>>()
        clipped.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous == null || range.first > previous.second) {
                merged += range
            } else if (range.second > previous.second) {
                merged[merged.lastIndex] = previous.first to range.second
            }
        }

        val slots = mutableListOf<MutableSlot>()
        var cursor = windowStart
        merged.forEach { range ->
            if (range.first > cursor) {
                slotFrom(cursor, range.first, windowStart, windowEnd, bufferMinutes)?.let(slots::add)
            }
            if (range.second > cursor) cursor = range.second
        }
        if (cursor < windowEnd) {
            slotFrom(cursor, windowEnd, windowStart, windowEnd, bufferMinutes)?.let(slots::add)
        }
        return slots
    }

    private fun slotFrom(
        start: Int,
        end: Int,
        windowStart: Int,
        windowEnd: Int,
        bufferMinutes: Int,
    ): MutableSlot? {
        val bufferedStart = if (start == windowStart) start else start + bufferMinutes
        val bufferedEnd = if (end == windowEnd) end else end - bufferMinutes
        val roundedStart = ceilToStep(bufferedStart)
        return if (roundedStart < bufferedEnd) MutableSlot(roundedStart, bufferedEnd) else null
    }

    private fun allocate(
        plans: List<SchedulingTaskPlan>,
        days: List<DayWork>,
        settings: SchedulingSettings,
    ): AllocationResult {
        val blocks = mutableListOf<ProposedSession>()
        val allocatedByTask = mutableMapOf<Long, Int>()
        val unscheduledReasons = mutableMapOf<Long, String>()
        var nextBlockId = 1L

        plans.forEach { plan ->
            if (plan.remainingMinutes <= 0) return@forEach
            var remaining = plan.remainingMinutes
            var proposedCount = 0
            var capHit = false

            allocation@ for (day in days) {
                if (day.date.isAfter(plan.latestDate)) break@allocation
                if (day.remainingCap <= 0) continue
                if (day.remainingCap < SchedulingSettings.MIN_SESSION_MINUTES &&
                    day.remainingCap < remaining
                ) {
                    continue
                }
                var index = 0
                while (remaining > 0 && day.remainingCap > 0 && index < day.slots.size) {
                    if (plan.lockedSessionCount + proposedCount >= MAX_TASK_SESSIONS) {
                        capHit = true
                        break@allocation
                    }
                    val slot = day.slots[index]
                    val slotLength = slot.end - slot.start
                    if (slotLength <= 0) {
                        day.slots.removeAt(index)
                        continue
                    }
                    val length = minOf(
                        remaining,
                        SchedulingSettings.MAX_SESSION_MINUTES,
                        day.remainingCap,
                        slotLength,
                    )
                    if (length <= 0) break
                    if (length < SchedulingSettings.MIN_SESSION_MINUTES && length < remaining) {
                        index++
                        continue
                    }
                    blocks += ProposedSession(
                        id = nextBlockId++,
                        taskId = plan.taskId,
                        taskTitle = plan.title,
                        subject = plan.subject,
                        date = day.date,
                        startMinute = slot.start,
                        endMinuteExclusive = slot.start + length,
                        isLocked = false,
                        score = plan.score,
                    )
                    remaining -= length
                    proposedCount++
                    day.remainingCap -= length
                    val nextStart = ceilToStep(slot.start + length + settings.bufferMinutes)
                    if (nextStart >= slot.end) {
                        day.slots.removeAt(index)
                    } else {
                        slot.start = nextStart
                    }
                }
                if (capHit) break@allocation
            }

            allocatedByTask[plan.taskId] = plan.remainingMinutes - remaining
            if (remaining > 0) {
                unscheduledReasons[plan.taskId] = unscheduledReason(
                    plan = plan,
                    remaining = remaining,
                    days = days,
                    capHit = capHit,
                )
            }
        }

        return AllocationResult(
            blocks = blocks,
            allocatedByTask = allocatedByTask,
            unscheduledReasons = unscheduledReasons,
        )
    }

    private fun unscheduledReason(
        plan: SchedulingTaskPlan,
        remaining: Int,
        days: List<DayWork>,
        capHit: Boolean,
    ): String {
        if (capHit) return "学习会话已达上限（含锁定与新增时段）"
        val eligible = days.filter { !it.date.isAfter(plan.latestDate) }
        val hasCap = eligible.any { it.remainingCap > 0 }
        val hasTime = eligible.any { it.availableMinutes > 0 }
        val hasFittingSlot = eligible.any { day ->
            day.slots.any { slot ->
                slot.end - slot.start >= minOf(remaining, SchedulingSettings.MIN_SESSION_MINUTES)
            }
        }
        return when {
            !hasCap -> "每日学习上限已满（含固定安排）"
            !hasTime -> "没有可用空闲时间（已扣除缓冲）"
            hasFittingSlot -> "截止日期前无法安排全部时长"
            else -> "剩余 $remaining 分钟找不到合适的连续时段"
        }
    }

    private fun findFixedConflicts(days: List<DayWork>): List<SchedulingConflict> {
        val conflicts = mutableListOf<SchedulingConflict>()
        days.forEach { day ->
            val blocks = day.fixedBlocks.filterNot { it.kind == FixedBlockKind.TASK && it.isCompleted }
            for (firstIndex in blocks.indices) {
                for (secondIndex in firstIndex + 1 until blocks.size) {
                    val first = blocks[firstIndex]
                    val second = blocks[secondIndex]
                    if (first.kind != FixedBlockKind.TASK && second.kind != FixedBlockKind.TASK) {
                        continue
                    }
                    if (first.startMinute < second.endMinuteExclusive &&
                        second.startMinute < first.endMinuteExclusive
                    ) {
                        conflicts += SchedulingConflict(
                            date = day.date,
                            firstTitle = first.title,
                            secondTitle = second.title,
                            firstKind = first.kind,
                            secondKind = second.kind,
                            startMinute = maxOf(first.startMinute, second.startMinute),
                            endMinuteExclusive = minOf(
                                first.endMinuteExclusive,
                                second.endMinuteExclusive,
                            ),
                        )
                    }
                }
            }
        }
        return conflicts
            .distinctBy {
                listOf(it.date, it.firstTitle, it.secondTitle, it.startMinute, it.endMinuteExclusive)
            }
            .sortedWith(compareBy({ it.date }, { it.startMinute }, { it.firstTitle }))
            .take(MAX_CONFLICT_RESULTS)
    }

    private fun buildWarnings(
        days: List<DayWork>,
        blocks: List<ProposedSession>,
        unscheduled: List<UnscheduledTask>,
        conflicts: List<SchedulingConflict>,
        tasks: List<StudyTask>,
        settings: SchedulingSettings,
        sleepConflicts: List<String>,
    ): List<SchedulingWarning> {
        val warnings = mutableListOf<SchedulingWarning>()
        val proposedByDate = blocks.groupBy(ProposedSession::date)
            .mapValues { (_, sessions) -> sessions.sumOf(ProposedSession::durationMinutes) }
        val hasUnscheduled = unscheduled.isNotEmpty()

        if (hasUnscheduled && days.all { it.availableMinutes == 0 }) {
            warnings += SchedulingWarning(
                type = SchedulingWarningType.NO_FREE_TIME,
                message = "所选范围内没有可用于新学习任务的空闲时间（已扣除固定安排与缓冲）",
            )
        }

        days.forEach { day ->
            if (day.fixedStudyMinutes > settings.dailyLimitMinutes) {
                warnings += SchedulingWarning(
                    type = SchedulingWarningType.FIXED_OVER_LIMIT,
                    message = "该日固定学习已超过每日上限 ${day.fixedStudyMinutes} 分钟",
                    date = day.date,
                )
            } else if (hasUnscheduled) {
                val proposed = proposedByDate[day.date] ?: 0
                if (day.availableMinutes > 0 &&
                    day.fixedStudyMinutes + proposed >= settings.dailyLimitMinutes
                ) {
                    warnings += SchedulingWarning(
                        type = SchedulingWarningType.DAILY_LIMIT,
                        message = "该日学习安排已达到每日上限 ${settings.dailyLimitMinutes} 分钟",
                        date = day.date,
                    )
                }
            }
        }

        if (hasUnscheduled) {
            warnings += SchedulingWarning(
                type = SchedulingWarningType.PARTIAL,
                message = "有 ${unscheduled.size} 项任务未完全安排，共 " +
                    "${unscheduled.sumOf(UnscheduledTask::remainingMinutes)} 分钟",
            )
        }

        tasks.filterNot(StudyTask::isCompleted).forEach { task ->
            val due = task.dueDate ?: return@forEach
            val lateSessions = task.sessions.filter { session ->
                (session.isLocked || task.status == TaskStatus.IN_PROGRESS) && session.endDate > due
            }
            if (lateSessions.isNotEmpty()) {
                warnings += SchedulingWarning(
                    type = SchedulingWarningType.LOCKED_AFTER_DUE,
                    message = "「${task.title}」存在晚于截止日期 ${due} 的锁定/进行中时段",
                    taskId = task.id,
                )
            }
        }

        if (conflicts.isNotEmpty()) {
            warnings += SchedulingWarning(
                type = SchedulingWarningType.CONFLICT,
                message = "检测到 ${conflicts.size} 处固定安排冲突；请在任务页调整锁定时间",
            )
        }
        sleepConflicts.distinct().take(MAX_CONFLICT_RESULTS).forEach { message ->
            warnings += SchedulingWarning(
                type = SchedulingWarningType.SLEEP_CONFLICT,
                message = message,
            )
        }

        return warnings.sortedWith(
            compareBy<SchedulingWarning>({ it.date == null }, { it.date }, { it.message }),
        )
    }

    private fun isFutureSession(
        session: com.zhixu.studyos.core.model.TaskSession,
        today: LocalDate,
        nowMinute: Int,
    ): Boolean {
        val endLocal = session.endDate.toEpochDay() * MINUTES_PER_DAY + session.endMinuteOfDay
        val nowLocal = today.toEpochDay() * MINUTES_PER_DAY + nowMinute
        return endLocal > nowLocal
    }

    private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun ceilToStep(minute: Int): Int {
        val step = SchedulingSettings.TIME_STEP_MINUTES
        return ((minute + step - 1) / step) * step
    }

    private data class MutableSlot(
        var start: Int,
        val end: Int,
    )

    private class DayWork(
        val date: LocalDate,
        val fixedBlocks: MutableList<SchedulingFixedBlock>,
        val slots: MutableList<MutableSlot>,
        val availableMinutes: Int,
        val fixedStudyMinutes: Int,
        var remainingCap: Int,
        val dailyLimitMinutes: Int,
        val sleepConflicts: List<String>,
    )

    private data class AllocationResult(
        val blocks: List<ProposedSession>,
        val allocatedByTask: Map<Long, Int>,
        val unscheduledReasons: Map<Long, String>,
    )
}
