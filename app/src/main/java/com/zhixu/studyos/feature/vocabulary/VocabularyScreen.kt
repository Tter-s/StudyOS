package com.zhixu.studyos.feature.vocabulary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.data.repository.VocabularyImportResultSummary
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.core.designsystem.component.SecondaryButton
import com.zhixu.studyos.core.vocabulary.VocabularyRating
import com.zhixu.studyos.core.vocabulary.VocabularySettings
import com.zhixu.studyos.core.vocabulary.VocabularyStatus
import com.zhixu.studyos.core.vocabulary.VocabularyWord
import java.time.LocalDate

@Composable
fun VocabularyScreen(viewModel: VocabularyViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    val snackbar = remember { SnackbarHostState() }
    var addDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VocabularyWord?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VocabularyWord?>(null) }
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importCsv) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().testTag("screen_vocabulary")) {
            if (state.isReviewing) {
                ReviewSession(
                    state = state,
                    onReveal = viewModel::revealAnswer,
                    onRate = viewModel::rate,
                    onExit = viewModel::exitReview,
                )
            } else {
                VocabularyList(
                    state = state,
                    onAdd = { addDialog = true },
                    onImport = {
                        csvLauncher.launch(
                            arrayOf(
                                "text/*",
                                "text/comma-separated-values",
                                "application/csv",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onSettings = { showSettings = true },
                    onStartReview = viewModel::startReview,
                    onGenerateTask = viewModel::generateTask,
                    onSearchChange = viewModel::setSearch,
                    onSearch = viewModel::searchNow,
                    onSelectList = viewModel::setList,
                    onOpenWord = { editing = it },
                    onLoadMore = viewModel::loadMore,
                    onDelete = { deleteTarget = it },
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    if (addDialog) {
        VocabularyEditorDialog(
            initial = null,
            listNames = state.listNames,
            onDismiss = { addDialog = false },
            onSave = { word, meaning, phonetic, pos, example, list, note, callback ->
                viewModel.addWord(word, meaning, phonetic, pos, example, list, note) { error ->
                    callback(error)
                    if (error == null) addDialog = false
                }
            },
        )
    }
    editing?.let { word ->
        VocabularyEditorDialog(
            initial = word,
            listNames = state.listNames,
            onDismiss = { editing = null },
            onSave = { title, meaning, phonetic, pos, example, list, note, callback ->
                viewModel.updateWord(
                    word.copy(
                        word = title,
                        meaning = meaning,
                        phonetic = phonetic,
                        partOfSpeech = pos,
                        example = example,
                        listName = list,
                        note = note,
                    ),
                ) { error ->
                    callback(error)
                    if (error == null) editing = null
                }
            },
            onDelete = {
                editing = null
                deleteTarget = word
            },
        )
    }
    if (showSettings) {
        VocabularySettingsDialog(
            settings = state.settings,
            onDismiss = { showSettings = false },
            onSave = { settings, callback ->
                viewModel.saveSettings(settings) { error ->
                    callback(error)
                    if (error == null) showSettings = false
                }
            },
        )
    }
    deleteTarget?.let { word ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除单词") },
            text = { Text("确定删除「${word.word}」及其复习记录？此操作不会影响其他数据。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteWord(word.id)
                        deleteTarget = null
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    state.importResult?.let { result ->
        ImportResultDialog(result = result, onDismiss = viewModel::dismissImportResult)
    }
}

@Composable
private fun VocabularyList(
    state: VocabularyUiState,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onStartReview: () -> Unit,
    onGenerateTask: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectList: (String) -> Unit,
    onOpenWord: (VocabularyWord) -> Unit,
    onLoadMore: () -> Unit,
    onDelete: (VocabularyWord) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("英语词汇", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "SM-2 间隔重复 · 全部数据离线保存在本机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onImport, modifier = Modifier.testTag("vocab_import")) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = "导入 CSV")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "词汇设置")
                }
                IconButton(onClick = onAdd, modifier = Modifier.testTag("vocab_add")) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加单词")
                }
            }
        }
        item {
            AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    VocabularyMetric("总词汇", state.totalWords.toString(), Modifier.weight(1f))
                    VocabularyMetric("待复习", state.dueWords.toString(), Modifier.weight(1f))
                    VocabularyMetric(
                        "已掌握",
                        state.stats?.masteredWords?.toString() ?: "—",
                        Modifier.weight(1f),
                    )
                    VocabularyMetric(
                        "连续",
                        state.stats?.streakDays?.let { "$it 天" } ?: "—",
                        Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = if (state.dueWords > 0) "开始复习 ${state.dueWords}" else "开始复习",
                        onClick = onStartReview,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "生成今日任务",
                        onClick = onGenerateTask,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "每日上限：新词 ${state.settings.dailyNewLimit} · 复习 ${state.settings.dailyReviewLimit} · " +
                        "时间预算 ${state.settings.dailyTimeBudgetMinutes} 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.stats?.recentRecallRatePercent?.let {
                    Text(
                        "近 7 天回忆率 $it% · 复习 ${state.stats?.recentReviews ?: 0} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.dueWords > state.settings.dailyReviewLimit + state.settings.dailyNewLimit) {
                    Text(
                        "待复习数量超过每日上限：系统会优先显示积压，不会无限制增加今日任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.selectedList.isBlank(),
                    onClick = { onSelectList("") },
                    label = { Text("全部词表") },
                )
                Spacer(modifier = Modifier.width(6.dp))
                state.listNames.forEach { name ->
                    FilterChip(
                        selected = state.selectedList == name,
                        onClick = { onSelectList(name) },
                        label = { Text(name) },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = onSearchChange,
                    label = { Text("搜索单词或释义") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSearch) { Text("搜索") }
            }
        }
        when {
            state.isLoading -> item {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.errorMessage != null -> item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "词汇库暂时无法加载",
                        message = state.errorMessage,
                        icon = Icons.Rounded.ErrorOutline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.words.isEmpty() -> item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = "还没有单词",
                        message = "可以手动添加单词，或导入你合法持有的 CSV/TSV 词表。" +
                            "应用不内置未经授权的商业词库。",
                        icon = Icons.Rounded.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            else -> {
                items(state.words, key = { it.id }) { word ->
                    VocabularyRow(
                        word = word,
                        onOpen = { onOpenWord(word) },
                        onDelete = { onDelete(word) },
                    )
                }
                if (state.words.size < state.totalCount) {
                    item {
                        SecondaryButton(
                            text = if (state.isLoadingMore) "加载中…" else "加载更多",
                            onClick = onLoadMore,
                            enabled = !state.isLoadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabularyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VocabularyRow(
    word: VocabularyWord,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth().testTag("vocab_${word.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.word, style = MaterialTheme.typography.titleMedium)
                    if (word.phonetic.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "/${word.phonetic}/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    listOf(word.partOfSpeech, word.meaning)
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                        .ifBlank { "尚未填写释义" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    "${statusLabel(word.status)} · 下次复习 ${word.dueDate}" +
                        (if (word.lapseCount > 0) " · 遗忘 ${word.lapseCount} 次" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onOpen) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

private fun statusLabel(status: VocabularyStatus): String = when (status) {
    VocabularyStatus.NEW -> "新词"
    VocabularyStatus.LEARNING -> "学习中"
    VocabularyStatus.REVIEWING -> "复习中"
    VocabularyStatus.MASTERED -> "已掌握"
    VocabularyStatus.SUSPENDED -> "已暂停"
}

@Composable
private fun ReviewSession(
    state: VocabularyUiState,
    onReveal: () -> Unit,
    onRate: (VocabularyRating) -> Unit,
    onExit: () -> Unit,
) {
    val word = state.currentReviewWord ?: return
    val progress = if (state.reviewQueue.isEmpty()) 0f else {
        state.reviewIndex.toFloat() / state.reviewQueue.size
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("退出复习") }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${state.reviewIndex + 1} / ${state.reviewQueue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        AppCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    word.word,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (word.phonetic.isNotBlank()) {
                    Text(
                        "/${word.phonetic}/",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${word.listName} · ${statusLabel(word.status)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                if (!state.revealed) {
                    PrimaryButton(text = "显示释义", onClick = onReveal, modifier = Modifier.fillMaxWidth(0.7f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "先在心里回想，再看答案；这不是考试，诚实评分会让复习间隔更准确。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        listOf(word.partOfSpeech, word.meaning)
                            .filter(String::isNotBlank)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (word.example.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            word.example,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (word.note.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "笔记：${word.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RatingButton("忘记", VocabularyRating.AGAIN, Modifier.weight(1f), onRate)
                        RatingButton("困难", VocabularyRating.HARD, Modifier.weight(1f), onRate)
                        RatingButton("记得", VocabularyRating.GOOD, Modifier.weight(1f), onRate)
                        RatingButton("轻松", VocabularyRating.EASY, Modifier.weight(1f), onRate)
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(
    label: String,
    rating: VocabularyRating,
    modifier: Modifier,
    onRate: (VocabularyRating) -> Unit,
) {
    Button(
        onClick = { onRate(rating) },
        modifier = modifier,
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun VocabularyEditorDialog(
    initial: VocabularyWord?,
    listNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (
        word: String,
        meaning: String,
        phonetic: String,
        partOfSpeech: String,
        example: String,
        listName: String,
        note: String,
        onResult: (String?) -> Unit,
    ) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var word by remember { mutableStateOf(initial?.word.orEmpty()) }
    var meaning by remember { mutableStateOf(initial?.meaning.orEmpty()) }
    var phonetic by remember { mutableStateOf(initial?.phonetic.orEmpty()) }
    var pos by remember { mutableStateOf(initial?.partOfSpeech.orEmpty()) }
    var example by remember { mutableStateOf(initial?.example.orEmpty()) }
    var listName by remember { mutableStateOf(initial?.listName ?: "自定义") }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加单词" else "编辑单词") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScrollCompat(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("单词 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phonetic,
                    onValueChange = { phonetic = it },
                    label = { Text("音标") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pos,
                    onValueChange = { pos = it },
                    label = { Text("词性") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = meaning,
                    onValueChange = { meaning = it },
                    label = { Text("释义") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = { Text("例句") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = listName,
                    onValueChange = { listName = it },
                    label = { Text("词表") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("个人笔记") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (initial != null) {
                    Text(
                        "当前状态：${statusLabel(initial.status)} · 复习 ${initial.reviewCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (word.isBlank()) {
                        error = "请填写单词"
                        return@TextButton
                    }
                    onSave(word, meaning, phonetic, pos, example, listName, note) { saveError ->
                        error = saveError
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.verticalScroll(rememberScrollState())

@Composable
private fun VocabularySettingsDialog(
    settings: VocabularySettings,
    onDismiss: () -> Unit,
    onSave: (VocabularySettings, (String?) -> Unit) -> Unit,
) {
    var newLimit by remember { mutableStateOf(settings.dailyNewLimit.toString()) }
    var reviewLimit by remember { mutableStateOf(settings.dailyReviewLimit.toString()) }
    var timeBudget by remember { mutableStateOf(settings.dailyTimeBudgetMinutes.toString()) }
    var examDate by remember { mutableStateOf(settings.examDate?.toString().orEmpty()) }
    var generateTask by remember { mutableStateOf(settings.taskGenerationEnabled) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("词汇设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newLimit,
                    onValueChange = { newLimit = it.filter(Char::isDigit) },
                    label = { Text("每日新词上限") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = reviewLimit,
                    onValueChange = { reviewLimit = it.filter(Char::isDigit) },
                    label = { Text("每日复习上限") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = timeBudget,
                    onValueChange = { timeBudget = it.filter(Char::isDigit) },
                    label = { Text("每日英语时间预算（分钟）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = examDate,
                    onValueChange = { examDate = it },
                    label = { Text("考试日期（yyyy-MM-dd，可留空）") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动生成每日词汇任务", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "生成“待安排”任务，具体时间仍由本地排程决定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = generateTask, onCheckedChange = { generateTask = it })
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newValue = newLimit.toIntOrNull()
                    val reviewValue = reviewLimit.toIntOrNull()
                    val timeValue = timeBudget.toIntOrNull()
                    if (newValue == null || reviewValue == null || timeValue == null) {
                        error = "请输入有效数字"
                        return@TextButton
                    }
                    val parsedExam = examDate.trim().takeIf(String::isNotEmpty)?.let {
                        runCatching { LocalDate.parse(it) }.getOrNull()
                    }
                    if (examDate.isNotBlank() && parsedExam == null) {
                        error = "考试日期格式应为 yyyy-MM-dd"
                        return@TextButton
                    }
                    onSave(
                        settings.copy(
                            dailyNewLimit = newValue,
                            dailyReviewLimit = reviewValue,
                            dailyTimeBudgetMinutes = timeValue,
                            examDate = parsedExam,
                            taskGenerationEnabled = generateTask,
                        ),
                    ) { saveError -> error = saveError }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportResultDialog(
    result: VocabularyImportResultSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CSV 导入结果") },
        text = {
            when (result) {
                is VocabularyImportResultSummary.Saved -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScrollCompat(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("新增 ${result.inserted} 个单词")
                    Text("跳过重复 ${result.skippedDuplicates} 个")
                    Text("无效行 ${result.invalid} 个")
                    Text("解析到 ${result.parsed.rows.size} 行，分隔符「${result.parsed.detectedDelimiter}」")
                    HorizontalDivider()
                    result.parsed.rows.filter { it.error != null }.forEach { row ->
                        Text(
                            "第 ${row.lineNumber} 行：${row.word.ifBlank { "(空)" }} — ${row.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "导入的释义、音标、例句与笔记均来自你的文件，可随时编辑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is VocabularyImportResultSummary.Rejected -> Text(result.message)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
