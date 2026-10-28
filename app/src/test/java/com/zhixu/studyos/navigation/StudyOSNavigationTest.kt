package com.zhixu.studyos.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudyOSNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigation_opensAllFivePages() {
        composeRule.onNodeWithTag("screen_home").assertIsDisplayed()

        val destinations = listOf(
            "schedule" to "screen_schedule",
            "records" to "screen_records",
            "finance" to "screen_finance",
            "profile" to "screen_profile",
            "home" to "screen_home",
        )
        destinations.forEach { (route, screenTag) ->
            composeRule.onNodeWithTag("nav_$route").performClick()
            composeRule.onNodeWithTag(screenTag).assertIsDisplayed()
        }
    }

    @Test
    fun systemBack_fromTopLevelDestination_returnsHome() {
        composeRule.onNodeWithTag("nav_finance").performClick()
        composeRule.onNodeWithTag("screen_finance").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun profile_canSelectDarkTheme() {
        composeRule.onNodeWithTag("nav_profile").performClick()
        composeRule.onNodeWithTag("theme_dark").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("theme_dark").assertIsSelected()
            }.isSuccess
        }
        composeRule.onNodeWithTag("theme_dark").assertIsSelected()
    }

    @Test
    fun schedule_canOpenImageImportDialog() {
        composeRule.onNodeWithTag("nav_schedule").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag("schedule_more").assertExists() }.isSuccess
        }

        composeRule.onNodeWithTag("schedule_more").performClick()
        composeRule.onNodeWithTag("import_schedule").performClick()

        composeRule.onNodeWithTag("import_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("import_pick_image").assertIsDisplayed()
        composeRule.onNodeWithText("本地识别，不上传图片").assertExists()
    }

    @Test
    fun today_canAddTask() {
        val title = "V3.1 导航测试待办"
        composeRule.onNodeWithTag("today_add_task").performClick()
        composeRule.onNodeWithTag("task_title_input").performTextInput(title)
        composeRule.onNodeWithText("保存").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { composeRule.onNodeWithText(title).assertExists() }.isSuccess
        }
        composeRule.onNodeWithText(title).assertExists()
        composeRule.onNodeWithText("保存").assertDoesNotExist()
    }

    @Test
    fun today_canOpenTomorrow() {
        composeRule.onNodeWithTag("screen_home").performScrollToNode(hasTestTag("today_tomorrow"))
        composeRule.onNodeWithTag("today_tomorrow").performClick()
        composeRule.onNodeWithTag("screen_day_tasks").assertIsDisplayed()
        composeRule.onNodeWithText("明日待办").assertExists()
    }

    @Test
    fun records_openSummarySleepEntertainmentAndHistory() {
        composeRule.onNodeWithTag("nav_records").performClick()

        composeRule.onNodeWithTag("records_summary").performClick()
        composeRule.onNodeWithTag("screen_daily_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("summary_input").performScrollTo().assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag("records_sleep").performClick()
        composeRule.onNodeWithTag("screen_sleep").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_add").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag("records_entertainment").performClick()
        composeRule.onNodeWithTag("screen_entertainment").assertIsDisplayed()
        composeRule.onNodeWithTag("entertainment_save").assertIsDisplayed()
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag("records_history").performClick()
        composeRule.onNodeWithTag("screen_history").assertIsDisplayed()
    }

    @Test
    fun records_canSaveDailySummary() {
        val text = "今天完成了 V3.1 的记录页面"
        composeRule.onNodeWithTag("nav_records").performClick()
        composeRule.onNodeWithTag("records_summary").performClick()
        composeRule.onNodeWithTag("summary_input").performScrollTo().performTextInput(text)
        composeRule.onNodeWithTag("summary_save").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("summary_notice").performScrollTo().assertExists()
            }.isSuccess
        }
        composeRule.onNodeWithText("已保存").assertExists()
    }

    @Test
    fun finance_canOpenEditor() {
        composeRule.onNodeWithTag("nav_finance").performClick()
        composeRule.onNodeWithTag("screen_finance").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("finance_add").assertIsEnabled()
            }.isSuccess
        }
        composeRule.onNodeWithTag("finance_add").performClick()
        composeRule.onNodeWithText("新增记录").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
    }
}
