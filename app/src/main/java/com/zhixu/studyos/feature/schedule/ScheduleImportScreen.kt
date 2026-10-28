package com.zhixu.studyos.feature.schedule

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.PeriodGrid
import com.zhixu.studyos.core.importer.ScheduleImportDraft
import com.zhixu.studyos.core.importer.ScheduleImportDraftState
import com.zhixu.studyos.core.importer.ScheduleImportFormat
import com.zhixu.studyos.core.importer.ScheduleImportValidation

@Composable
fun ScheduleImportStartContent(
    isSnapshotAvailable: Boolean,
    snapshotError: String?,
    isImageLoading: Boolean,
    onPickImage: () -> Unit,
    onRetrySnapshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = "从课表截图导入",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "选择教务系统或课程表应用的截图，在设备上识别课程、星期、节次、教室和教学周。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!isSnapshotAvailable) {
            ImportBanner(
                title = "无法读取现有课表",
                message = snapshotError ?: "导入已暂停，避免在未知课表上写入冲突课程。",
                actionLabel = "重试",
                onAction = onRetrySnapshot,
                isError = true,
            )
        }

        PrimaryButton(
            text = "选择课表图片",
            onClick = onPickImage,
            enabled = !isImageLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("import_pick_image"),
        )
        if (isImageLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在读取图片…", style = MaterialTheme.typography.bodySmall)
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text("本地识别，不上传图片", style = MaterialTheme.typography.titleSmall)
            TipRow("识别在本机完成，应用未声明 INTERNET 权限。")
            TipRow("保留星期表头和左侧节次列，裁剪掉无关页面。")
            TipRow("识别结果只是候选，逐字段核对后才写入课表。")
            TipRow("支持 JPG、PNG、WebP，单张不超过 25 MB。")
            Spacer(modifier = Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "云端视觉识别本版本未启用；识别接口已隔离，后续版本可由你主动选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }


        Text(
            text = "取消或识别失败都不会修改已有课程。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ScheduleImportAdjustContent(
    bitmap: Bitmap,
    crop: NormalizedCrop,
    hasSemester: Boolean,
    warnings: List<String>,
    onCropChange: (NormalizedCrop) -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onResetCrop: () -> Unit,
    onPickAnother: () -> Unit,
    onRecognize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRotateLeft) {
                Icon(Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = "向左旋转 90 度")
            }
            IconButton(onClick = onRotateRight) {
                Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = "向右旋转 90 度")
            }
            TextButton(onClick = onResetCrop) { Text("重置裁剪") }
            TextButton(onClick = onPickAnother) { Text("重新选择") }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(4.dp),
        ) {
            ScheduleImageCropBox(
                bitmap = bitmap,
                crop = crop,
                onCropChange = onCropChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = "拖动四角调整裁剪范围，并保留完整的星期表头和节次列。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasSemester) {
            ImportBanner(
                title = "尚未设置学期",
                message = "可以继续识别和预览，但写入前需要先设置学期开始日期。",
            )
        }
        if (warnings.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    warnings.take(3).forEach { warning ->
                        Text(
                            text = "· $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton(
                text = "重新选择",
                onClick = onPickAnother,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "开始识别",
                onClick = onRecognize,
                modifier = Modifier
                    .weight(1f)
                    .testTag("import_recognize"),
            )
        }
    }
}

