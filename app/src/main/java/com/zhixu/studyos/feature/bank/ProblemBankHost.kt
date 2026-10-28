package com.zhixu.studyos.feature.bank

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhixu.studyos.core.bank.ProblemPageRenderer

/**
 * 题库功能的宿主组合：列表 Tab、导入向导、题目详情、原始页面查看和生成任务对话框。
 * 学习页只需挂载这一个组件即可获得完整的 V0.8 题库能力。
 */
@Composable
fun ProblemBankHost(
    bankViewModel: ProblemBankViewModel,
    importViewModel: ProblemImportViewModel,
    pageRenderer: ProblemPageRenderer?,
) {
    val state by bankViewModel.uiState.collectAsStateWithLifecycle()
    val importState by importViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showImportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbarHostState.showSnackbar(message)
            bankViewModel.consumeNotice()
        }
    }
    LaunchedEffect(importState.notice) {
        importState.notice?.let { message ->
            snackbarHostState.showSnackbar(message)
            importViewModel.consumeNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ProblemBankTab(
            state = state,
            onStartImportPdf = {
                importViewModel.reset()
                showImportDialog = true
            },
            onStartImportImages = {
                importViewModel.reset()
                showImportDialog = true
            },
            onOpenProblem = bankViewModel::openProblem,
            onFilterDocument = bankViewModel::setFilterDocument,
            onFilterChapter = bankViewModel::setFilterChapter,
            onFilterCompletion = bankViewModel::setFilterCompletion,
            onFilterOnlyUnfinished = bankViewModel::setFilterOnlyUnfinished,
            onFilterSearch = bankViewModel::setFilterSearch,
            onClearFilter = bankViewModel::clearFilter,
            onDismissError = bankViewModel::dismissError,
            onLoadMore = bankViewModel::loadMore,
            onPrepareTaskPlan = { problemsPerDay ->
                bankViewModel.prepareTaskPlan(problemsPerDay, java.time.LocalDate.now())
            },
        )

        state.detailProblem?.let { problem ->
            val document = state.documents.firstOrNull { it.id == problem.documentId }
            ProblemBankDetailDialog(
                problem = problem,
                document = document,
                pageRenderer = pageRenderer,
                errorMessage = state.errorMessage,
                onDismissError = bankViewModel::dismissError,
                onDismiss = bankViewModel::closeProblemDetail,
                onSave = bankViewModel::saveProblemEdit,
                onDelete = bankViewModel::deleteProblem,
            )
        }

        if (state.taskPlan != null) {
            GenerateTasksDialog(
                plan = state.taskPlan,
                busy = state.taskPlanBusy,
                onPrepare = { perDay, startDate -> bankViewModel.prepareTaskPlan(perDay, startDate) },
                onConfirm = bankViewModel::confirmGenerateTasks,
                onDismiss = bankViewModel::dismissTaskPlan,
            )
        }

        if (showImportDialog) {
            ProblemImportDialog(
                viewModel = importViewModel,
                onDismiss = {
                    importViewModel.reset()
                    showImportDialog = false
                },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
