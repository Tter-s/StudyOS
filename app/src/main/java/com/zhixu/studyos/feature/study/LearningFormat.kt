package com.zhixu.studyos.feature.study

import com.zhixu.studyos.core.model.BlockerReason
import com.zhixu.studyos.core.model.ProblemStatus
import com.zhixu.studyos.core.model.ProcrastinationReason
import com.zhixu.studyos.core.model.StudyTimerMode
import com.zhixu.studyos.core.model.Subject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val learningDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)
private val shortLearningDateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.ROOT)

fun formatLearningMinutes(minutes: Long): String {
    if (minutes <= 0L) return "0 分钟"
    val hours = minutes / 60L
    val remainder = minutes % 60L
    return when {
        hours == 0L -> "$remainder 分钟"
        remainder == 0L -> "$hours 小时"
        else -> "$hours 小时 $remainder 分"
    }
}

fun formatLearningMillis(millis: Long): String = formatLearningMinutes(millis / 60_000L)

fun formatSubjectName(subjects: List<Subject>, subjectId: Long): String =
    subjects.firstOrNull { it.id == subjectId }?.name ?: "未分类科目"

fun formatLearningDate(date: LocalDate): String = date.format(learningDateFormatter)

fun formatShortLearningDate(date: LocalDate): String = date.format(shortLearningDateFormatter)

fun formatLearningRate(rate: Float?): String =
    if (rate == null) "暂无计划" else "${(rate * 100).toInt()}%"

fun formatSignedMinutes(minutes: Int): String = when {
    minutes > 0 -> "比计划多 $minutes 分钟"
    minutes < 0 -> "比计划少 ${-minutes} 分钟"
    else -> "与计划一致"
}

val StudyTimerMode.label: String
    get() = when (this) {
        StudyTimerMode.STOPWATCH -> "正计时"
        StudyTimerMode.POMODORO -> "番茄钟"
        StudyTimerMode.MANUAL -> "手动补录"
    }

val ProblemStatus.label: String
    get() = when (this) {
        ProblemStatus.TODO -> "未开始"
        ProblemStatus.IN_PROGRESS -> "练习中"
        ProblemStatus.COMPLETED -> "已完成"
    }

val BlockerReason.label: String
    get() = when (this) {
        BlockerReason.CONCEPT -> "不理解概念"
        BlockerReason.THEOREM -> "想不到相关定理"
        BlockerReason.PROOF_CONSTRUCTION -> "不会构造证明"
        BlockerReason.CALCULATION -> "计算困难"
        BlockerReason.REPRODUCTION -> "看懂但无法独立复现"
        BlockerReason.OTHER -> "其他"
    }

val ProcrastinationReason.label: String
    get() = when (this) {
        ProcrastinationReason.TOO_DIFFICULT -> "任务太难"
        ProcrastinationReason.UNCLEAR_START -> "不知道从哪开始"
        ProcrastinationReason.FATIGUE -> "疲劳"
        ProcrastinationReason.DISTRACTION -> "手机或环境干扰"
        ProcrastinationReason.EMOTION -> "情绪影响"
        ProcrastinationReason.OTHER -> "其他"
    }