@Composable
fun ScheduleImportRecognizingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.size(20.dp))
        Text("正在本地识别课程信息…", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "通常需要几秒。图片不会上传，识别失败也不会修改已有课表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ScheduleImportPreviewContent(
    state: ScheduleImportUiState,
    onEditDraft: (String) -> Unit,
    onToggleDraft: (String, Boolean) -> Unit,
    onOpenGrid: () -> Unit,
    onOpenSemester: () -> Unit,
    onRetrySnapshot: () -> Unit,
    onImport: () -> Unit,
    onRequestClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val readyCount = state.analysis.values.count { it.isSelectable }
    val blockedCount = state.analysis.values.count {
        it.duplicates.isNotEmpty() || it.conflicts.isNotEmpty()
    }
    val pendingCount = state.analysis.values.count {
        (it.state == ScheduleImportDraftState.NEEDS_REVIEW ||
            it.state == ScheduleImportDraftState.INCOMPLETE) &&
            it.duplicates.isEmpty() &&
            it.conflicts.isEmpty()
    }
    val selectedCount = state.selectedDrafts.size
    val parserWarnings = state.warnings.filterNot { it.contains("默认节次") }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SummaryMetric(
                    value = state.recognizedCount.toString(),
                    label = "识别课程",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    value = readyCount.toString(),
                    label = "可导入",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    value = pendingCount.toString(),
                    label = "待确认",
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                SummaryMetric(
                    value = blockedCount.toString(),
                    label = "重复/冲突",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }

            state.methodLabel?.let { method ->
                val seconds = state.elapsedMillis?.let { millis ->
                    if (millis < 1000) "不到 1 秒" else "${millis / 1000} 秒"
                } ?: "—"
                Text(
                    text = "$method · 识别用时 $seconds",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.isSnapshotAvailable) {
                ImportBanner(
                    title = "无法读取现有课表",
                    message = state.snapshotError ?: "重复和冲突检测不可靠，导入已暂停。",
                    actionLabel = "重试",
                    onAction = onRetrySnapshot,
                    isError = true,
                )
            }
            if (state.needsSemester) {
                ImportBanner(
                    title = "尚未设置学期",
                    message = "课表的日期和教学周依赖学期开始日期；设置后才能写入。",
                    actionLabel = "设置学期",
                    onAction = onOpenSemester,
                )
            }
            if (state.needsGridConfirmation) {
                ImportBanner(
                    title = "需要核对节次时间",
                    message = "图片中没有识别到部分节次的钟点时间，当前使用默认时间表。请核对后再导入。",
                    actionLabel = "核对节次时间",
                    onAction = onOpenGrid,
                )
            } else if (state.fallbackPeriods.isNotEmpty()) {
                Text(
                    text = "节次时间已确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (parserWarnings.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("识别提示", style = MaterialTheme.typography.labelLarge)
                        }
                        parserWarnings.forEach { warning ->
                            Text(
                                text = "· $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "点击任意行可修改。待确认、重复和冲突行默认不导入；勾选后可导入行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenGrid) { Text("节次时间") }
            }
            HorizontalDivider()
        }

        ImportPreviewTable(
            drafts = state.drafts,
            analysis = state.analysis,
            grid = state.grid,
            selectedIds = state.selectedIds,
            onEditDraft = onEditDraft,
            onToggleDraft = onToggleDraft,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("import_preview_table"),
        )

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("已选择 $selectedCount 门", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (state.isImporting) "正在写入课表…" else "确认后才会写入数据库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRequestClose, enabled = !state.isImporting) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                PrimaryButton(
                    text = if (state.isImporting) "正在写入…" else "导入 $selectedCount 门",
                    onClick = onImport,
                    enabled = state.canImport,
                    modifier = Modifier.testTag("import_submit"),
                )
            }
        }
    }
}

@Composable
fun ScheduleImportImportedContent(
    count: Int,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text("已导入 $count 门课程", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "原有课程没有被修改。识别结果已写入课表，可以继续编辑、调课或停课。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(24.dp))
        PrimaryButton(
            text = "完成",
            onClick = onDone,
            modifier = Modifier.testTag("import_done"),
        )
    }
}

@Composable
fun ImportBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = contentColor)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f),
                )
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TipRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val importTableWidth = 820.dp
private val checkboxColumnWidth = 52.dp
private val nameColumnWidth = 168.dp
private val dayColumnWidth = 64.dp
private val periodColumnWidth = 120.dp
private val weeksColumnWidth = 170.dp
private val roomColumnWidth = 130.dp
private val statusColumnWidth = 116.dp

