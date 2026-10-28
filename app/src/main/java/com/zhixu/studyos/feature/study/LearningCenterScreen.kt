package com.zhixu.studyos.feature.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.designsystem.component.AppCard
import com.zhixu.studyos.core.bank.ProblemPageRenderer
import com.zhixu.studyos.core.designsystem.component.EmptyState
import com.zhixu.studyos.core.designsystem.component.PrimaryButton
import com.zhixu.studyos.feature.ai.AiPlannerScreen
import com.zhixu.studyos.feature.ai.AiPlannerViewModel
import com.zhixu.studyos.feature.bank.ProblemBankHost
import com.zhixu.studyos.feature.bank.ProblemBankViewModel
import com.zhixu.studyos.feature.bank.ProblemImportViewModel
import com.zhixu.studyos.feature.vocabulary.VocabularyScreen
import com.zhixu.studyos.feature.vocabulary.VocabularyViewModel
import com.zhixu.studyos.feature.knowledge.KnowledgeScreen
import com.zhixu.studyos.feature.knowledge.KnowledgeViewModel
import com.zhixu.studyos.feature.math.MathAssistantScreen
import com.zhixu.studyos.feature.math.MathAssistantViewModel
import com.zhixu.studyos.feature.report.ReportScreen
import com.zhixu.studyos.feature.report.ReportViewModel
import com.zhixu.studyos.feature.exam.ExamGoalScreen
import com.zhixu.studyos.feature.exam.ExamGoalViewModel

@Composable
fun LearningCenterScreen(
    taskViewModel: TaskViewModel,
    schedulingViewModel: SchedulingViewModel,
    learningViewModel: LearningViewModel,
    problemBankViewModel: ProblemBankViewModel,
    problemImportViewModel: ProblemImportViewModel,
    problemPageRenderer: ProblemPageRenderer?,
    aiPlannerViewModel: AiPlannerViewModel,
    vocabularyViewModel: VocabularyViewModel,
    knowledgeViewModel: KnowledgeViewModel,
    mathAssistantViewModel: MathAssistantViewModel,
    reportViewModel: ReportViewModel,
    examGoalViewModel: ExamGoalViewModel,
    initialSection: LearningSection? = null,
) {
    val learningState by learningViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sectionName by rememberSaveable {
        mutableStateOf((initialSection ?: LearningSection.TASKS).name)
    }
    val section = runCatching { LearningSection.valueOf(sectionName) }.getOrDefault(LearningSection.TASKS)

    LaunchedEffect(learningState.notice) {
        learningState.notice?.let {
            snackbarHostState.showSnackbar(it)
            learningViewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("screen_study"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                LearningSection.entries.forEach { value ->
                    FilterChip(
                        selected = section == value,
                        onClick = { sectionName = value.name },
                        label = { Text(value.label) },
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .testTag("section_${value.name}"),
                    )
                }
            }
            when (section) {
                LearningSection.TASKS -> StudyScreen(viewModel = taskViewModel)
                LearningSection.SCHEDULING -> SchedulingScreen(viewModel = schedulingViewModel)
                LearningSection.VOCABULARY -> VocabularyScreen(viewModel = vocabularyViewModel)
                LearningSection.KNOWLEDGE -> KnowledgeScreen(viewModel = knowledgeViewModel)
                LearningSection.MATH -> MathAssistantScreen(viewModel = mathAssistantViewModel)
                LearningSection.REPORT -> ReportScreen(viewModel = reportViewModel)
                LearningSection.EXAM_GOAL -> ExamGoalScreen(viewModel = examGoalViewModel)
                LearningSection.BANK -> ProblemBankHost(
                    bankViewModel = problemBankViewModel,
                    importViewModel = problemImportViewModel,
                    pageRenderer = problemPageRenderer,
                )
                LearningSection.AI -> {
                    val aiState by aiPlannerViewModel.uiState.collectAsStateWithLifecycle()
                    AiPlannerScreen(
                        state = aiState,
                        onRefresh = aiPlannerViewModel::refreshSnapshot,
                        onPrivacyChange = { titles, subjects, problems ->
                            aiPlannerViewModel.setPrivacy(titles, subjects, problems)
                        },
                        onRequestAnalysis = { aiPlannerViewModel.requestAnalysis(forceRefresh = true) },
                        onUseCached = aiPlannerViewModel::useCachedResult,
                        onToggleEstimate = aiPlannerViewModel::toggleTaskEstimate,
                        onConfirmApply = aiPlannerViewModel::confirmAndApply,
                        onDismissError = aiPlannerViewModel::dismissError,
                    )
                }
                else -> {
                    when {
                        learningState.isLoading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        learningState.errorMessage != null -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                        ) {
                            AppCard(modifier = Modifier.fillMaxWidth()) {
                                EmptyState(
                                    title = "学习中心暂时无法加载",
                                    message = learningState.errorMessage.orEmpty(),
                                    icon = Icons.Rounded.ErrorOutline,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                PrimaryButton(
                                    text = "重试",
                                    onClick = learningViewModel::retry,
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                )
                            }
                        }
                        else -> when (section) {
                            LearningSection.TIMER -> LearningTimerTab(learningState, learningViewModel)
                            LearningSection.PROBLEMS -> LearningProblemsTab(learningState, learningViewModel)
                            LearningSection.ANALYTICS -> LearningAnalyticsTab(learningState)
                            LearningSection.SELF_CONTROL -> LearningSelfControlTab(learningState, learningViewModel)
                            LearningSection.SCHEDULING, LearningSection.TASKS, LearningSection.BANK,
                            LearningSection.AI, LearningSection.VOCABULARY, LearningSection.KNOWLEDGE,
                            LearningSection.MATH, LearningSection.REPORT, LearningSection.EXAM_GOAL -> Unit
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

private val LearningSection.label: String
    get() = when (this) {
        LearningSection.TASKS -> "任务"
        LearningSection.SCHEDULING -> "排程"
        LearningSection.TIMER -> "计时"
        LearningSection.PROBLEMS -> "习题"
        LearningSection.BANK -> "题库"
        LearningSection.VOCABULARY -> "词汇"
        LearningSection.KNOWLEDGE -> "图谱"
        LearningSection.MATH -> "数学助手"
        LearningSection.AI -> "AI 规划"
        LearningSection.ANALYTICS -> "分析"
        LearningSection.REPORT -> "周报成长"
        LearningSection.EXAM_GOAL -> "考试目标"
        LearningSection.SELF_CONTROL -> "自控"
    }
