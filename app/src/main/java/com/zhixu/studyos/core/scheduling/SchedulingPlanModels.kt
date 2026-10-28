package com.zhixu.studyos.core.scheduling

import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.ScheduleSnapshot
import com.zhixu.studyos.core.model.StudyTask
import com.zhixu.studyos.core.model.SubjectPreference
import com.zhixu.studyos.core.model.TaskPriority
import com.zhixu.studyos.core.sleep.SleepInterval
import java.time.LocalDate
import java.time.ZoneId

data class SchedulingInput(
    val today: LocalDate,
    val nowMinuteOfDay: Int,
    val horizonDays: Int,
    val schedule: ScheduleSnapshot,
    val tasks: List<StudyTask>,
    val settings: SchedulingSettings,
    val subjectPreferences: Map<String, SubjectPreference>,
    val zoneId: ZoneId,
    val sleepIntervals: Map<LocalDate, List<SleepInterval>> = emptyMap(),
)

enum class FixedBlockKind {
    COURSE,
    EVENT,
    ROUTINE,
    TASK,
}

data class SchedulingFixedBlock(
    val date: LocalDate,
    val title: String,
    val subtitle: String,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val kind: FixedBlockKind,
    val colorArgb: Int,
    val taskId: Long? = null,
    val isCompleted: Boolean = false,
    val isLocked: Boolean = false,
)

data class TaskScoreBreakdown(
    val deadlineScore: Int,
    val priorityScore: Int,
    val subjectScore: Int,
    val carryOverScore: Int,
    val total: Int,
    val reasons: List<String>,
)

data class SchedulingTaskPlan(
    val taskId: Long,
    val title: String,
    val subject: String,
    val priority: TaskPriority,
    val dueDate: LocalDate?,
    val estimatedMinutes: Int,
    val fixedMinutes: Int,
    val remainingMinutes: Int,
    val lockedSessionCount: Int,
    val score: TaskScoreBreakdown,
    val latestDate: LocalDate,
    val isCarryOver: Boolean,
)

data class ProposedSession(
    val id: Long,
    val taskId: Long,
    val taskTitle: String,
    val subject: String,
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val isLocked: Boolean = false,
    val score: TaskScoreBreakdown,
) {
    val durationMinutes: Int get() = endMinuteExclusive - startMinute
}

data class SchedulingDay(
    val date: LocalDate,
    val fixedBlocks: List<SchedulingFixedBlock>,
    val availableMinutes: Int,
    val fixedStudyMinutes: Int,
    val dailyLimitMinutes: Int,
)

enum class SchedulingWarningType {
    NO_FREE_TIME,
    DAILY_LIMIT,
    FIXED_OVER_LIMIT,
    PARTIAL,
    LOCKED_AFTER_DUE,
    CONFLICT,
    SLEEP_CONFLICT,
}

data class SchedulingWarning(
    val type: SchedulingWarningType,
    val message: String,
    val date: LocalDate? = null,
    val taskId: Long? = null,
)

data class SchedulingConflict(
    val date: LocalDate,
    val firstTitle: String,
    val secondTitle: String,
    val firstKind: FixedBlockKind,
    val secondKind: FixedBlockKind,
    val startMinute: Int,
    val endMinuteExclusive: Int,
)

data class UnscheduledTask(
    val taskId: Long,
    val title: String,
    val remainingMinutes: Int,
    val reason: String,
)

data class SchedulingResult(
    val horizonStart: LocalDate,
    val horizonEnd: LocalDate,
    val days: List<SchedulingDay>,
    val taskPlans: List<SchedulingTaskPlan>,
    val blocks: List<ProposedSession>,
    val unscheduled: List<UnscheduledTask>,
    val warnings: List<SchedulingWarning>,
    val conflicts: List<SchedulingConflict>,
    val candidateTaskIds: List<Long>,
    val generatedNowMinuteOfDay: Int,
) {
    val totalProposedMinutes: Int get() = blocks.sumOf(ProposedSession::durationMinutes)
    val totalFixedStudyMinutes: Int get() = days.sumOf(SchedulingDay::fixedStudyMinutes)
}
