package com.zhixu.studyos.feature.data

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.sync.ConflictResolution
import com.zhixu.studyos.core.sync.SyncScope

@Composable
fun DataToolsDialog(viewModel: DataToolsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pending by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var passphrase by remember { mutableStateOf("") }
    var jsonPassphraseNote by remember { mutableStateOf(false) }
    var secureRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var deleteApiKey by remember { mutableStateOf(false) }
    var selectedScopes by remember { mutableStateOf(SyncScope.entries.toSet()) }

    val encryptedCreate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { viewModel.createEncryptedBackup(it, passphrase) } }
    val encryptedOpen = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            secureRestoreUri = it
            viewModel.inspectEncryptedBackup(it, passphrase)
        }
    }
    val jsonCreate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJson) }
    val syncCreate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { viewModel.exportSyncBundle(it, passphrase, selectedScopes) } }
    val syncOpen = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.buildSyncPreview(it, passphrase) } }

    LaunchedEffect(state.notice, state.errorMessage) {
        (state.notice ?: state.errorMessage)?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("screen_data_tools"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Rounded.Close, contentDescription = "关闭")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("数据与同步", style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "加密备份、完整导出、本地同步与彻底删除。普通备份与 CSV 在“备份与导出”中。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.isBusy) {
                                Text("处理中…", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("加密口令", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "加密备份和同步包都使用该口令；口令不会被保存，丢失后无法恢复。" +
                                            "至少 8 个字符，请使用只有你知道的长口令。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = { passphrase = it },
                                label = { Text("备份/同步口令") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item { SectionHeader(title = "加密备份（含题库原始文件）") }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "AES-256-GCM + PBKDF2；备份包含全部业务表、附件和 AI 分析记录，" +
                                    "不包含 API Key 与 AI 使用量/缓存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(
                                    text = "创建加密备份",
                                    onClick = {
                                        encryptedCreate.launch("StudyOS-encrypted-backup.zip")
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryButton(
                                    text = "预览/恢复",
                                    onClick = { encryptedOpen.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            state.securePreview?.let { preview ->
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("备份校验通过", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "来源：${preview.sourceName} · 创建于 ${preview.manifest.createdAt} · " +
                                        "数据库 v${preview.manifest.databaseVersion} · " +
                                        "应用 ${preview.manifest.appVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "总行数 ${preview.manifest.totalRows} · 文档 ${preview.manifest.documentCount} · " +
                                        "附件 ${preview.manifest.documentFileCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                preview.warnings.forEach {
                                    Text(
                                        "· $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                SecondaryButton(
                                    text = "确认恢复（整体替换当前数据）",
                                    onClick = {
                                        secureRestoreUri?.let {
                                            viewModel.restoreEncryptedBackup(it, passphrase)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    item { SectionHeader(title = "完整 JSON 导出") }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "版本化 JSON，包含全部业务表、附件清单与偏好（不含 API Key）。" +
                                    "适合长期归档与人工检查；完整恢复请使用加密/普通 ZIP 备份。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SecondaryButton(
                                text = "导出完整 JSON",
                                onClick = { jsonCreate.launch("StudyOS-data-export.json") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            state.jsonExport?.let {
                                Text(
                                    "已导出 ${it.tableCount} 张表、${it.rowCount} 行，大小 ${it.sizeBytes / 1024} KB。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item { SectionHeader(title = "多设备本地同步（加密同步包）") }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CloudOff, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        if (state.cloudConfigured) "云端同步已配置" else "云端同步未配置",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "应用不会伪造云端同步。当前可用的是手动加密同步包：在设备间通过文件交换，支持增量比较、冲突检测与删除墓碑。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                "待同步的本地变更：$pending 条",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("同步范围", style = MaterialTheme.typography.labelLarge)
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                SyncScope.entries.forEach { scope ->
                                    FilterChip(
                                        selected = scope in selectedScopes,
                                        onClick = {
                                            selectedScopes = if (scope in selectedScopes) {
                                                selectedScopes - scope
                                            } else {
                                                selectedScopes + scope
                                            }
                                        },
                                        label = { Text(scope.label) },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(
                                    text = "导出同步包",
                                    onClick = { syncCreate.launch("StudyOS-sync-package.dat") },
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryButton(
                                    text = "导入并预览",
                                    onClick = { syncOpen.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (state.syncPreview != null) {
                                SyncPreviewCard(
                                    state = state,
                                    onResolve = viewModel::setResolution,
                                    onApply = viewModel::applySyncPreview,
                                    onCancel = viewModel::clearSyncPreview,
                                )
                            }
                            state.syncExport?.let {
                                Text(
                                    "已导出 ${it.objectCount} 个对象（${it.scopeLabels.joinToString("、")}），" +
                                        "${it.tombstoneCount} 个删除标记。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            state.syncApply?.let {
                                Text(
                                    "上次同步：新增 ${it.imported}，更新 ${it.updated}，删除 ${it.deleted}，" +
                                        "已处理冲突 ${it.conflictsResolved}。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item { SectionHeader(title = "彻底删除个人数据") }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "删除全部业务数据、附件、学习历史、睡眠、词汇、知识图谱、考试目标、AI 分析记录与同步状态。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("同时删除 API Key 与 AI 设置", modifier = Modifier.weight(1f))
                                Switch(checked = deleteApiKey, onCheckedChange = { deleteApiKey = it })
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            SecondaryButton(
                                text = "删除全部个人数据",
                                onClick = { showWipeConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            state.wipeResult?.let {
                                Text(
                                    "已删除 ${it.deletedRows} 行、${it.deletedFiles} 个附件（${it.deletedBytes / 1024} KB）。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Text("数据隐私说明", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "· 学习、财务、睡眠、词汇与题库默认只在本地计算和保存。\n" +
                                    "· 只有你主动请求 AI 功能时才访问你配置的服务地址；财务流水与睡眠原始记录默认不发送。\n" +
                                    "· API Key 由 Android Keystore 加密保存，不进入备份、导出或日志。\n" +
                                    "· 备份口令、加密备份与同步包需要你自行妥善保管；口令丢失后无法恢复。\n" +
                                    "· 统计相关性不代表因果关系；AI 输出不能替代你的判断。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("确认彻底删除？") },
            text = {
                Text(
                    "此操作不可撤销。建议先创建加密备份。删除后应用会保留预设科目与财务分类，" +
                        "但所有学习、财务、睡眠、词汇、题库记录都会消失。" +
                        if (deleteApiKey) " API Key 也会被删除。" else "",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirm = false
                        viewModel.wipeAllData(deleteApiKey)
                    },
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SyncPreviewCard(
    state: DataToolsUiState,
    onResolve: (String, ConflictResolution) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val preview = state.syncPreview ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("同步预览", style = MaterialTheme.typography.titleSmall)
        Text(
            "新增 ${preview.newObjects.size} · 更新 ${preview.updatedObjects.size} · " +
                "将删除 ${preview.remoteDeletions.size} · 冲突 ${preview.conflicts.size}",
            style = MaterialTheme.typography.bodySmall,
        )
        preview.warnings.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        preview.conflicts.forEach { conflict ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    "${conflict.objectType}:${conflict.objectId} · ${conflict.local?.title ?: conflict.remote?.title ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    conflict.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.syncResolutions["${conflict.objectType}:${conflict.objectId}"] ==
                            ConflictResolution.KEEP_LOCAL,
                        onClick = {
                            onResolve("${conflict.objectType}:${conflict.objectId}", ConflictResolution.KEEP_LOCAL)
                        },
                        label = { Text("保留本地") },
                    )
                    FilterChip(
                        selected = state.syncResolutions["${conflict.objectType}:${conflict.objectId}"] ==
                            ConflictResolution.USE_REMOTE,
                        onClick = {
                            onResolve("${conflict.objectType}:${conflict.objectId}", ConflictResolution.USE_REMOTE)
                        },
                        label = { Text("使用同步包") },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
            SecondaryButton(
                text = "应用同步",
                onClick = onApply,
                enabled = !state.isBusy,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "未选择处理的冲突会保留本地，等待下次同步。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
