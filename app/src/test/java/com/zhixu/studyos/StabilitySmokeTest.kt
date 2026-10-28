package com.zhixu.studyos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.designsystem.theme.StudyOSTheme
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentType
import com.zhixu.studyos.feature.ai.AiPlannerScreen
import com.zhixu.studyos.feature.ai.AiPlannerUiState
import com.zhixu.studyos.feature.bank.ProblemBankTab
import com.zhixu.studyos.feature.bank.ProblemBankUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 稳定性冒烟：横屏 + 深色主题下关键页面可以渲染且不崩溃。
 * 这不是完整的视觉验收（真机仍需人工检查），用于防止布局硬编码/颜色假设导致的回归。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StabilitySmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun problem(number: String) = ProblemBankEntry(
        id = number.toLong(),
        documentId = 1L,
        pageIndex = 0,
        courseId = null,
        courseName = "数学分析",
        textbook = "数分习题册",
        chapter = "第一章",
        problemNumber = number,
        statement = "横屏深色模式下的题库条目 $number",
        estimatedMinutes = 10,
    )

    private fun document() = ProblemDocument(
        id = 1L,
        displayName = "数分习题册",
        type = ProblemDocumentType.PDF,
        relativePath = "problem_bank_documents/a.pdf",
        originalName = "a.pdf",
        sizeBytes = 1,
        contentSha256 = "sha",
        pageCount = 1,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    @Test
    fun problemBank_rendersInLandscapeDarkTheme() {
        composeRule.setContent {
            StudyOSTheme(darkTheme = true) {
                ProblemBankTab(
                    state = ProblemBankUiState(
                        isLoading = false,
                        problems = listOf(problem("1"), problem("2")),
                        totalCount = 2,
                        documents = listOf(document()),
                        documentProblemCounts = mapOf(1L to 2),
                        chapters = listOf("第一章"),
                    ),
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
        composeRule.onNodeWithText("第 1 题", substring = true).assertExists()
    }

    @Test
    fun aiPlanner_rendersInLandscapeDarkTheme() {
        composeRule.setContent {
            StudyOSTheme(darkTheme = true) {
                AiPlannerScreen(
                    state = AiPlannerUiState(
                        loading = false,
                        config = AiProviderConfig(),
                    ),
                    onRefresh = {},
                    onPrivacyChange = { _, _, _ -> },
                    onRequestAnalysis = {},
                    onUseCached = {},
                    onToggleEstimate = {},
                    onConfirmApply = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag("screen_ai").assertExists()
        composeRule.onNodeWithText("AI 学习规划").assertExists()
    }
}
