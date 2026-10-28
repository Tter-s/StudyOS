package com.zhixu.studyos.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.zhixu.studyos.core.model.SchedulingSettings
import com.zhixu.studyos.core.model.SchedulingSubjectPreference
import com.zhixu.studyos.core.model.SubjectPreference

@Entity(tableName = "scheduling_settings")
data class SchedulingSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val dailyLimitMinutes: Int,
    val bufferMinutes: Int,
    val studyStartMinute: Int,
    val studyEndMinuteExclusive: Int,
    val planReminderEnabled: Boolean,
    val planReminderHour: Int,
    val planReminderMinute: Int,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "scheduling_subject_preferences")
data class SchedulingSubjectPreferenceEntity(
    @PrimaryKey val subjectName: String,
    val level: String,
    val updatedAtEpochMillis: Long,
)

fun SchedulingSettingsEntity.toDomainModel() = SchedulingSettings(
    dailyLimitMinutes = dailyLimitMinutes,
    bufferMinutes = bufferMinutes,
    studyStartMinute = studyStartMinute,
    studyEndMinuteExclusive = studyEndMinuteExclusive,
    planReminderEnabled = planReminderEnabled,
    planReminderHour = planReminderHour,
    planReminderMinute = planReminderMinute,
)

fun SchedulingSettings.toEntity(updatedAtEpochMillis: Long) = SchedulingSettingsEntity(
    dailyLimitMinutes = dailyLimitMinutes,
    bufferMinutes = bufferMinutes,
    studyStartMinute = studyStartMinute,
    studyEndMinuteExclusive = studyEndMinuteExclusive,
    planReminderEnabled = planReminderEnabled,
    planReminderHour = planReminderHour,
    planReminderMinute = planReminderMinute,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

fun SchedulingSubjectPreferenceEntity.toDomainModel() = SchedulingSubjectPreference(
    subjectName = subjectName,
    preference = level
        .let { runCatching { SubjectPreference.valueOf(it) }.getOrNull() }
        ?: SubjectPreference.NORMAL,
)
