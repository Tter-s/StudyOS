package com.zhixu.studyos.feature.bank

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.designsystem.theme.StudyOSTheme
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemCompletionStatus
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.core.model.ProblemKnowledgePoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProblemBankTabTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun document() = ProblemDocument(
        id = 1L,
        displayName = "数学分析习题册",
        type = ProblemDocumentType.PDF,
        relativePath = "problem_bank_documents/a.pdf",
        originalName = "a.pdf",
        sizeBytes = 2048,
        contentSha256 = "sha",
        pageCount = 3,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    private fun problem(number: String) = ProblemBankEntry(
        id = number.toLong(),
        documentId = 1L,
        pageIndex = 0,
        courseId = null,
        courseName = "数学分析",
        textbook = "数学分析习题册",
        chapter = "第一章",
        problemNumber = number,
        statement = "求函数的极限并证明连续性。",
        knowledgePoints = listOf(
            com.zhixu.studyos.core.model.ProblemKnowledgePoint(
                name = "知识点$number",
                source = com.zhixu.studyos.core.model.ProblemAnalysisSource.HEURISTIC,
            ),
        ),
        estimatedDifficulty = 3,
        estimatedMinutes = 20,
        completionStatus = ProblemCompletionStatus.NEW,
    )

    private fun state(
        problems: List<ProblemBankEntry>,
        documents: List<ProblemDocument> = listOf(document()),
    ) = ProblemBankUiState(
        isLoading = false,
        problems = problems,
        documents = documents,
        documentProblemCounts = mapOf(1L to problems.size),
        chapters = problems.map { it.chapter }.distinct(),
        totalCount = problems.size,
        completedCount = problems.count { it.completionStatus == ProblemCompletionStatus.COMPLETED },
    )

    @Test
    fun emptyBank_showsImportGuidance() {
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                ProblemBankTab(
                    state = ProblemBankUiState(isLoading = false),
                    onStartImportPdf = {},
                    onStartImportImages = {},
                    onOpenProblem = {},
                    onFilterDocument = {},
                    onFilterChapter = {},
                    onFilterCompletion = {},
                    onFilterOnlyUnfinished = {},
                    onFilterSearch = {},
                    onClearFilter = {},
                    onDismissError = {},
                    onLoadMore = {},
                    onPrepareTaskPlan = {},
                )
            }
        }

        composeRule.onNodeWithText("还没有导入习题册").assertIsDisplayed()
    }

    @Test
    fun problemList_showsEntriesAndFilters() {
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                ProblemBankTab(
                    state = state(listOf(problem("1"), problem("2"))),
                    onStartImportPdf = {},
                    onStartImportImages = {},
                    onOpenProblem = {},
                    onFilterDocument = {},
                    onFilterChapter = {},
                    onFilterCompletion = {},
                    onFilterOnlyUnfinished = {},
                    onFilterSearch = {},
                    onClearFilter = {},
                    onDismissError = {},
                    onLoadMore = {},
                    onPrepareTaskPlan = {},
                )
            }
        }

        composeRule.onNodeWithTag("bank_list").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 题", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("知识点1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("全部章节").assertIsDisplayed()
    }

    @Test
    fun searchField_updatesFilterThroughCallback() {
        var searchText = ""
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                ProblemBankTab(
                    state = state(listOf(problem("1"))),
                    onStartImportPdf = {},
                    onStartImportImages = {},
                    onOpenProblem = {},
                    onFilterDocument = {},
                    onFilterChapter = {},
                    onFilterCompletion = {},
                    onFilterOnlyUnfinished = {},
                    onFilterSearch = { searchText = it },
                    onClearFilter = {},
                    onDismissError = {},
                    onLoadMore = {},
                    onPrepareTaskPlan = {},
                )
            }
        }

        composeRule.onNodeWithTag("bank_search").performTextInput("极限")

        composeRule.runOnIdle { assertEquals("极限", searchText) }
    }

    @Test
    fun generateTasksButton_callsPrepare() {
        var prepared = false
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                ProblemBankTab(
                    state = state(listOf(problem("1"))),
                    onStartImportPdf = {},
                    onStartImportImages = {},
                    onOpenProblem = {},
                    onFilterDocument = {},
                    onFilterChapter = {},
                    onFilterCompletion = {},
                    onFilterOnlyUnfinished = {},
                    onFilterSearch = {},
                    onClearFilter = {},
                    onDismissError = {},
                    onLoadMore = {},
                    onPrepareTaskPlan = { prepared = true },
                )
            }
        }

        composeRule.onNodeWithText("生成每日任务").performClick()
        composeRule.runOnIdle { assertEquals(true, prepared) }
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
