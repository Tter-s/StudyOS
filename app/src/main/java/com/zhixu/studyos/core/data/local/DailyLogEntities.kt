package com.zhixu.studyos.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * V3.1 极简数据表。
 *
 * 旧的 `study_tasks`、学习计时、题库等表保持不变，仅作为历史数据归档；
 * 新界面只读写下面三张表，避免继承旧的科目/排程/评分语义。
 */
@Entity(
    tableName = "daily_tasks",
    indices = [Index(value = ["dateEpochDay"])],
)
data class DailyTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val title: String,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val sortOrder: Int = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val dateEpochDay: Long,
    val content: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "entertainment_records")
data class EntertainmentRecordEntity(
    @PrimaryKey val dateEpochDay: Long,
    val minutes: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
