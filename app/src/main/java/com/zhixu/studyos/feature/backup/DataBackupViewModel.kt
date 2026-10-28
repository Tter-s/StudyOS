package com.zhixu.studyos.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.backup.BackupPreview
import com.zhixu.studyos.core.backup.BackupService
import com.zhixu.studyos.core.backup.BackupSummary
import com.zhixu.studyos.core.backup.CurrentDataSummary
import com.zhixu.studyos.core.backup.DataExportService
import com.zhixu.studyos.core.backup.RestoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExportKind(val label: String, val fileName: String) {
    TASKS("任务", "studyos-tasks"),
    TIMINGS("学习计时", "studyos-timings"),
    PROBLEMS("习题记录", "studyos-problems"),
    FINANCE("财务流水", "studyos-finance"),
}

data class BackupUiState(
    val loadingSummary: Boolean = true,
    val summary: CurrentDataSummary? = null,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val preview: BackupPreview? = null,
    val lastBackup: BackupSummary? = null,
    val lastRestore: RestoreResult? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
) {
    val hasPendingPreview: Boolean get() = preview != null
}

/**
 * 备份/恢复/导出页面状态。
 *
 * - 恢复必须先 inspect（只读校验）再确认；确认后整体替换业务数据；
 * - 恢复失败由底层事务与文件回滚保证当前数据不变，这里只呈现错误；
 * - 导出为一次性 CSV 快照，不修改任何数据。
 */
class DataBackupViewModel(
    private val backupService: BackupService,
    private val exportService: DataExportService,
) : ViewModel() {

    private val state = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = state.asStateFlow()

    private var pendingRestoreUri: Uri? = null

    init {
        refreshSummary()
    }

    fun refreshSummary() {
        viewModelScope.launch {
            state.update { it.copy(loadingSummary = true) }
            runCatching { backupService.currentDataSummary() }
                .onSuccess { summary ->
                    state.update { it.copy(loadingSummary = false, summary = summary) }
                }
                .onFailure { error ->
                    state.update {
                        it.copy(
                            loadingSummary = false,
                            errorMessage = error.message ?: "无法读取当前数据统计",
                        )
                    }
                }
        }
    }

    fun createBackup(uri: Uri) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update { it.copy(busy = true, busyLabel = "正在创建完整备份…", errorMessage = null, notice = null) }
            runCatching { backupService.createBackup(uri) }
                .onSuccess { summary ->
                    state.update {
                        it.copy(
                            busy = false,
                            busyLabel = "",
                            lastBackup = summary,
                            notice = "备份已保存：${summary.fileName}（${formatSize(summary.sizeBytes)}，" +
                                "${summary.manifest.totalRows} 行、${summary.manifest.documentFileCount} 个原始文件）",
                        )
                    }
                }
                .onFailure { error -> fail("备份失败，未修改任何数据", error) }
        }
    }

    fun inspectBackup(uri: Uri) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update {
                it.copy(
                    busy = true,
                    busyLabel = "正在校验备份文件…",
                    errorMessage = null,
                    notice = null,
                    preview = null,
                )
            }
            runCatching { backupService.inspectBackup(uri) }
                .onSuccess { preview ->
                    pendingRestoreUri = uri
                    state.update { it.copy(busy = false, busyLabel = "", preview = preview) }
                }
                .onFailure { error -> fail("无法恢复：备份文件校验失败", error) }
        }
    }

    fun cancelPreview() {
        pendingRestoreUri = null
        state.update { it.copy(preview = null) }
    }

    fun confirmRestore() {
        val uri = pendingRestoreUri
        if (uri == null) {
            state.update { it.copy(errorMessage = "请先选择一个备份文件") }
            return
        }
        if (state.value.busy) return
        viewModelScope.launch {
            state.update { it.copy(busy = true, busyLabel = "正在恢复数据…", errorMessage = null, notice = null) }
            runCatching { backupService.restoreBackup(uri) }
                .onSuccess { result ->
                    pendingRestoreUri = null
                    state.update {
                        it.copy(
                            busy = false,
                            busyLabel = "",
                            preview = null,
                            lastRestore = result,
                            notice = "恢复完成：${result.restoredCounts.values.sum()} 行、" +
                                "${result.restoredDocumentFiles} 个原始文件；如页面未刷新请重新打开应用",
                        )
                    }
                    refreshSummary()
                }
                .onFailure { error -> fail("恢复失败，已回滚到恢复前的数据", error) }
        }
    }

    fun export(kind: ExportKind, uri: Uri) {
        if (state.value.busy) return
        viewModelScope.launch {
            state.update {
                it.copy(busy = true, busyLabel = "正在导出${kind.label}…", errorMessage = null, notice = null)
            }
            runCatching {
                when (kind) {
                    ExportKind.TASKS -> exportService.exportTasks(uri)
                    ExportKind.TIMINGS -> exportService.exportTimings(uri)
                    ExportKind.PROBLEMS -> exportService.exportManualProblems(uri)
                    ExportKind.FINANCE -> exportService.exportFinance(uri)
                }
            }
                .onSuccess {
                    state.update {
                        it.copy(busy = false, busyLabel = "", notice = "${kind.label}已导出为 CSV")
                    }
                }
                .onFailure { error -> fail("导出${kind.label}失败", error) }
        }
    }

    fun dismissError() = state.update { it.copy(errorMessage = null) }

    fun consumeNotice() = state.update { it.copy(notice = null) }

    private fun fail(prefix: String, error: Throwable) {
        state.update {
            it.copy(
                busy = false,
                busyLabel = "",
                errorMessage = "$prefix：${error.message ?: "未知错误"}",
            )
        }
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
