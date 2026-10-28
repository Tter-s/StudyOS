package com.zhixu.studyos.feature.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.ai.AiPlanConstraintChecker
import com.zhixu.studyos.core.designsystem.theme.StudyOSTheme
import com.zhixu.studyos.core.model.AiDayStat
import com.zhixu.studyos.core.model.AiGoalSummary
import com.zhixu.studyos.core.model.AiPlan
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiSchedulingSummary
import com.zhixu.studyos.core.model.AiStudySnapshot
import com.zhixu.studyos.core.model.AiSubjectStat
import com.zhixu.studyos.core.model.AiTaskEstimate
import com.zhixu.studyos.core.model.AiTaskStat
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiPlannerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 25)

    private fun snapshot() = AiStudySnapshot(
        generatedAt = Instant.parse("2026-10-25T08:00:00Z"),
        windowStart = LocalDate.of(2026, 10, 19),
        windowEnd = today,
        subjects = listOf(AiSubjectStat("数学分析", 120, 2, 3)),
        recentDays = (0 until 7).map { AiDayStat(LocalDate.of(2026, 10, 19).plusDays(it.toLong()), 20, 0) },
        upcomingTasks = listOf(
            AiTaskStat(42, "数学分析", "第三章习题", 90, null, "HIGH", 0, 1),
        ),
        difficultProblems = emptyList(),
        goals = AiGoalSummary(null, null, null),
        scheduling = AiSchedulingSummary(240, 480, 1320, 10),
    )

    private fun review() = AiPlanConstraintChecker.check(
        plan = AiPlan(
            summary = "本周学习稳定，建议保持数学分析的时间投入。",
            taskEstimates = listOf(AiTaskEstimate(42, 75, "题目较多")),
            restReminders = listOf("每 50 分钟休息 10 分钟"),
            weeklySuggestions = listOf("优先完成数学分析"),
        ),
        upcomingTasks = snapshot().upcomingTasks,
        knownSubjects = setOf("数学分析"),
        dailyLimitMinutes = 240,
        subjectNamesAvailable = true,
    )

    private fun configuredState(review: com.zhixu.studyos.core.model.AiPlanReview? = null) = AiPlannerUiState(
        loading = false,
        config = AiProviderConfig(
            enabled = true,
            baseUrl = "https://api.example.com/v1",
            model = "test-model",
        ),
        hasApiKey = true,
        snapshot = snapshot(),
        payloadPreview = "{}",
        payloadBytes = 2,
        privacySummary = "将发送学习统计摘要。",
        review = review,
        selectedTaskIds = review?.acceptedTaskEstimates?.map { it.taskId }?.toSet() ?: emptySet(),
    )

    @Test
    fun unconfiguredState_showsGuidanceAndDisablesGenerate() {
        var generated = false
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                AiPlannerScreen(
                    state = AiPlannerUiState(loading = false),
                    onRefresh = {},
                    onPrivacyChange = { _, _, _ -> },
                    onRequestAnalysis = { generated = true },
                    onUseCached = {},
                    onToggleEstimate = {},
                    onConfirmApply = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag("screen_ai").assertIsDisplayed()
        composeRule.onNodeWithText("AI 功能未启用", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("ai_generate_button").performClick()
        composeRule.runOnIdle { assertTrue(!generated) }
    }

    @Test
    fun configuredWithoutReview_showsGenerateButton() {
        var generated = false
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                AiPlannerScreen(
                    state = configuredState(),
                    onRefresh = {},
                    onPrivacyChange = { _, _, _ -> },
                    onRequestAnalysis = { generated = true },
                    onUseCached = {},
                    onToggleEstimate = {},
                    onConfirmApply = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag("ai_generate_button").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(generated) }
        composeRule.onNodeWithText("生成建议").assertExists()
    }

    @Test
    fun review_showsPlanSummaryAndConfirm() {
        var confirmed = false
        val state = configuredState(review())
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                AiPlannerScreen(
                    state = state,
                    onRefresh = {},
                    onPrivacyChange = { _, _, _ -> },
                    onRequestAnalysis = {},
                    onUseCached = {},
                    onToggleEstimate = {},
                    onConfirmApply = { confirmed = true },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("建议预览（尚未应用）").assertExists()
        composeRule.onNodeWithText("本周学习稳定", substring = true).assertExists()
        composeRule.onNodeWithText("第三章习题", substring = true).assertExists()
        composeRule.onNodeWithText("每 50 分钟休息 10 分钟", substring = true).assertExists()
        composeRule.onNodeWithTag("ai_confirm_button").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun privacySwitch_reportsChange() {
        var reported: Triple<Boolean, Boolean, Boolean>? = null
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                AiPlannerScreen(
                    state = configuredState(),
                    onRefresh = {},
                    onPrivacyChange = { a, b, c -> reported = Triple(a, b, c) },
                    onRequestAnalysis = {},
                    onUseCached = {},
                    onToggleEstimate = {},
                    onConfirmApply = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithTag("ai_privacy_task_titles").performClick()
        composeRule.runOnIdle {
            assertTrue(reported != null)
            assertTrue(reported!!.first.not())
        }
    }

    @Test
    fun error_isShownWithDismiss() {
        var dismissed = false
        val state = configuredState().copy(
            errorKind = com.zhixu.studyos.core.model.AiFailureKind.OFFLINE,
            errorMessage = "当前处于离线状态",
        )
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                AiPlannerScreen(
                    state = state,
                    onRefresh = {},
                    onPrivacyChange = { _, _, _ -> },
                    onRequestAnalysis = {},
                    onUseCached = {},
                    onToggleEstimate = {},
                    onConfirmApply = {},
                    onDismissError = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("当前处于离线状态").assertExists()
        composeRule.onNodeWithText("知道了").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }
}
