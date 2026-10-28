package com.zhixu.studyos.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * V2.0 新增持久化实体。
 *
 * 设计约束：
 * - 所有字段都有明确默认值，V1.0 数据迁移不需要重建旧表；
 * - 历史、睡眠、词汇、知识图谱、考试目标、AI 结果与同步日志各自成表，
 *   不把全部数据塞进任务表或单个超宽表；
 * - 金额仍以整数分保存，日期以 epochDay、时间以分钟保存，与 V1.0 口径一致。
 */

// ---------------- 任务与对象历史 ----------------

@Entity(
    tableName = "task_events",
    indices = [
        Index(value = ["objectType", "objectId"]),
        Index(value = ["occurredAtEpochMillis"]),
        Index(value = ["eventType"]),
    ],
)
data class TaskEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectType: String,
    val objectId: Long,
    val eventType: String,
    val occurredAtEpochMillis: Long,
    val beforeJson: String?,
    val afterJson: String?,
    val source: String,
    val reason: String?,
    val sessionId: Long?,
    val revision: Int = 0,
)

// ---------------- 睡眠保护 ----------------

@Entity(tableName = "sleep_schedule")
data class SleepScheduleEntity(
    @PrimaryKey val id: Int = 1,
    val weekdayBedMinute: Int,
    val weekdayWakeMinute: Int,
    val weekendBedMinute: Int,
    val weekendWakeMinute: Int,
    val earliestWakeMinute: Int,
    val latestBedMinute: Int,
    val preSleepBufferMinutes: Int,
    val napEnabled: Boolean,
    val napStartMinute: Int,
    val napEndMinute: Int,
    val examPeriodStartEpochDay: Long?,
    val examPeriodEndEpochDay: Long?,
    val examBedMinute: Int?,
    val examWakeMinute: Int?,
    @ColumnInfo(defaultValue = "0") val bedtimeModeEnabled: Boolean = false,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "sleep_date_overrides")
data class SleepDateOverrideEntity(
    @PrimaryKey val dateEpochDay: Long,
    val bedMinute: Int,
    val wakeMinute: Int,
    val note: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "sleep_records")
data class SleepRecordEntity(
    @PrimaryKey val dateEpochDay: Long,
    val bedTimeEpochMillis: Long?,
    val wakeTimeEpochMillis: Long?,
    val quality: Int?,
    val nextDayEnergy: Int?,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "sleep_change_history",
    indices = [Index(value = ["changedAtEpochMillis"])],
)
data class SleepChangeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val changedAtEpochMillis: Long,
    val beforeJson: String,
    val afterJson: String,
    val reason: String,
)

// ---------------- 英语词汇与间隔重复 ----------------

@Entity(
    tableName = "vocabulary_words",
    indices = [
        Index(value = ["normalizedWord"], unique = true),
        Index(value = ["dueDateEpochDay"]),
        Index(value = ["status"]),
    ],
)
data class VocabularyWordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedWord: String,
    val word: String,
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val meaning: String = "",
    val example: String = "",
    val listName: String = "自定义",
    val note: String = "",
    val status: String = "NEW",
    val familiarity: Int = 0,
    val repetition: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Double = 2.5,
    val dueDateEpochDay: Long,
    val lastReviewedAtEpochMillis: Long? = null,
    val reviewCount: Int = 0,
    val lapseCount: Int = 0,
    val source: String = "USER",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val revision: Int = 1,
)

@Entity(
    tableName = "vocabulary_reviews",
    foreignKeys = [
        ForeignKey(
            entity = VocabularyWordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["wordId"]), Index(value = ["reviewedAtEpochMillis"])],
)
data class VocabularyReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val reviewedAtEpochMillis: Long,
    val rating: String,
    val recalled: Boolean,
    val previousIntervalDays: Int,
    val newIntervalDays: Int,
    val nextDueEpochDay: Long,
    val responseTimeMillis: Long?,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "vocabulary_settings")
data class VocabularySettingsEntity(
    @PrimaryKey val id: Int = 1,
    val dailyNewLimit: Int = 20,
    val dailyReviewLimit: Int = 100,
    val dailyTimeBudgetMinutes: Int = 30,
    val examDateEpochDay: Long? = null,
    val taskGenerationEnabled: Boolean = false,
    val lastTaskGeneratedEpochDay: Long? = null,
    val updatedAtEpochMillis: Long,
)

// ---------------- 数学知识图谱 ----------------

@Entity(
    tableName = "knowledge_nodes",
    indices = [
        Index(value = ["subject", "name"], unique = true),
        Index(value = ["subject", "chapter"]),
    ],
)
data class KnowledgeNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val chapter: String,
    val name: String,
    val definition: String = "",
    val theorems: String = "",
    val note: String = "",
    val mastery: Int = 0,
    val masterySource: String = "USER",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val revision: Int = 1,
)

@Entity(
    tableName = "knowledge_dependencies",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["fromNodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["toNodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fromNodeId", "toNodeId"], unique = true),
        Index(value = ["toNodeId"]),
    ],
)
data class KnowledgeDependencyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 前置知识点。 */
    val fromNodeId: Long,
    /** 后续知识点。 */
    val toNodeId: Long,
    val source: String = "USER",
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "knowledge_problem_links",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["nodeId", "problemId"], unique = true), Index(value = ["problemId"])],
)
data class KnowledgeProblemLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: Long,
    val problemId: Long,
    val source: String = "USER",
    val createdAtEpochMillis: Long,
)

// ---------------- 考试与长期目标 ----------------

