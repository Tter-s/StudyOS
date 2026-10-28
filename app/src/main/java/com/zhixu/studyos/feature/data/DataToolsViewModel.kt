package com.zhixu.studyos.feature.data

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhixu.studyos.core.backup.BackupPreview
import com.zhixu.studyos.core.ownership.DataOwnershipService
import com.zhixu.studyos.core.ownership.JsonExportSummary
import com.zhixu.studyos.core.ownership.SecureBackupService
import com.zhixu.studyos.core.ownership.WipeResult
import com.zhixu.studyos.core.sync.ConflictResolution
import com.zhixu.studyos.core.sync.LocalSyncService
import com.zhixu.studyos.core.sync.SyncApplyResult
import com.zhixu.studyos.core.sync.SyncExportResult
import com.zhixu.studyos.core.sync.SyncPreview
import com.zhixu.studyos.core.sync.SyncScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DataToolsUiState(
    val isBusy: Boolean = false,
    val securePreview: BackupPreview? = null,
    val jsonExport: JsonExportSummary? = null,
    val syncPreview: SyncPreview? = null,
    val syncResolutions: Map<String, ConflictResolution> = emptyMap(),
    val syncExport: SyncExportResult? = null,
    val syncApply: SyncApplyResult? = null,
    val wipeResult: WipeResult? = null,
    val errorMessage: String? = null,
    val notice: String? = null,
    val cloudConfigured: Boolean = false,
    val cloudName: String = "",
)

class DataToolsViewModel(
    private val secureBackupService: SecureBackupService,
    private val ownershipService: DataOwnershipService,
    private val syncService: LocalSyncService,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        DataToolsUiState(
            cloudConfigured = syncService.cloudConfigured,
            cloudName = syncService.transportName,
        ),
    )
    val uiState: StateFlow<DataToolsUiState> = mutable

    val pendingSyncCount: StateFlow<Int> = syncService.observePendingCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun createEncryptedBackup(uri: Uri, passphrase: String) {
        if (passphrase.length < 8) {
            mutable.value = mutable.value.copy(errorMessage = "加密口令至少需要 8 个字符")
            return
        }
        runBusy {
            val summary = secureBackupService.createEncryptedBackup(
                uri,
                passphrase.toCharArray(),
            )
            mutable.value = mutable.value.copy(
                isBusy = false,
                notice = "加密备份已写入：${summary.fileName}（${summary.sizeBytes / 1024} KB）",
            )
        }
    }

    fun inspectEncryptedBackup(uri: Uri, passphrase: String) {
        if (passphrase.length < 8) {
            mutable.value = mutable.value.copy(errorMessage = "请输入加密口令（至少 8 个字符）")
            return
        }
        runBusy {
            val preview = secureBackupService.inspectEncryptedBackup(uri, passphrase.toCharArray())
            mutable.value = mutable.value.copy(
                isBusy = false,
                securePreview = preview,
                notice = "备份校验通过，请确认后再恢复",
            )
        }
    }

    fun restoreEncryptedBackup(uri: Uri, passphrase: String) {
        if (passphrase.length < 8) {
            mutable.value = mutable.value.copy(errorMessage = "请输入加密口令")
            return
        }
        runBusy {
            val result = secureBackupService.restoreEncryptedBackup(uri, passphrase.toCharArray())
            mutable.value = mutable.value.copy(
                isBusy = false,
                securePreview = null,
                notice = "恢复完成：恢复 ${result.restoredCounts.values.sum()} 行，" +
                    "${result.restoredDocumentFiles} 个附件；原有数据已整体替换",
            )
        }
    }

    fun clearSecurePreview() {
        mutable.value = mutable.value.copy(securePreview = null)
    }

    fun exportJson(uri: Uri) {
        runBusy {
            val summary = ownershipService.exportJson(uri)
            mutable.value = mutable.value.copy(
                isBusy = false,
                jsonExport = summary,
                notice = "JSON 已导出：${summary.tableCount} 张表、${summary.rowCount} 行、" +
                    "${summary.attachmentCount} 个附件清单",
            )
        }
    }

    fun buildSyncPreview(uri: Uri, passphrase: String) {
        if (passphrase.length < 8) {
            mutable.value = mutable.value.copy(errorMessage = "同步包口令至少需要 8 个字符")
            return
        }
        runBusy {
            val preview = syncService.inspectEncryptedBundle(uri, passphrase.toCharArray())
            mutable.value = mutable.value.copy(
                isBusy = false,
                syncPreview = preview,
                syncResolutions = emptyMap(),
                syncApply = null,
                notice = "同步包已解密并完成冲突检测，尚未写入任何数据",
            )
        }
    }

    fun setResolution(key: String, resolution: ConflictResolution) {
        mutable.value = mutable.value.copy(
            syncResolutions = mutable.value.syncResolutions + (key to resolution),
        )
    }

    fun applySyncPreview() {
        val preview = mutable.value.syncPreview ?: return
        runBusy {
            val result = syncService.applyPreview(preview, mutable.value.syncResolutions)
            mutable.value = mutable.value.copy(
                isBusy = false,
                syncPreview = null,
                syncResolutions = emptyMap(),
                syncApply = result,
                notice = "同步完成：新增 ${result.imported}，更新 ${result.updated}，删除 ${result.deleted}，" +
                    "已处理冲突 ${result.conflictsResolved}，剩余 ${result.conflictsRemaining}",
            )
        }
    }

    fun clearSyncPreview() {
        mutable.value = mutable.value.copy(syncPreview = null, syncResolutions = emptyMap())
    }

    fun exportSyncBundle(uri: Uri, passphrase: String, scopes: Set<SyncScope>) {
        if (scopes.isEmpty()) {
            mutable.value = mutable.value.copy(errorMessage = "请至少选择一类同步数据")
            return
        }
        if (passphrase.length < 8) {
            mutable.value = mutable.value.copy(errorMessage = "同步包口令至少需要 8 个字符")
            return
        }
        runBusy {
            val result = syncService.exportEncryptedBundle(uri, passphrase.toCharArray(), scopes)
            mutable.value = mutable.value.copy(
                isBusy = false,
                syncExport = result,
                notice = "加密同步包已导出：${result.objectCount} 个对象，${result.tombstoneCount} 个删除标记",
            )
        }
    }

    fun wipeAllData(deleteApiKey: Boolean) {
        runBusy {
            val result = ownershipService.wipeAllData(deleteApiKey)
            mutable.value = mutable.value.copy(
                isBusy = false,
                wipeResult = result,
                notice = "已删除约 ${result.deletedRows} 行本地数据与 ${result.deletedFiles} 个附件" +
                    if (result.apiKeyCleared) "，并清除了 API Key" else "",
            )
        }
    }

    fun consumeMessage() {
        mutable.value = mutable.value.copy(notice = null, errorMessage = null)
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (mutable.value.isBusy) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isBusy = true, errorMessage = null, notice = null)
            try {
                block()
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(
                    isBusy = false,
                    errorMessage = error.message ?: "操作失败",
                )
            }
        }
    }
}
