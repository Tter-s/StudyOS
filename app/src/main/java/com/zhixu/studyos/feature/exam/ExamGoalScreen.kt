package com.zhixu.studyos.feature.exam

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.designsystem.component.SectionHeader
import com.zhixu.studyos.core.exam.Exam
import com.zhixu.studyos.core.exam.GoalMilestone
import com.zhixu.studyos.core.exam.GoalProgress
import com.zhixu.studyos.core.exam.LongTermGoal
import java.time.LocalDate

private enum class ExamGoalTab { EXAMS, GOALS }

@Composable
fun ExamGoalScreen(viewModel: ExamGoalViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(ExamGoalTab.EXAMS) }
    var examEditor by remember { mutableStateOf<Exam?>(null) }
    var showExamEditor by remember { mutableStateOf(false) }
    var goalEditor by remember { mutableStateOf<LongTermGoal?>(null) }
    var showGoalEditor by remember { mutableStateOf(false) }
    var milestoneGoalId by remember { mutableStateOf<Long?>(null) }
    var editingMilestone by remember { mutableStateOf<GoalMilestone?>(null) }
    var deleteExamId by remember { mutableStateOf<Long?>(null) }
    var deleteGoalId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("screen_exam_goal"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("考试与目标", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "倒计时、复习计划与长期目标；复习任务仍由本地排程安排时间。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (tab == ExamGoalTab.EXAMS) {
                                examEditor = null
                                showExamEditor = true
                            } else {
                                goalEditor = null
                                showGoalEditor = true
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == ExamGoalTab.EXAMS,
                        onClick = { tab = ExamGoalTab.EXAMS },
                        label = { Text("考试倒计时") },
                    )
                    FilterChip(
                        selected = tab == ExamGoalTab.GOALS,
                        onClick = { tab = ExamGoalTab.GOALS },
                        label = { Text("长期目标") },
                    )
                }
            }
            if (state.isLoading) {
                item { Text("正在读取…", style = MaterialTheme.typography.bodyMedium) }
            }
            state.errorMessage?.let {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            title = "无法读取考试与目标",
                            message = it,
                            icon = Icons.Rounded.Event,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            when (tab) {
                ExamGoalTab.EXAMS -> {
                    if (state.snapshot.exams.isEmpty()) {
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                EmptyState(
                                    title = "还没有考试",
                                    message = "添加考试后显示首页倒计时，并可按薄弱章节生成复习任务。",
                                    icon = Icons.Rounded.Event,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        state.countdowns.forEach { countdown ->
                            item(key = "exam_${countdown.exam.id}") {
                                ExamCard(
                                    countdown = countdown,
                                    onEdit = {
                                        examEditor = countdown.exam
                                        showExamEditor = true
                                    },
                                    onDelete = { deleteExamId = countdown.exam.id },
                                    onGenerate = { viewModel.generateReviewTasks(countdown.exam.id) },
                                )
                            }
                        }
                    }
                }
                ExamGoalTab.GOALS -> {
                    if (state.snapshot.goals.isEmpty()) {
                        item {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                EmptyState(
                                    title = "还没有长期目标",
                                    message = "目标可以拆成阶段里程碑，进度只依据真实完成情况。",
                                    icon = Icons.Rounded.Add,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        state.snapshot.goals.forEach { goal ->
                            val progress = state.goalProgress[goal.id]
                            item(key = "goal_${goal.id}") {
                                GoalCard(
                                    goal = goal,
                                    progress = progress,
                                    milestones = state.snapshot.milestones[goal.id].orEmpty(),
                                    onEdit = {
                                        goalEditor = goal
                                        showGoalEditor = true
                                    },
                                    onDelete = { deleteGoalId = goal.id },
                                    onAddMilestone = {
                                        milestoneGoalId = goal.id
                                        editingMilestone = null
                                    },
                                    onEditMilestone = { milestone ->
                                        milestoneGoalId = goal.id
                                        editingMilestone = milestone
                                    },
                                    onToggleMilestone = viewModel::toggleMilestone,
                                    onDeleteMilestone = viewModel::deleteMilestone,
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (showExamEditor) {
        ExamEditorDialog(
            initial = examEditor,
            onDismiss = { showExamEditor = false },
            onSave = { exam, callback ->
                viewModel.saveExam(exam) { error ->
                    callback(error)
                    if (error == null) showExamEditor = false
                }
            },
        )
    }
    if (showGoalEditor) {
        GoalEditorDialog(
            initial = goalEditor,
            onDismiss = { showGoalEditor = false },
            onSave = { goal, callback ->
                viewModel.saveGoal(goal) { error ->
                    callback(error)
                    if (error == null) showGoalEditor = false
                }
            },
        )
    }
    milestoneGoalId?.let { goalId ->
        MilestoneEditorDialog(
            goalId = goalId,
            initial = editingMilestone,
            onDismiss = { milestoneGoalId = null },
            onSave = { milestone, callback ->
                viewModel.saveMilestone(milestone) { error ->
                    callback(error)
                    if (error == null) milestoneGoalId = null
                }
            },
        )
    }
    state.lastGeneratedPlan?.let { result ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPlan,
            title = { Text("复习任务预览") },
            text = {
                Column(
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    result.plan.items.forEach { item ->
                        Text("${item.date} · ${item.title}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            item.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    result.plan.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "已创建 ${result.createdTaskIds.size} 个“待安排”任务，请到学习 → 排程中安排时间。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissPlan) { Text("知道了") } },
        )
    }
    deleteExamId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteExamId = null },
            title = { Text("删除考试") },
            text = { Text("删除考试不会删除已生成的任务或学习记录。确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteExam(id)
                    deleteExamId = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteExamId = null }) { Text("取消") } },
        )
    }
    deleteGoalId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteGoalId = null },
            title = { Text("删除长期目标") },
            text = { Text("目标和里程碑会被删除，已生成的任务与学习记录保留。确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGoal(id)
                    deleteGoalId = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteGoalId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ExamCard(
    countdown: com.zhixu.studyos.core.exam.ExamCountdown,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onGenerate: () -> Unit,
) {
    val exam = countdown.exam
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(exam.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${exam.subject} · ${exam.date}" +
                        (exam.startMinute?.let { " ${formatMinute(it)}" } ?: "") +
                        (exam.durationMinutes?.let { " · ${it} 分钟" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                countdown.label,
                style = MaterialTheme.typography.titleMedium,
                color = when (countdown.phase) {
                    com.zhixu.studyos.core.exam.ExamPhase.TODAY,
                    com.zhixu.studyos.core.exam.ExamPhase.TOMORROW -> MaterialTheme.colorScheme.error
                    com.zhixu.studyos.core.exam.ExamPhase.THIS_WEEK -> MaterialTheme.colorScheme.primary
                    com.zhixu.studyos.core.exam.ExamPhase.PASSED -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (exam.scope.isNotBlank()) {
            Text("范围：${exam.scope}", style = MaterialTheme.typography.bodySmall)
        }
        if (exam.targetScore.isNotBlank()) {
            Text("目标：${exam.targetScore}", style = MaterialTheme.typography.bodySmall)
        }
        if (exam.materials.isNotBlank()) {
            Text("资料：${exam.materials}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (exam.reminderEnabled) "提前 ${exam.reminderDaysBefore} 天提醒" else "已关闭提醒",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "编辑", onClick = onEdit, modifier = Modifier.weight(1f))
            SecondaryButton(text = "生成复习任务", onClick = onGenerate, modifier = Modifier.weight(1.4f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除考试")
            }
        }
    }
}

@Composable
private fun GoalCard(
    goal: LongTermGoal,
    progress: GoalProgress?,
    milestones: List<GoalMilestone>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddMilestone: () -> Unit,
    onEditMilestone: (GoalMilestone) -> Unit,
    onToggleMilestone: (Long, Boolean) -> Unit,
    onDeleteMilestone: (Long) -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(goal.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${goal.category} · ${goal.status}" +
                        (goal.targetDate?.let { " · 目标日期 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "删除目标") }
        }
        if (progress != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (progress.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text(
                "里程碑进度 " + (progress.percent?.let { "$it%" } ?: "数据不足") +
                    if (progress.overdueMilestones > 0) " · ${progress.overdueMilestones} 个已逾期" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        milestones.forEach { milestone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = milestone.isCompleted,
                    onCheckedChange = { onToggleMilestone(milestone.id, it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        milestone.title,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        (milestone.dueDate?.let { "截止 $it" } ?: "无截止日期") +
                            " · 权重 ${milestone.weight}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onEditMilestone(milestone) }) { Text("编辑") }
                IconButton(onClick = { onDeleteMilestone(milestone.id) }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除里程碑")
                }
            }
        }
        if (milestones.isEmpty()) {
            Text(
                "还没有里程碑；目标进度显示为数据不足。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAddMilestone) { Text("添加里程碑") }
    }
}

@Composable
private fun ExamEditorDialog(
    initial: Exam?,
    onDismiss: () -> Unit,
    onSave: (Exam, (String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var subject by remember { mutableStateOf(initial?.subject.orEmpty()) }
    var date by remember { mutableStateOf(initial?.date?.toString() ?: LocalDate.now().plusWeeks(2).toString()) }
    var start by remember { mutableStateOf(initial?.startMinute?.let(::formatMinute).orEmpty()) }
    var duration by remember { mutableStateOf(initial?.durationMinutes?.toString().orEmpty()) }
    var scope by remember { mutableStateOf(initial?.scope.orEmpty()) }
    var target by remember { mutableStateOf(initial?.targetScore.orEmpty()) }
    var materials by remember { mutableStateOf(initial?.materials.orEmpty()) }
    var reminderEnabled by remember { mutableStateOf(initial?.reminderEnabled ?: true) }
    var reminderDays by remember { mutableStateOf((initial?.reminderDaysBefore ?: 3).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加考试" else "编辑考试") },
        text = {
            Column(
                modifier = Modifier.height(480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("考试名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("科目") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期 yyyy-MM-dd *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it },
                        label = { Text("开始 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it.filter(Char::isDigit) },
                        label = { Text("时长/分钟") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = scope,
                    onValueChange = { scope = it },
                    label = { Text("考试范围（章节）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("目标成绩（仅记录）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = materials,
                    onValueChange = { materials = it },
                    label = { Text("复习资料") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("考前提醒", modifier = Modifier.weight(1f))
                    Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
                }
                OutlinedTextField(
                    value = reminderDays,
                    onValueChange = { reminderDays = it.filter(Char::isDigit) },
                    label = { Text("提前几天提醒") },
                    singleLine = true,
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
                    if (name.isBlank()) {
                        error = "请填写考试名称"
                        return@TextButton
                    }
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                    if (parsedDate == null) {
                        error = "日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    val startMinute = start.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching {
                            val (h, m) = it.split(":").map(String::toInt)
                            h * 60 + m
                        }.getOrNull()
                    }
                    if (start.isNotBlank() && startMinute == null) {
                        error = "开始时间格式应为 HH:mm"
                        return@TextButton
                    }
                    val durationValue = duration.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
                    if (duration.isNotBlank() && durationValue == null) {
                        error = "时长应为分钟数"
                        return@TextButton
                    }
                    val exam = (initial ?: Exam(name = "", subject = "", date = parsedDate)).copy(
                        name = name,
                        subject = subject,
                        date = parsedDate,
                        startMinute = startMinute,
                        durationMinutes = durationValue,
                        scope = scope,
                        targetScore = target,
                        materials = materials,
                        reminderEnabled = reminderEnabled,
                        reminderDaysBefore = reminderDays.toIntOrNull() ?: 3,
                    )
                    onSave(exam) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GoalEditorDialog(
    initial: LongTermGoal?,
    onDismiss: () -> Unit,
    onSave: (LongTermGoal, (String?) -> Unit) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var category by remember { mutableStateOf(initial?.category ?: "学期目标") }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var startDate by remember { mutableStateOf((initial?.startDate ?: LocalDate.now()).toString()) }
    var targetDate by remember { mutableStateOf(initial?.targetDate?.toString().orEmpty()) }
    var status by remember { mutableStateOf(initial?.status ?: "ACTIVE") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加长期目标" else "编辑长期目标") },
        text = {
            Column(
                modifier = Modifier.height(420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("目标名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("类别（学期/学年/成绩/英语/编程/竞赛/自定义）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text("开始 yyyy-MM-dd") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = targetDate,
                        onValueChange = { targetDate = it },
                        label = { Text("目标日期（可空）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("状态", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("ACTIVE" to "进行中", "PAUSED" to "暂停", "COMPLETED" to "已完成").forEach { (value, label) ->
                        FilterChip(
                            selected = status == value,
                            onClick = { status = value },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Text(
                    "目标进度只根据里程碑完成情况计算，不预测成绩或排名。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isBlank()) {
                        error = "请填写目标名称"
                        return@TextButton
                    }
                    val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
                    if (start == null) {
                        error = "开始日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    val target = targetDate.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    if (targetDate.isNotBlank() && target == null) {
                        error = "目标日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    onSave(
                        (initial ?: LongTermGoal(title = "", startDate = start)).copy(
                            title = title,
                            category = category,
                            description = description,
                            startDate = start,
                            targetDate = target,
                            status = status,
                        ),
                    ) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MilestoneEditorDialog(
    goalId: Long,
    initial: GoalMilestone?,
    onDismiss: () -> Unit,
    onSave: (GoalMilestone, (String?) -> Unit) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var dueDate by remember { mutableStateOf(initial?.dueDate?.toString().orEmpty()) }
    var weight by remember { mutableStateOf((initial?.weight ?: 1).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加里程碑" else "编辑里程碑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("里程碑名称 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = { Text("截止日期（可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it.filter(Char::isDigit) },
                    label = { Text("权重 1–10") },
                    singleLine = true,
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
                    if (title.isBlank()) {
                        error = "请填写里程碑名称"
                        return@TextButton
                    }
                    val due = dueDate.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    if (dueDate.isNotBlank() && due == null) {
                        error = "日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    onSave(
                        (initial ?: GoalMilestone(goalId = goalId, title = "")).copy(
                            goalId = goalId,
                            title = title,
                            dueDate = due,
                            weight = (weight.toIntOrNull() ?: 1).coerceIn(1, 10),
                        ),
                    ) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