@Entity(
    tableName = "exams",
    indices = [Index(value = ["dateEpochDay"]), Index(value = ["subject"])],
)
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val subject: String,
    val dateEpochDay: Long,
    val startMinute: Int?,
    val durationMinutes: Int?,
    val scope: String = "",
    val targetScore: String = "",
    val materials: String = "",
    val reminderEnabled: Boolean = true,
    val reminderDaysBefore: Int = 3,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val revision: Int = 1,
)

@Entity(
    tableName = "long_term_goals",
    indices = [Index(value = ["status"]), Index(value = ["targetDateEpochDay"])],
)
data class LongTermGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val description: String = "",
    val startDateEpochDay: Long,
    val targetDateEpochDay: Long?,
    val status: String = "ACTIVE",
    val progressSource: String = "MILESTONE",
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val revision: Int = 1,
)

@Entity(
    tableName = "goal_milestones",
    foreignKeys = [
        ForeignKey(
            entity = LongTermGoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["goalId"]), Index(value = ["dueDateEpochDay"])],
)
data class GoalMilestoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val title: String,
    val dueDateEpochDay: Long?,
    val weight: Int = 1,
    val status: String = "PENDING",
    val completedAtEpochMillis: Long? = null,
    val sortOrder: Int = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

// ---------------- 习题尝试、提示与能力估计 ----------------

@Entity(
    tableName = "exercise_attempts",
    indices = [
        Index(value = ["problemType", "problemId"]),
        Index(value = ["submittedAtEpochMillis"]),
    ],
)
data class ExerciseAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val problemType: String,
    val problemId: Long,
    val attemptIndex: Int,
    val startedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long,
    val durationMinutes: Int,
    val highestHintLevel: Int,
    val independent: Boolean,
    val correct: Boolean,
    val note: String = "",
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "exercise_hint_records",
    indices = [
        Index(value = ["problemType", "problemId"]),
        Index(value = ["requestedAtEpochMillis"]),
    ],
)
data class ExerciseHintRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val problemType: String,
    val problemId: Long,
    val level: Int,
    val requestedAtEpochMillis: Long,
    val content: String,
    val usedCache: Boolean,
    val model: String,
    val costMicros: Long,
    val createdProof: Boolean = false,
)

@Entity(
    tableName = "exercise_profiles",
    primaryKeys = ["problemType", "problemId"],
    indices = [Index(value = ["updatedAtEpochMillis"])],
)
data class ExerciseProfileEntity(
    val problemType: String,
    val problemId: Long,
    val personalDifficulty: Double? = null,
    val difficultySampleCount: Int = 0,
    val attemptCount: Int = 0,
    val independentCount: Int = 0,
    val hintLevelSum: Int = 0,
    val avgActualMinutes: Double = 0.0,
    val estimatedMinutes: Int? = null,
    val mastery: Double = 0.0,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "exercise_saved_proofs",
    indices = [Index(value = ["problemType", "problemId"])],
)
data class SavedProofEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val problemType: String,
    val problemId: Long,
    val content: String,
    /** USER_CONFIRMED 或 USER_EDITED_AI。 */
    val source: String,
    val note: String = "",
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "exercise_error_reports",
    indices = [Index(value = ["problemType", "problemId"]), Index(value = ["createdAtEpochMillis"])],
)
data class ExerciseErrorReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val problemType: String,
    val problemId: Long,
    val hintLevel: Int?,
    val reason: String,
    val note: String = "",
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "ability_estimates")
data class AbilityEstimateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    val scopeKey: String,
    val ability: Double,
    val sampleCount: Int,
    val updatedAtEpochMillis: Long,
)

// ---------------- AI 结果与分析记录 ----------------

@Entity(
    tableName = "ai_analysis_records",
    indices = [Index(value = ["taskType"]), Index(value = ["createdAtEpochMillis"])],
)
data class AiAnalysisRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String,
    val objectType: String,
    val objectId: Long?,
    val createdAtEpochMillis: Long,
    val model: String,
    val promptVersion: String,
    val resultJson: String,
    val costMicros: Long,
    val usedCache: Boolean,
    val userAccepted: Boolean? = null,
    val note: String = "",
)

@Entity(
    tableName = "weekly_reports",
    indices = [Index(value = ["weekStartEpochDay"], unique = true)],
)
data class WeeklyReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStartEpochDay: Long,
    val generatedAtEpochMillis: Long,
    val reportJson: String,
    val aiSummary: String? = null,
    val aiGeneratedAtEpochMillis: Long? = null,
    val aiModel: String? = null,
)

// ---------------- 本地同步 ----------------

@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["objectType", "objectId"]),
        Index(value = ["occurredAtEpochMillis"]),
    ],
)
data class SyncOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val objectType: String,
    val objectId: Long,
    val operation: String,
    val revision: Int,
    val payloadHash: String,
    val payloadJson: String,
    val occurredAtEpochMillis: Long,
    val deviceId: String,
    val syncedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "sync_baselines",
    primaryKeys = ["objectType", "objectId"],
)
data class SyncBaselineEntity(
    val objectType: String,
    val objectId: Long,
    val revision: Int,
    val payloadHash: String,
    val syncedAtEpochMillis: Long,
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["objectType", "objectId"],
    indices = [Index(value = ["deletedAtEpochMillis"])],
)
data class SyncTombstoneEntity(
    val objectType: String,
    val objectId: Long,
    val deletedAtEpochMillis: Long,
    val originDeviceId: String,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    val lastSyncAtEpochMillis: Long?,
    val lastConflictCount: Int,
    val updatedAtEpochMillis: Long,
)

// ---------------- 首页卡片配置（DataStore 之外的结构化偏好） ----------------

@Entity(tableName = "home_card_preferences")
data class HomeCardPreferenceEntity(
    @PrimaryKey val cardId: String,
    val visible: Boolean,
    val sortOrder: Int,
)
