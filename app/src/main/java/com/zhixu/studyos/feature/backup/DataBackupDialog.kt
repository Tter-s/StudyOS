package com.zhixu.studyos.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.backup.BackupPreview
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「我的 → 数据备份与导出」全屏对话框。
 *
 * 恢复是破坏性操作：必须先通过只读校验预览，再二次确认；
 * 失败时底层保证回滚，这里只展示错误。
 */
@Composable
fun DataBackupDialog(
    viewModel: DataBackupViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf(false) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) viewModel.createBackup(uri) }
    val inspectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.inspectBackup(uri) }

    val exportLaunchers = ExportKind.entries.associateWith { kind ->
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri -> if (uri != null) viewModel.export(kind, uri) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                    Text(
                        text = "数据备份与导出",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .testTag("backup_screen"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "完整备份包含全部本地记录与旧版本归档数据，用于换机或重装后恢复。" +
                            "设置与密钥不在备份中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CurrentDataCard(state)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimaryButton(
                            text = "创建完整备份",
                            onClick = {
                                createBackupLauncher.launch(
                                    "studyos-backup-${java.time.LocalDate.now()}.zip",
                                )
                            },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("backup_create"),
                        )
                        SecondaryButton(
                            text = "从备份恢复",
                            onClick = { inspectLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = !state.busy && !state.hasPendingPreview,
                            modifier = Modifier.testTag("backup_restore"),
                        )
                    }
                    if (state.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.busyLabel.ifBlank { "处理中…" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    state.errorMessage?.let { message ->
                        Banner(
                            message = message,
                            isError = true,
                            onDismiss = viewModel::dismissError,
                        )
                    }
                    state.notice?.let { message ->
                        Banner(
                            message = message,
                            isError = false,
                            onDismiss = viewModel::consumeNotice,
                        )
                    }
                    state.preview?.let { preview ->
                        PreviewCard(
                            preview = preview,
                            onConfirm = { confirmRestore = true },
                            onCancel = viewModel::cancelPreview,
                        )
                    }
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "导出为 CSV", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "导出的 CSV 带 UTF-8 BOM，可直接用 Excel 打开；不是恢复格式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ExportKind.entries.forEach { kind ->
                                SecondaryButton(
                                    text = kind.label,
                                    onClick = {
                                        exportLaunchers.getValue(kind).launch("${kind.fileName}.csv")
                                    },
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (confirmRestore && state.preview != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("确认恢复？") },
            text = {
                Text(
                    "恢复将用备份内容替换当前全部业务数据，并替换题库原始文件。" +
                        "此操作不可撤销，建议先创建一份当前备份。",
                )
            },
            confirmButton = {
                PrimaryButton(
                    text = "确认恢复",
                    onClick = {
                        confirmRestore = false
                        viewModel.confirmRestore()
                    },
                    modifier = Modifier.testTag("backup_confirm_restore"),
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CurrentDataCard(state: BackupUiState) {
    val summary = state.summary
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "当前数据", style = MaterialTheme.typography.titleSmall)
        if (state.loadingSummary) {
            Text("正在统计…", style = MaterialTheme.typography.bodySmall)
            return@AppCard
        }
        if (summary == null) {
            Text("无法读取当前数据统计", style = MaterialTheme.typography.bodySmall)
            return@AppCard
        }
        val counts = summary.tableCounts
        Text(
            text = "共 ${summary.totalRows} 行：任务 ${counts["study_tasks"] ?: 0}、" +
                "课程 ${counts["courses"] ?: 0}、计时 ${counts["study_timing_sessions"] ?: 0}、" +
                "习题 ${counts["problems"] ?: 0}、财务流水 ${counts["finance_transactions"] ?: 0}、" +
                "题库题目 ${counts["problem_bank_entries"] ?: 0}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "题库原始文件：${summary.documentFileCount} 个，" +
                DataBackupViewModel.formatSize(summary.documentTotalBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewCard(
    preview: BackupPreview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val manifest = preview.manifest
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "恢复前预览（${preview.sourceName}）",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "创建时间：${
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(manifest.createdAt.atZone(ZoneId.systemDefault()))
            } · 应用版本 ${manifest.appVersion} · 数据库 v${manifest.databaseVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "包含 ${manifest.totalRows} 行数据、" +
                "${manifest.documentFileCount} 个原始文件（" +
                DataBackupViewModel.formatSize(manifest.documentTotalBytes) + "）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preview.warnings.forEach { warning ->
            Row {
                Text(
                    text = "• ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = "确认恢复（替换当前数据）",
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .testTag("backup_preview_confirm"),
            )
            SecondaryButton(text = "取消", onClick = onCancel)
        }
    }
}

@Composable
private fun Banner(message: String, isError: Boolean, onDismiss: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
