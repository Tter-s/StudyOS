package com.zhixu.studyos.feature.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.image.PickedScheduleImage
import com.zhixu.studyos.core.importer.image.ScheduleImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScheduleImportDialog(
    viewModel: ScheduleImportViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<PickedScheduleImage?>(null) }
    var userRotation by remember { mutableIntStateOf(0) }
    var crop by remember { mutableStateOf(NormalizedCrop.Full) }
    var isImageLoading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var showSemesterSettings by remember { mutableStateOf(false) }
    var showGridDialog by remember { mutableStateOf(false) }
    var editingDraftId by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImageLoading = true
        localError = null
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { ScheduleImageLoader.load(context.contentResolver, uri) }
            }
            if (!isActive) return@launch
            isImageLoading = false
            loaded.onSuccess { image ->
                picked = image
                userRotation = 0
                crop = NormalizedCrop.Full
                viewModel.startNewImage()
            }.onFailure { error ->
                localError = error.message?.takeIf(String::isNotBlank) ?: "无法读取图片，请重新选择。"
            }
        }
    }

    fun openPicker() {
        if (isImageLoading) return
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun close() {
        viewModel.reset()
        onDismiss()
    }

    fun requestClose() {
        if (state.isRecognizing || state.isImporting || isImageLoading) return
        if (state.drafts.isNotEmpty()) {
            showDiscard = true
        } else {
            close()
        }
    }

    Dialog(
        onDismissRequest = ::requestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("import_dialog"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                ImportHeader(
                    stage = state.stage,
                    busy = state.isRecognizing || state.isImporting || isImageLoading,
                    canGoBack = state.stage == ScheduleImportStage.PREVIEW,
                    onBack = viewModel::backToAdjust,
                    onClose = ::requestClose,
                )
                when (state.stage) {
                    ScheduleImportStage.PICK_IMAGE -> ScheduleImportStartContent(
                        isSnapshotAvailable = state.isSnapshotAvailable,
                        snapshotError = state.snapshotError,
                        isImageLoading = isImageLoading,
                        onPickImage = ::openPicker,
                        onRetrySnapshot = viewModel::retrySnapshot,
                        modifier = Modifier.weight(1f),
                    )

                    ScheduleImportStage.ADJUST -> {
                        val image = picked
                        if (image == null) {
                            ScheduleImportStartContent(
                                isSnapshotAvailable = state.isSnapshotAvailable,
                                snapshotError = state.snapshotError,
                                isImageLoading = isImageLoading,
                                onPickImage = ::openPicker,
                                onRetrySnapshot = viewModel::retrySnapshot,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            val rotated = remember(image.previewBitmap, userRotation) {
                                if (userRotation == 0) {
                                    image.previewBitmap
                                } else {
                                    ScheduleImageLoader.rotate(image.previewBitmap, userRotation)
                                }
                            }
                            ScheduleImportAdjustContent(
                                bitmap = rotated,
                                crop = crop,
                                hasSemester = state.hasSemester || !state.isSnapshotAvailable,
                                warnings = state.warnings,
                                onCropChange = { crop = it },
                                onRotateLeft = {
                                    userRotation = Math.floorMod(userRotation - 1, 4)
                                    crop = NormalizedCrop.Full
                                },
                                onRotateRight = {
                                    userRotation = Math.floorMod(userRotation + 1, 4)
                                    crop = NormalizedCrop.Full
                                },
                                onResetCrop = { crop = NormalizedCrop.Full },
                                onPickAnother = ::openPicker,
                                onRecognize = {
                                    if (!isImageLoading) {
                                        val totalTurns = Math.floorMod(image.exifQuarterTurns + userRotation, 4)
                                        viewModel.recognize(
                                            imageBytes = image.bytes,
                                            mimeType = image.mimeType,
                                            rotationQuarterTurns = totalTurns,
                                            crop = crop,
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    ScheduleImportStage.RECOGNIZING -> ScheduleImportRecognizingContent(
                        modifier = Modifier.weight(1f),
                    )

                    ScheduleImportStage.PREVIEW -> ScheduleImportPreviewContent(
                        state = state,
                        onEditDraft = { editingDraftId = it },
                        onToggleDraft = viewModel::toggleSelected,
                        onOpenGrid = { showGridDialog = true },
                        onOpenSemester = { showSemesterSettings = true },
                        onRetrySnapshot = viewModel::retrySnapshot,
                        onImport = viewModel::importSelected,
                        onRequestClose = ::requestClose,
                        modifier = Modifier.weight(1f),
                    )

                    ScheduleImportStage.IMPORTED -> ScheduleImportImportedContent(
                        count = state.importedCount,
                        onDone = ::close,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    val editingDraft = editingDraftId?.let { id ->
        state.drafts.firstOrNull { it.localId == id }
    }
    if (editingDraft != null) {
        ImportDraftEditorDialog(
            draft = editingDraft,
            grid = state.grid,
            analysis = state.analysis[editingDraft.localId],
            onDismiss = { editingDraftId = null },
            onSave = { updated ->
                viewModel.updateDraft(updated)
                editingDraftId = null
            },
        )
    }

    if (showGridDialog) {
        ImportPeriodGridDialog(
            grid = state.grid,
            onDismiss = { showGridDialog = false },
            onSave = { grid ->
                viewModel.updateGrid(grid)
                showGridDialog = false
            },
        )
    }

    if (showSemesterSettings) {
        SemesterSettingsDialog(
            initial = null,
            defaultStartDate = viewModel.currentDate(),
            defaultZoneId = viewModel.fallbackZoneId,
            onDismiss = { showSemesterSettings = false },
            onSave = viewModel::saveSemester,
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("放弃本次导入？") },
            text = { Text("识别结果和修改不会保存，已有课程不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscard = false
                        close()
                    },
                ) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    val errorMessage = localError ?: state.errorMessage
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                localError = null
                viewModel.consumeError()
            },
            title = { Text("操作未完成") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        localError = null
                        viewModel.consumeError()
                    },
                ) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun ImportHeader(
    stage: ScheduleImportStage,
    busy: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val subtitle = when (stage) {
        ScheduleImportStage.PICK_IMAGE -> "选择截图并裁剪，识别后先预览"
        ScheduleImportStage.ADJUST -> "保留完整的星期表头和节次列"
        ScheduleImportStage.RECOGNIZING -> "本地 OCR 正在工作，图片不会上传"
        ScheduleImportStage.PREVIEW -> "核对识别结果，确认后才写入课表"
        ScheduleImportStage.IMPORTED -> "导入完成"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回调整")
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("课表图片导入", style = MaterialTheme.typography.titleLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose, enabled = !busy) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭导入")
        }
    }
}
