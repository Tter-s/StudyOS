package com.zhixu.studyos.feature.knowledge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.knowledge.KnowledgeNode
import com.zhixu.studyos.core.knowledge.KnowledgeNodeDetail

@Composable
fun KnowledgeScreen(viewModel: KnowledgeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    val snackbar = remember { SnackbarHostState() }
    var addNode by remember { mutableStateOf(false) }
    var editingNode by remember { mutableStateOf<KnowledgeNode?>(null) }
    var addingPrerequisite by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<KnowledgeNode?>(null) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .testTag("screen_knowledge"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("数学知识图谱", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "知识点、前置依赖与习题关联；图谱可编辑，不是唯一正确的数学体系。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { addNode = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加知识点")
                }
            }
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${state.nodeCount} 个知识点 · ${state.dependencyCount} 条依赖",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "掌握程度来自你的手动标记与真实习题尝试；多次失败才会建议回顾前置知识。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryButton(
                    text = if (state.isAiLoading) "正在请求 AI 建议…" else "AI 关联建议（需确认）",
                    onClick = viewModel::requestAiSuggestions,
                    enabled = !state.isAiLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.subject.isBlank(),
                    onClick = { viewModel.setSubject("") },
                    label = { Text("全部") },
                )
                Spacer(modifier = Modifier.width(6.dp))
                state.subjects.forEach { subject ->
                    FilterChip(
                        selected = state.subject == subject,
                        onClick = { viewModel.setSubject(subject) },
                        label = { Text(subject) },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                state.errorMessage != null -> AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "知识图谱无法加载",
                        message = state.errorMessage.orEmpty(),
                        icon = Icons.Rounded.ErrorOutline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.layout.nodes.isEmpty() -> AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "还没有知识点",
                        message = "添加知识点后可以建立前置关系。",
                        icon = Icons.Rounded.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    KnowledgeGraphView(
                        state = state,
                        onOpenNode = viewModel::openNode,
                    )
                    Text(
                        "点击节点查看详情。节点颜色表示掌握程度；箭头方向为前置 → 后续。",
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

    state.detail?.let { detail ->
        KnowledgeDetailDialog(
            detail = detail,
            onDismiss = viewModel::closeNode,
            onSaveNote = { note, mastery, callback ->
                viewModel.saveNote(detail.node.id, note, mastery, callback)
            },
            onEditNode = { editingNode = detail.node },
            onDeleteNode = { deleteConfirm = detail.node },
            onAddPrerequisite = { addingPrerequisite = true },
            onDeleteDependency = viewModel::deleteDependency,
        )
    }
    if (addingPrerequisite) {
        val current = state.detail?.node
        if (current != null) {
            NodePickerDialog(
                title = "选择「${current.name}」的前置知识点",
                nodes = state.graph.nodes.filter { it.id != current.id },
                onDismiss = { addingPrerequisite = false },
                onSelected = { prerequisite ->
                    addingPrerequisite = false
                    viewModel.addDependency(prerequisite.id, current.id)
                },
            )
        }
    }
    if (addNode || editingNode != null) {
        KnowledgeNodeEditorDialog(
            initial = editingNode,
            subjects = state.subjects.ifEmpty {
                listOf("数学分析", "高等代数", "空间解析几何")
            },
            onDismiss = {
                addNode = false
                editingNode = null
            },
            onSave = { node, callback ->
                viewModel.saveNode(node) { error ->
                    callback(error)
                    if (error == null) {
                        addNode = false
                        editingNode = null
                    }
                }
            },
        )
    }
    deleteConfirm?.let { node ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("删除知识点") },
            text = {
                Text(
                    "确定删除「${node.name}」？依赖关系和题目关联会一并删除，任务历史不受影响。" +
                        "此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNode(node.id)
                        deleteConfirm = null
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("取消") } },
        )
    }
    if (state.aiDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAiDialog,
            title = { Text("AI 关联建议（未自动应用）") },
            text = {
                Column(
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (state.aiSuggestions.isEmpty()) {
                        Text(
                            "没有通过结构校验的可用建议。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.aiSuggestions.forEach { suggestion ->
                        val from = state.graph.nodeById(suggestion.fromNodeId)?.name ?: "?"
                        val to = state.graph.nodeById(suggestion.toNodeId)?.name ?: "?"
                        Text("$from → $to", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            suggestion.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.aiRejected.isNotEmpty()) {
                        HorizontalDivider()
                        Text("未通过校验：", style = MaterialTheme.typography.labelLarge)
                        state.aiRejected.forEach { rejected ->
                            Text(
                                rejected,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::applyAiSuggestions,
                    enabled = state.aiSuggestions.isNotEmpty(),
                ) { Text("确认添加 ${state.aiSuggestions.size} 条") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAiDialog) { Text("取消") }
            },
        )
    }
}

@Composable
private fun KnowledgeGraphView(
    state: KnowledgeUiState,
    onOpenNode: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val nodeWidth = with(density) { 118.dp.toPx() }
    val nodeHeight = with(density) { 46.dp.toPx() }
    val gapX = with(density) { 44.dp.toPx() }
    val gapY = with(density) { 20.dp.toPx() }
    val positions = remember(state.layout, nodeWidth, nodeHeight, gapX, gapY) {
        state.layout.nodes.associate { item ->
            item.node.id to Offset(
                x = item.level * (nodeWidth + gapX),
                y = item.order * (nodeHeight + gapY),
            )
        }
    }
    val maxRows = state.layout.nodes.groupBy { it.level }.values.maxOfOrNull { it.size } ?: 1
    val widthPx = (state.layout.levels.coerceAtLeast(1) * (nodeWidth + gapX) - gapX)
        .coerceAtLeast(nodeWidth)
    val heightPx = (maxRows * (nodeHeight + gapY) - gapY).coerceAtLeast(nodeHeight)
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    val warning = MaterialTheme.colorScheme.primary
    val mastered = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .horizontalScroll(rememberScrollState())
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Canvas(
            modifier = Modifier
                .width(with(density) { widthPx.toDp() })
                .height(with(density) { heightPx.toDp() })
                .pointerInput(positions, nodeWidth, nodeHeight) {
                    detectTapGestures { offset ->
                        positions.entries.firstOrNull { (_, point) ->
                            offset.x in point.x..(point.x + nodeWidth) &&
                                offset.y in point.y..(point.y + nodeHeight)
                        }?.let { (nodeId, _) -> onOpenNode(nodeId) }
                    }
                },
        ) {
            val centerY = nodeHeight / 2f
            state.layout.nodes.forEach { item ->
                val from = positions[item.node.id] ?: return@forEach
                state.layout.nodes.forEach { other ->
                    val to = positions[other.node.id] ?: return@forEach
                    val connected = state.graph.dependencies.any {
                        it.fromNodeId == item.node.id && it.toNodeId == other.node.id
                    }
                    if (connected) {
                        drawLine(
                            color = outline,
                            start = Offset(from.x + nodeWidth, from.y + centerY),
                            end = Offset(to.x, to.y + centerY),
                            strokeWidth = 1.5f,
                        )
                    }
                }
            }
            state.layout.nodes.forEach { item ->
                val point = positions[item.node.id] ?: return@forEach
                val node = item.node
                val fill = when {
                    node.mastery >= 4 -> mastered.copy(alpha = 0.22f)
                    node.mastery == 3 -> warning.copy(alpha = 0.20f)
                    node.mastery in 1..2 -> error.copy(alpha = 0.16f)
                    else -> surface
                }
                drawRoundRect(
                    color = fill,
                    topLeft = point,
                    size = Size(nodeWidth, nodeHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                )
                drawRoundRect(
                    color = outline,
                    topLeft = point,
                    size = Size(nodeWidth, nodeHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f),
                )
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 12f * density.density
                        color = onSurface.toArgb()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    }
                    val label = if (node.name.length > 8) node.name.take(8) + "…" else node.name
                    canvas.nativeCanvas.drawText(
                        label,
                        point.x + nodeWidth / 2f,
                        point.y + nodeHeight / 2f + paint.textSize / 3f,
                        paint,
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeDetailDialog(
    detail: KnowledgeNodeDetail,
    onDismiss: () -> Unit,
    onSaveNote: (note: String, mastery: Int, callback: (String?) -> Unit) -> Unit,
    onEditNode: () -> Unit,
    onDeleteNode: () -> Unit,
    onAddPrerequisite: () -> Unit,
    onDeleteDependency: (from: Long, to: Long) -> Unit,
) {
    var note by remember(detail.node.id, detail.node.note) { mutableStateOf(detail.node.note) }
    var mastery by remember(detail.node.id, detail.node.mastery) { mutableStateOf(detail.node.mastery) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.node.name) },
        text = {
            Column(
                modifier = Modifier.height(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${detail.node.subject} · ${detail.node.chapter} · ${detail.node.masteryLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detail.node.definition.isNotBlank()) {
                    Text("定义说明", style = MaterialTheme.typography.labelLarge)
                    Text(detail.node.definition, style = MaterialTheme.typography.bodyMedium)
                }
                if (detail.node.theorems.isNotBlank()) {
                    Text("相关定理", style = MaterialTheme.typography.labelLarge)
                    Text(detail.node.theorems, style = MaterialTheme.typography.bodyMedium)
                }
                Text("掌握程度（0–5）", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    (0..5).forEach { value ->
                        FilterChip(
                            selected = mastery == value,
                            onClick = { mastery = value },
                            label = { Text(value.toString()) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("个人笔记") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "保存笔记",
                        onClick = {
                            onSaveNote(note, mastery) { saveError -> error = saveError }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "编辑知识点",
                        onClick = onEditNode,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
                Text("前置知识", style = MaterialTheme.typography.labelLarge)
                if (detail.prerequisites.isEmpty()) {
                    Text(
                        "还没有前置知识。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    detail.prerequisites.forEach { prerequisite ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prerequisite.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDeleteDependency(prerequisite.id, detail.node.id) }) {
                                Text("删除")
                            }
                        }
                    }
                }
                SecondaryButton(
                    text = "添加前置知识",
                    onClick = onAddPrerequisite,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("后续知识", style = MaterialTheme.typography.labelLarge)
                if (detail.successors.isEmpty()) {
                    Text(
                        "还没有后续知识。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    detail.successors.forEach { successor ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(successor.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onDeleteDependency(detail.node.id, successor.id) }) {
                                Text("删除")
                            }
                        }
                    }
                }
                Text("关联题目", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (detail.linkedProblemIds.isEmpty()) {
                        "暂无关联题目；导入题库后，同名知识点会自动建立关联。"
                    } else {
                        "共 ${detail.linkedProblemIds.size} 道题：" +
                            detail.linkedProblemIds.take(8).joinToString("、") { "#$it" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onDeleteNode) { Text("删除知识点") } },
    )
}

@Composable
private fun NodePickerDialog(
    title: String,
    nodes: List<KnowledgeNode>,
    onDismiss: () -> Unit,
    onSelected: (KnowledgeNode) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.height(380.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索知识点") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    nodes.filter {
                        search.isBlank() || it.name.contains(search) || it.chapter.contains(search)
                    }.forEach { node ->
                        TextButton(
                            onClick = { onSelected(node) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${node.subject} · ${node.name}（${node.chapter}）",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun KnowledgeNodeEditorDialog(
    initial: KnowledgeNode?,
    subjects: List<String>,
    onDismiss: () -> Unit,
    onSave: (KnowledgeNode, (String?) -> Unit) -> Unit,
) {
    var subject by remember { mutableStateOf(initial?.subject ?: subjects.firstOrNull().orEmpty()) }
    var chapter by remember { mutableStateOf(initial?.chapter.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var definition by remember { mutableStateOf(initial?.definition.orEmpty()) }
    var theorems by remember { mutableStateOf(initial?.theorems.orEmpty()) }
    var mastery by remember { mutableStateOf(initial?.mastery ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加知识点" else "编辑知识点") },
        text = {
            Column(
                modifier = Modifier.height(460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("所属科目", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    (subjects + "自定义科目").distinct().forEach { candidate ->
                        FilterChip(
                            selected = subject == candidate,
                            onClick = { subject = if (candidate == "自定义科目") "" else candidate },
                            label = { Text(candidate) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("科目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = chapter,
                    onValueChange = { chapter = it },
                    label = { Text("章节") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("知识点名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = definition,
                    onValueChange = { definition = it },
                    label = { Text("定义说明") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = theorems,
                    onValueChange = { theorems = it },
                    label = { Text("相关定理") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("掌握程度（0–5）", style = MaterialTheme.typography.labelLarge)
                Row {
                    (0..5).forEach { value ->
                        FilterChip(
                            selected = mastery == value,
                            onClick = { mastery = value },
                            label = { Text(value.toString()) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (subject.isBlank() || name.isBlank()) {
                        error = "请填写科目与知识点名称"
                        return@TextButton
                    }
                    onSave(
                        (initial ?: KnowledgeNode(
                            subject = "",
                            chapter = "",
                            name = "",
                        )).copy(
                            subject = subject,
                            chapter = chapter,
                            name = name,
                            definition = definition,
                            theorems = theorems,
                            mastery = mastery,
                        ),
                    ) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