@Composable
private fun ImportPreviewTable(
    drafts: List<ScheduleImportDraft>,
    analysis: Map<String, com.zhixu.studyos.core.importer.ScheduleImportDraftAnalysis>,
    grid: PeriodGrid,
    selectedIds: Set<String>,
    onEditDraft: (String) -> Unit,
    onToggleDraft: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    Box(
        modifier = modifier
            .horizontalScroll(horizontal)
            .verticalScroll(vertical),
    ) {
        Column(modifier = Modifier.width(importTableWidth)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TableHeaderCell("导入", checkboxColumnWidth)
                TableHeaderCell("课程", nameColumnWidth)
                TableHeaderCell("星期", dayColumnWidth)
                TableHeaderCell("节次", periodColumnWidth)
                TableHeaderCell("教学周", weeksColumnWidth)
                TableHeaderCell("教室", roomColumnWidth)
                TableHeaderCell("状态", statusColumnWidth)
            }
            HorizontalDivider()
            drafts.forEachIndexed { index, draft ->
                val rowAnalysis = analysis[draft.localId]
                val selectable = rowAnalysis?.isSelectable == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditDraft(draft.localId) }
                        .padding(vertical = 8.dp)
                        .testTag("import_row_$index"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.width(checkboxColumnWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        Checkbox(
                            checked = draft.localId in selectedIds,
                            onCheckedChange = { checked -> onToggleDraft(draft.localId, checked) },
                            enabled = selectable,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .width(nameColumnWidth)
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(draft.colorArgb), CircleShape),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = draft.name.ifBlank { "待确认" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (draft.name.isBlank()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    TableTextCell(
                        text = draft.dayOfWeek?.let { weekDayLabels.getOrNull(it - 1) } ?: "待确认",
                        width = dayColumnWidth,
                        isPending = draft.dayOfWeek == null,
                    )
                    Column(modifier = Modifier.width(periodColumnWidth).padding(end = 8.dp)) {
                        Text(
                            text = ScheduleImportFormat.periodRange(draft.periodStart, draft.periodEnd),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (draft.periodStart == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        val time = ScheduleImportValidation.timeRange(draft, grid)
                        if (time != null) {
                            Text(
                                text = "${ScheduleImportFormat.clock(time.first)}–${ScheduleImportFormat.clock(time.second)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TableTextCell(
                        text = ScheduleImportFormat.courseWeeks(draft.startWeek, draft.endWeek, draft.weekParity),
                        width = weeksColumnWidth,
                        isPending = draft.startWeek == null || draft.endWeek == null,
                    )
                    TableTextCell(
                        text = draft.classroom.ifBlank { "待确认" },
                        width = roomColumnWidth,
                        isPending = draft.classroom.isBlank(),
                    )
                    Box(modifier = Modifier.width(statusColumnWidth)) {
                        ImportStatusBadge(
                            state = rowAnalysis?.state,
                            needsReview = draft.needsReview.isNotEmpty(),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, width: Dp) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(end = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TableTextCell(text: String, width: Dp, isPending: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(end = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (isPending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ImportStatusBadge(
    state: ScheduleImportDraftState?,
    needsReview: Boolean,
) {
    val effective = state ?: if (needsReview) {
        ScheduleImportDraftState.NEEDS_REVIEW
    } else {
        ScheduleImportDraftState.READY
    }
    val (label, foreground, background) = when (effective) {
        ScheduleImportDraftState.READY -> Triple(
            "可导入",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
        )
        ScheduleImportDraftState.NEEDS_REVIEW -> Triple(
            "待确认",
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiaryContainer,
        )
        ScheduleImportDraftState.INCOMPLETE -> Triple(
            "信息不完整",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer,
        )
        ScheduleImportDraftState.DUPLICATE -> Triple(
            "重复",
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.secondaryContainer,
        )
        ScheduleImportDraftState.CONFLICT -> Triple(
            "冲突",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer,
        )
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = background,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            maxLines = 1,
        )
    }
}
