package com.zhixu.studyos.feature.math

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.exercise.RecommendationKind
import com.zhixu.studyos.core.math.HintLevel
import com.zhixu.studyos.core.math.MathHint
import com.zhixu.studyos.core.math.ProofVerdict
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.feature.bank.FormulaPreview
import kotlinx.coroutines.delay

@Composable
fun MathAssistantScreen(viewModel: MathAssistantViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    val snackbar = remember { SnackbarHostState() }
    var pickerVisible by remember { mutableStateOf(false) }
    var attemptDialog by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf(false) }
    var thinkingSeconds by remember(state.selected?.id) { mutableIntStateOf(0) }
    var thinkingRunning by remember(state.selected?.id) { mutableStateOf(false) }

    LaunchedEffect(thinkingRunning, state.selected?.id) {
        if (thinkingRunning) {
            while (thinkingRunning) {
                delay(1_000)
                thinkingSeconds += 1
            }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("screen_math"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("数学助手", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "五级提示、证明检查与个人难度档案；AI 不是标准答案。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text("刷新")
                    }
                    TextButton(onClick = { pickerVisible = true }) { Text("换题") }
                }
            }
            if (state.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.errorMessage != null) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            title = "数学助手暂时无法加载",
                            message = state.errorMessage.orEmpty(),
                            icon = Icons.Rounded.ErrorOutline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                if (state.recommendations.isNotEmpty()) {
                    item { SectionHeader(title = "个人难度推荐") }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "推荐基于你的真实尝试记录（独立性、提示等级、用时与结果），" +
                                    "不是训练出的精准能力预测。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.recommendations.take(4).forEach { recommendation ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${recommendation.kind.label} · ${recommendation.candidate.title}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            recommendation.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = {
                                        viewModel.openProblem(recommendation.candidate.id)
                                    }) { Text("打开") }
                                }
                            }
                        }
                    }
                }
                val problem = state.selected
                if (problem == null) {
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            EmptyState(
                                title = "题库中还没有数学题",
                                message = "先在“学习 → 题库”导入或录入题目，再回到这里使用分级提示。",
                                icon = Icons.Rounded.Lightbulb,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    item { ProblemHeaderCard(problem = problem, state = state) }
                    item {
                        AppCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("独立思考", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    formatSeconds(thinkingSeconds),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Text(
                                "Level 0：不显示任何提示。先写下你的思路，再决定是否升级提示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(
                                    text = if (thinkingRunning) "暂停" else "开始/继续",
                                    onClick = { thinkingRunning = !thinkingRunning },
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryButton(
                                    text = "重置",
                                    onClick = {
                                        thinkingRunning = false
                                        thinkingSeconds = 0
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.thoughts,
                                onValueChange = viewModel::setThoughts,
                                label = { Text("当前思路（可保存草稿）") },
                                modifier = Modifier.fillMaxWidth().height(110.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(
                                    text = "保存草稿",
                                    onClick = viewModel::saveDraft,
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryButton(
                                    text = "记录尝试",
                                    onClick = { attemptDialog = true },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    item { HintCard(state = state, viewModel = viewModel, onReportError = { errorDialog = true }) }
                    item { ProofCard(state = state, viewModel = viewModel) }
                    if (state.attempts.isNotEmpty() || state.profile?.attemptCount != 0) {
                        item {
                            AttemptHistoryCard(state = state)
                        }
                    }
                    if (state.savedProofs.isNotEmpty()) {
                        item { SavedProofsCard(state = state) }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    state.pendingLevel?.let { level ->
        val jump = level == HintLevel.LEVEL_5
        AlertDialog(
            onDismissRequest = viewModel::dismissPendingLevel,
            title = { Text(if (jump) "查看完整参考证明？" else "跳过提示等级？") },
            text = {
                Text(
                    if (jump) {
                        "Level 5 会显示完整参考证明。建议先自己尝试并记录思路；" +
                            "AI 证明不保证数学上严格正确，需要你自行核对。"
                    } else {
                        "当前下一级是 Level ${state.nextLevel.ordinal}，你请求的是 Level ${level.ordinal}。" +
                            "跳级会减少独立思考的机会，但你可以明确选择这样做。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPendingLevel) { Text("我确认") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPendingLevel) { Text("取消") }
            },
        )
    }
    if (pickerVisible) {
        ProblemPickerDialog(
            problems = state.visibleProblems,
            search = state.search,
            onSearchChange = viewModel::setSearch,
            onDismiss = { pickerVisible = false },
            onSelected = {
                pickerVisible = false
                viewModel.openProblem(it.id)
            },
        )
    }
    if (attemptDialog) {
        AttemptDialog(
            hasHints = (state.highestLevel?.ordinal ?: 0) > 0,
            initialMinutes = (thinkingSeconds / 60).coerceAtLeast(1),
            onDismiss = { attemptDialog = false },
            onSubmit = { minutes, correct, independent, note, callback ->
                viewModel.submitAttempt(minutes, correct, independent, note) { error ->
                    callback(error)
                    if (error == null) {
                        attemptDialog = false
                        thinkingRunning = false
                    }
                }
            },
        )
    }
    if (errorDialog) {
        ReportErrorDialog(
            onDismiss = { errorDialog = false },
            onSubmit = { reason, note ->
                errorDialog = false
                viewModel.reportHintError(reason, note)
            },
        )
    }
}

@Composable
private fun ProblemHeaderCard(problem: ProblemBankEntry, state: MathAssistantUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "${problem.courseName} · ${problem.chapter} · 第 ${problem.problemNumber} 题",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        RichMathText(text = problem.statement, style = MaterialTheme.typography.bodyMedium)
        if (problem.formulas.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("公式", style = MaterialTheme.typography.labelLarge)
            problem.formulas.forEach { formula ->
                FormulaPreview(latex = formula.latex, displayMode = true, modifier = Modifier.fillMaxWidth())
                if (formula.needsReview) {
                    Text(
                        "该公式由本地识别产生，需要人工校对。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "估计难度 ${problem.estimatedDifficulty ?: "未评估"} · 预计 ${problem.estimatedMinutes ?: "—"} 分钟" +
                (state.profile?.personalDifficulty?.let { " · 个人难度 $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.profile != null && state.profile.attemptCount > 0) {
            Text(
                "已尝试 ${state.profile.attemptCount} 次 · 独立 ${state.profile.independentCount} 次 · " +
                    "掌握估计 ${(state.profile.mastery * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (problem.knowledgePoints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                problem.knowledgePoints.forEach { point ->
                    AssistChip(onClick = {}, label = { Text(point.name) })
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
        Text(
            "原始页面可在“学习 → 题库”的题目详情中查看。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HintCard(
    state: MathAssistantUiState,
    viewModel: MathAssistantViewModel,
    onReportError: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("分级提示", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "已用最高：${state.highestLevel?.let { "Level ${it.ordinal} ${it.label}" } ?: "Level 0"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "默认从 Level 0 开始，逐级解锁；跳级或查看完整证明都需要明确确认。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            HintLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.hint?.level == level,
                    onClick = { viewModel.requestLevel(level) },
                    label = { Text("L${level.ordinal}") },
                    enabled = !state.isHintLoading,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        PrimaryButton(
            text = if (state.isHintLoading) {
                "正在请求提示…"
            } else {
                "请求下一级提示（Level ${state.nextLevel.ordinal}）"
            },
            onClick = { viewModel.requestLevel(state.nextLevel) },
            enabled = !state.isHintLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        state.hint?.let { hint ->
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Level ${hint.level.ordinal} · ${hint.level.label}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (hint.uncertain) {
                Text(
                    "AI 标记为不确定，请结合教材或课程内容核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val body = buildList {
                if (hint.hint.isNotBlank()) add(hint.hint)
                hint.keyObservation?.let { add("关键观察：$it") }
                hint.strategy?.let { add("方法提示：$it") }
                hint.steps.forEachIndexed { index, step -> add("${index + 1}. $step") }
                hint.referenceProof?.let { add("参考证明：\n$it") }
                hint.caveats.forEach { add("需要注意：$it") }
            }.joinToString("\n\n")
            RichMathText(text = body, style = MaterialTheme.typography.bodyMedium)
            state.hintMeta?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReportError) { Text("报告提示问题") }
        }
        if (state.hints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "提示记录：" + state.hints.joinToString("、") { "L${it.level.ordinal}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "所有提示都记录时间与等级；使用提示的尝试不会被算作独立完成。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProofCard(
    state: MathAssistantUiState,
    viewModel: MathAssistantViewModel,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("提交我的证明", style = MaterialTheme.typography.titleMedium)
        Text(
            "AI 可以指出潜在缺口，但不能把 AI 的判断当作严格验证。保存后可作为你的复习版本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.proofText,
            onValueChange = viewModel::setProofText,
            label = { Text("我的证明（支持 LaTeX，可用 \$...\$）") },
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = if (state.isProofLoading) "检查中…" else "AI 检查漏洞",
                onClick = { viewModel.checkProof(otherMethods = false) },
                enabled = !state.isProofLoading,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "其他方法",
                onClick = { viewModel.checkProof(otherMethods = true) },
                enabled = !state.isProofLoading,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = viewModel::saveConfirmedProof, modifier = Modifier.fillMaxWidth()) {
            Text("保存这个证明版本")
        }
        state.proofCheck?.let { check ->
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "AI 检查结论：${check.verdict.label}",
                style = MaterialTheme.typography.titleSmall,
                color = when (check.verdict) {
                    ProofVerdict.LOGIC_GAP -> MaterialTheme.colorScheme.error
                    ProofVerdict.NEEDS_CHECK, ProofVerdict.UNCERTAIN -> MaterialTheme.colorScheme.primary
                    ProofVerdict.OK -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (check.certainIssues.isNotEmpty()) {
                Text("确定的逻辑问题", style = MaterialTheme.typography.labelLarge)
                check.certainIssues.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (check.needsCheck.isNotEmpty()) {
                Text("需要进一步检查", style = MaterialTheme.typography.labelLarge)
                check.needsCheck.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (check.suggestions.isNotEmpty()) {
                Text("建议", style = MaterialTheme.typography.labelLarge)
                check.suggestions.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            check.uncertainNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                check.disclaimer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.proofMeta?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttemptHistoryCard(state: MathAssistantUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("尝试与难度档案", style = MaterialTheme.typography.titleMedium)
        state.profile?.let { profile ->
            Text(
                "个人难度 ${profile.personalDifficulty ?: "—"}（1–5，越高越难）· " +
                    "平均用时 ${"%.1f".format(profile.avgActualMinutes)} 分钟 · " +
                    "平均最高提示 ${"%.1f".format(profile.avgHintLevel)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.attemptResultMessage?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        state.attempts.reversed().take(5).forEach { attempt ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "第 ${attempt.attemptIndex} 次 · ${attempt.durationMinutes} 分钟 · " +
                    "提示 L${attempt.highestHintLevel} · " +
                    (if (attempt.correct) "完成" else "未完成") +
                    (if (attempt.independent) " · 独立" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SavedProofsCard(state: MathAssistantUiState) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text("保存的证明/草稿", style = MaterialTheme.typography.titleMedium)
        state.savedProofs.take(5).forEach { proof ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${proof.createdAt} · ${proof.source} · ${proof.note}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RichMathText(
                text = proof.content.take(600),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProblemPickerDialog(
    problems: List<ProblemBankEntry>,
    search: String,
    onSearchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelected: (ProblemBankEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择题目") },
        text = {
            Column(modifier = Modifier.height(420.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    label = { Text("搜索题干、章节或题号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    items(problems.size) { index ->
                        val problem = problems[index]
                        TextButton(
                            onClick = { onSelected(problem) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${problem.chapter} 第 ${problem.problemNumber} 题",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    problem.statement.take(60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AttemptDialog(
    hasHints: Boolean,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onSubmit: (
        minutes: Int,
        correct: Boolean,
        independent: Boolean,
        note: String,
        callback: (String?) -> Unit,
    ) -> Unit,
) {
    var minutes by remember { mutableStateOf(initialMinutes.toString()) }
    var correct by remember { mutableStateOf(true) }
    var independent by remember { mutableStateOf(!hasHints) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录本次尝试") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit) },
                    label = { Text("本次用时（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("完成", modifier = Modifier.weight(1f))
                    Switch(checked = correct, onCheckedChange = { correct = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("独立完成")
                        Text(
                            if (hasHints) {
                                "已使用提示，按记录规则不能标记为独立完成。"
                            } else {
                                "未使用任何 AI 提示且自己完成。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = independent && !hasHints,
                        onCheckedChange = { independent = it },
                        enabled = !hasHints,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = minutes.toIntOrNull()
                    if (value == null || value !in 0..1440) {
                        error = "请输入 0–1440 之间的分钟数"
                        return@TextButton
                    }
                    onSubmit(value, correct, independent, note) { submitError -> error = submitError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ReportErrorDialog(
    onDismiss: () -> Unit,
    onSubmit: (reason: String, note: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("报告提示问题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "报告只保存在本机，不会自动上传。请说明问题，便于后续在题库或分析中核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("问题原因 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("补充说明") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(reason, note) },
                enabled = reason.isNotBlank(),
            ) { Text("提交") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 将 $...$ / $$...$$ 片段交给离线 KaTeX 渲染，其余文本按普通段落显示。 */
@Composable
fun RichMathText(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val segments = remember(text) { splitMath(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            when {
                segment.isFormula && segment.display -> FormulaPreview(
                    latex = segment.content,
                    displayMode = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                segment.isFormula -> FormulaPreview(
                    latex = segment.content,
                    displayMode = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                segment.content.isNotBlank() -> Text(segment.content.trim(), style = style)
            }
        }
    }
}

private data class MathSegment(val content: String, val isFormula: Boolean, val display: Boolean)

private fun splitMath(text: String): List<MathSegment> {
    val segments = mutableListOf<MathSegment>()
    var index = 0
    val buffer = StringBuilder()
    fun flushText() {
        if (buffer.isNotEmpty()) {
            segments += MathSegment(buffer.toString(), isFormula = false, display = false)
            buffer.clear()
        }
    }
    while (index < text.length) {
        if (text.startsWith("$$", index)) {
            val end = text.indexOf("$$", index + 2)
            if (end > index + 2) {
                flushText()
                segments += MathSegment(text.substring(index + 2, end), true, true)
                index = end + 2
                continue
            }
        }
        if (text[index] == '$') {
            val end = text.indexOf('$', index + 1)
            if (end > index + 1) {
                flushText()
                segments += MathSegment(text.substring(index + 1, end), true, false)
                index = end + 1
                continue
            }
        }
        buffer.append(text[index])
        index += 1
    }
    flushText()
    return if (segments.isEmpty()) listOf(MathSegment(text, false, false)) else segments
}

private fun formatSeconds(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return "%02d:%02d".format(minutes, rest)
}
