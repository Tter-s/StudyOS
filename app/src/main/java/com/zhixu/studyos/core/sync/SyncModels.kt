package com.zhixu.studyos.core.sync

import java.time.Instant

/** 可增量同步的数据范围。用户可在每次同步时选择。 */
enum class SyncScope(val key: String, val label: String, val objectType: String, val table: String) {
    TASKS("tasks", "任务与学习时段", "TASK", "study_tasks"),
    FINANCE("finance", "财务流水", "FINANCE", "finance_transactions"),
    VOCABULARY("vocabulary", "英语词汇", "VOCABULARY", "vocabulary_words"),
    KNOWLEDGE("knowledge", "数学知识点", "KNOWLEDGE", "knowledge_nodes"),
    EXAMS("exams", "考试安排", "EXAM", "exams"),
    GOALS("goals", "长期目标", "GOAL", "long_term_goals"),
}

enum class SyncOperationType { INSERT, UPDATE, DELETE }

/** 同步对象摘要，用于冲突列表。 */
data class SyncObjectSummary(
    val objectType: String,
    val objectId: Long,
    val title: String,
    val detail: String,
)

data class SyncConflict(
    val objectType: String,
    val objectId: Long,
    val local: SyncObjectSummary?,
    val remote: SyncObjectSummary?,
    val description: String,
)

enum class ConflictResolution { KEEP_LOCAL, USE_REMOTE }

data class SyncBundle(
    val formatVersion: Int,
    val deviceId: String,
    val exportedAt: Instant,
    val scopes: List<SyncScope>,
    val objects: List<SyncObject>,
    val tombstones: List<SyncTombstone>,
)

data class SyncObject(
    val objectType: String,
    val objectId: Long,
    val revision: Int,
    val updatedAtEpochMillis: Long,
    val payloadHash: String,
    val payloadJson: String,
) {
    val key: String get() = "$objectType:$objectId"
}

data class SyncTombstone(
    val objectType: String,
    val objectId: Long,
    val deletedAtEpochMillis: Long,
)

data class SyncPreview(
    val bundle: SyncBundle,
    val scopeLabels: List<String>,
    val newObjects: List<SyncObjectSummary>,
    val updatedObjects: List<SyncObjectSummary>,
    val conflicts: List<SyncConflict>,
    val remoteDeletions: List<SyncObjectSummary>,
    val warnings: List<String>,
)

data class SyncApplyResult(
    val imported: Int,
    val updated: Int,
    val deleted: Int,
    val conflictsResolved: Int,
    val conflictsRemaining: Int,
    val baselinesUpdated: Int,
)

data class SyncExportResult(
    val fileName: String,
    val sizeBytes: Long,
    val objectCount: Int,
    val tombstoneCount: Int,
    val scopeLabels: List<String>,
)

class SyncValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 云端传输适配接口。当前没有配置任何云服务，因此默认实现明确返回未配置。 */
interface SyncTransport {
    val name: String
    val configured: Boolean

    suspend fun isAvailable(): Boolean

    suspend fun push(bundle: SyncBundle)

    suspend fun pull(): SyncBundle?
}

class UnconfiguredCloudSyncTransport(
    override val name: String = "云端同步（未配置）",
) : SyncTransport {
    override val configured: Boolean = false

    override suspend fun isAvailable(): Boolean = false

    override suspend fun push(bundle: SyncBundle) {
        throw SyncValidationException("尚未配置云端同步服务；本地数据没有上传")
    }

    override suspend fun pull(): SyncBundle? {
        throw SyncValidationException("尚未配置云端同步服务；本地数据没有上传")
    }
}
