package com.zhixu.studyos.core.backup

import java.time.Instant

/** 备份格式版本。结构或字段含义变化时递增，并保留对旧版本的读取逻辑。 */
const val BACKUP_FORMAT_VERSION = 1

/**
 * 备份包含的业务表（父表在前，恢复时按此顺序插入、逆序删除）。
 *
 * AI 相关表与 DataStore 偏好/密钥不在其中：
 * - `ai_usage_daily`、`ai_analysis_cache`、`ai_plan_records` 属于可选 AI 数据，可能含任务标题，
 *   且缓存可重新生成；
 * - AI 配置在 DataStore、API Key 在 Android Keystore 加密存储，均不进入普通备份。
 */
object BackupTables {
    val ordered: List<String> = listOf(
        "semester_settings",
        "subjects",
        "courses",
        "course_exceptions",
        "one_off_events",
        "routine_blocks",
        "study_tasks",
        "task_sessions",
        "study_timing_sessions",
        "problems",
        "self_control_entries",
        "learning_goals",
        "finance_categories",
        "finance_transactions",
        "finance_monthly_budgets",
        "finance_category_budgets",
        "scheduling_settings",
        "scheduling_subject_preferences",
        "problem_documents",
        "problem_document_pages",
        "problem_import_batches",
        "problem_import_items",
        "problem_bank_entries",
        "problem_bank_formulas",
        "problem_bank_knowledge_points",
        // V2.0
        "task_events",
        "sleep_schedule",
        "sleep_date_overrides",
        "sleep_records",
        "sleep_change_history",
        "vocabulary_words",
        "vocabulary_reviews",
        "vocabulary_settings",
        "knowledge_nodes",
        "knowledge_dependencies",
        "knowledge_problem_links",
        "exams",
        "long_term_goals",
        "goal_milestones",
        "exercise_attempts",
        "exercise_hint_records",
        "exercise_profiles",
        "exercise_saved_proofs",
        "exercise_error_reports",
        "ability_estimates",
        "ai_analysis_records",
        "weekly_reports",
        "sync_operations",
        "sync_baselines",
        "sync_tombstones",
        "sync_state",
        "home_card_preferences",
        // V3.1 极简生活记录；旧表继续随备份保留，避免升级后丢失历史数据。
        "daily_tasks",
        "daily_summaries",
        "entertainment_records",
    )

    val excluded: List<String> = listOf(
        "ai_usage_daily",
        "ai_analysis_cache",
        "ai_plan_records",
    )

    /** 旧版本备份可能缺少 V2.0 新表；恢复时按空表处理，而不是拒绝整个备份。 */
    fun mayBeAbsentInOlderBackups(table: String): Boolean = table !in setOf(
        "semester_settings",
        "subjects",
        "courses",
        "course_exceptions",
        "one_off_events",
        "routine_blocks",
        "study_tasks",
        "task_sessions",
        "study_timing_sessions",
        "problems",
        "self_control_entries",
        "learning_goals",
        "finance_categories",
        "finance_transactions",
        "finance_monthly_budgets",
        "finance_category_budgets",
        "scheduling_settings",
        "scheduling_subject_preferences",
        "problem_documents",
        "problem_document_pages",
        "problem_import_batches",
        "problem_import_items",
        "problem_bank_entries",
        "problem_bank_formulas",
        "problem_bank_knowledge_points",
    )

    const val DOCUMENTS_ROOT = "problem_bank_documents"
}

data class BackupManifest(
    val formatVersion: Int,
    val databaseVersion: Int,
    val appVersion: String,
    val createdAt: Instant,
    val timezone: String,
    val tableCounts: Map<String, Int>,
    val documentCount: Int,
    val documentFileCount: Int,
    val documentTotalBytes: Long,
    val excluded: List<String>,
) {
    val totalRows: Int get() = tableCounts.values.sum()
}

data class BackupSummary(
    val fileName: String,
    val sizeBytes: Long,
    val manifest: BackupManifest,
)

/** 恢复前预览：只读校验结果，不修改任何数据。 */
data class BackupPreview(
    val manifest: BackupManifest,
    val sourceName: String,
    val sizeBytes: Long,
    val warnings: List<String>,
    val documentPaths: Set<String>,
    val referencedDocumentPaths: List<String>,
)

data class RestoreResult(
    val restoredCounts: Map<String, Int>,
    val restoredDocumentFiles: Int,
    val manifestCreatedAt: Instant,
)

class BackupValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class CurrentDataSummary(
    val tableCounts: Map<String, Int>,
    val documentCount: Int,
    val documentFileCount: Int,
    val documentTotalBytes: Long,
) {
    val totalRows: Int get() = tableCounts.values.sum()
}
