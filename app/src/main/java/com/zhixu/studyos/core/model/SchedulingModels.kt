package com.zhixu.studyos.core.model

enum class SubjectPreference {
    LOWER,
    NORMAL,
    HIGHER,
}

/**
 * 自动排程的可持久化输入。时间使用当地民用分钟，结束分钟为排他边界。
 * 自动排程只生成本地自然日内的学习时段，不跨越午夜。
 */
data class SchedulingSettings(
    val dailyLimitMinutes: Int = DEFAULT_DAILY_LIMIT_MINUTES,
    val bufferMinutes: Int = DEFAULT_BUFFER_MINUTES,
    val studyStartMinute: Int = DEFAULT_STUDY_START_MINUTE,
    val studyEndMinuteExclusive: Int = DEFAULT_STUDY_END_MINUTE,
    val planReminderEnabled: Boolean = false,
    val planReminderHour: Int = DEFAULT_REMINDER_HOUR,
    val planReminderMinute: Int = DEFAULT_REMINDER_MINUTE,
) {
    fun validationError(): String? = when {
        dailyLimitMinutes !in 30..24 * 60 -> "每日学习上限应在 30–1440 分钟之间"
        bufferMinutes !in 0..120 -> "缓冲时间应在 0–120 分钟之间"
        studyStartMinute !in 0 until MINUTES_PER_DAY -> "学习窗口开始时间无效"
        studyEndMinuteExclusive !in 1..MINUTES_PER_DAY -> "学习窗口结束时间无效"
        studyEndMinuteExclusive <= studyStartMinute -> "学习窗口结束时间必须晚于开始时间"
        planReminderHour !in 0..23 -> "提醒小时应在 0–23 之间"
        planReminderMinute !in 0..59 -> "提醒分钟应在 0–59 之间"
        else -> null
    }

    fun requireValid() {
        require(validationError() == null) { validationError() ?: "排程设置无效" }
    }

    companion object {
        const val DEFAULT_DAILY_LIMIT_MINUTES = 240
        const val DEFAULT_BUFFER_MINUTES = 10
        const val DEFAULT_STUDY_START_MINUTE = 8 * 60
        const val DEFAULT_STUDY_END_MINUTE = 22 * 60
        const val DEFAULT_REMINDER_HOUR = 7
        const val DEFAULT_REMINDER_MINUTE = 30
        const val MAX_SESSION_MINUTES = 50
        const val MIN_SESSION_MINUTES = 20
        const val TIME_STEP_MINUTES = 5

        val Default = SchedulingSettings()
    }
}

data class SchedulingSubjectPreference(
    val subjectName: String,
    val preference: SubjectPreference,
)
