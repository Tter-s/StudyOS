package com.zhixu.studyos.feature.schedule

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.designsystem.theme.StudyOSTheme
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.PeriodGrid
import com.zhixu.studyos.core.importer.ScheduleImportDraft
import com.zhixu.studyos.core.importer.ScheduleImportDraftAnalysis
import com.zhixu.studyos.core.importer.ScheduleImportField
import com.zhixu.studyos.core.model.WeekParity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleImportPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewShowsReadyPendingAndImportCount() {
        val ready = ScheduleImportDraft(
            localId = "ready",
            name = "数学分析",
            teacher = "王老师",
            classroom = "A101",
            dayOfWeek = 1,
            periodStart = 1,
            periodEnd = 2,
            startWeek = 1,
            endWeek = 16,
            weekParity = WeekParity.ALL,
            colorArgb = 0xFF64B5F6.toInt(),
        )
        val pending = ScheduleImportDraft(
            localId = "pending",
            name = "高等代数",
            classroom = "",
            dayOfWeek = 2,
            periodStart = 1,
            periodEnd = 2,
            startWeek = 1,
            endWeek = 16,
            weekParity = WeekParity.ALL,
            colorArgb = 0xFF81C784.toInt(),
            needsReview = setOf(ScheduleImportField.CLASSROOM),
        )
        val state = ScheduleImportUiState(
            stage = ScheduleImportStage.PREVIEW,
            drafts = listOf(ready, pending),
            analysis = mapOf(
                ready.localId to ScheduleImportDraftAnalysis(
                    localId = ready.localId,
                    validationError = null,
                    needsReview = emptySet(),
                    duplicates = emptyList(),
                    conflicts = emptyList(),
                ),
                pending.localId to ScheduleImportDraftAnalysis(
                    localId = pending.localId,
                    validationError = "教室待确认",
                    needsReview = setOf(ScheduleImportField.CLASSROOM),
                    duplicates = emptyList(),
                    conflicts = emptyList(),
                ),
            ),
            selectedIds = setOf(ready.localId),
            grid = PeriodGrid.commonDefault(),
            gridConfirmed = true,
            hasSemester = true,
            isSnapshotAvailable = true,
            recognizedCount = 2,
        )

        composeRule.setContent {
            StudyOSTheme {
                ScheduleImportPreviewContent(
                    state = state,
                    onEditDraft = {},
                    onToggleDraft = { _, _ -> },
                    onOpenGrid = {},
                    onOpenSemester = {},
                    onRetrySnapshot = {},
                    onImport = {},
                    onRequestClose = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithTag("import_preview_table").assertExists()
        composeRule.onNodeWithText("数学分析").assertExists()
        composeRule.onNodeWithText("高等代数").assertExists()
        composeRule.onNodeWithText("信息不完整").assertExists()
        composeRule.onNodeWithText("已选择 1 门").assertIsDisplayed()
        composeRule.onNodeWithTag("import_submit").assertIsDisplayed()
    }

    @Test
    fun adjustScreenComposesCropBoxAndActions() {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)

        composeRule.setContent {
            StudyOSTheme {
                ScheduleImportAdjustContent(
                    bitmap = bitmap,
                    crop = NormalizedCrop.Full,
                    hasSemester = true,
                    warnings = emptyList(),
                    onCropChange = {},
                    onRotateLeft = {},
                    onRotateRight = {},
                    onResetCrop = {},
                    onPickAnother = {},
                    onRecognize = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithText("开始识别").assertIsDisplayed()
        composeRule.onNodeWithText("重置裁剪").assertIsDisplayed()
        composeRule.onNodeWithText("拖动四角调整裁剪范围，并保留完整的星期表头和节次列。").assertIsDisplayed()
    }
}
