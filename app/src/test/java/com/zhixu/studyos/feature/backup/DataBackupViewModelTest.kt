package com.zhixu.studyos.feature.backup

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.backup.BackupManifest
import com.zhixu.studyos.core.backup.BackupPreview
import com.zhixu.studyos.core.backup.BackupService
import com.zhixu.studyos.core.backup.BackupSummary
import com.zhixu.studyos.core.backup.CurrentDataSummary
import com.zhixu.studyos.core.backup.DataExportService
import com.zhixu.studyos.core.backup.RestoreResult
import com.zhixu.studyos.core.designsystem.theme.StudyOSTheme
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DataBackupViewModelTest {

    private val uri: Uri = Uri.parse("file:///tmp/backup.zip")

    private val manifest = BackupManifest(
        formatVersion = 1,
        databaseVersion = 8,
        appVersion = "1.0.0",
        createdAt = Instant.parse("2026-10-25T12:00:00Z"),
        timezone = "Asia/Shanghai",
        tableCounts = mapOf("study_tasks" to 3, "finance_transactions" to 2),
        documentCount = 1,
        documentFileCount = 2,
        documentTotalBytes = 1024,
        excluded = listOf("ai_analysis_cache"),
    )

    private class FakeBackupService(
        var restoreFailure: Throwable? = null,
        var inspectFailure: Throwable? = null,
    ) : BackupService {
        var summaryCalls = 0
        var restoreCalls = 0
        var createdUri: Uri? = null

        override suspend fun currentDataSummary(): CurrentDataSummary {
            summaryCalls += 1
            return CurrentDataSummary(
                tableCounts = mapOf("study_tasks" to 1, "finances" to 0),
                documentCount = 0,
                documentFileCount = 0,
                documentTotalBytes = 0,
            )
        }

        override suspend fun createBackup(uri: Uri): BackupSummary {
            createdUri = uri
            return BackupSummary(
                fileName = "backup.zip",
                sizeBytes = 2048,
                manifest = BackupManifest(
                    formatVersion = 1,
                    databaseVersion = 8,
                    appVersion = "1.0.0",
                    createdAt = Instant.parse("2026-10-25T12:00:00Z"),
                    timezone = "UTC",
                    tableCounts = mapOf("study_tasks" to 1),
                    documentCount = 0,
                    documentFileCount = 0,
                    documentTotalBytes = 0,
                    excluded = emptyList(),
                ),
            )
        }

        override suspend fun inspectBackup(uri: Uri): BackupPreview {
            inspectFailure?.let { throw it }
            return BackupPreview(
                manifest = BackupManifest(
                    formatVersion = 1,
                    databaseVersion = 8,
                    appVersion = "1.0.0",
                    createdAt = Instant.parse("2026-10-25T12:00:00Z"),
                    timezone = "UTC",
                    tableCounts = mapOf("study_tasks" to 3),
                    documentCount = 0,
                    documentFileCount = 0,
                    documentTotalBytes = 0,
                    excluded = emptyList(),
                ),
                sourceName = "backup.zip",
                sizeBytes = 2048,
                warnings = listOf("恢复会替换当前全部业务数据。"),
                documentPaths = emptySet(),
                referencedDocumentPaths = emptyList(),
            )
        }

        override suspend fun restoreBackup(uri: Uri): RestoreResult {
            restoreCalls += 1
            restoreFailure?.let { throw it }
            return RestoreResult(
                restoredCounts = mapOf("study_tasks" to 3),
                restoredDocumentFiles = 0,
                manifestCreatedAt = Instant.parse("2026-10-25T12:00:00Z"),
            )
        }
    }

    private class FakeExportService : DataExportService {
        val exported = mutableListOf<ExportKind>()

        override suspend fun exportTasks(uri: Uri) {
            exported += ExportKind.TASKS
        }

        override suspend fun exportTimings(uri: Uri) {
            exported += ExportKind.TIMINGS
        }

        override suspend fun exportManualProblems(uri: Uri) {
            exported += ExportKind.PROBLEMS
        }

        override suspend fun exportFinance(uri: Uri) {
            exported += ExportKind.FINANCE
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsCurrentSummary() = runTest {
        val service = FakeBackupService()
        val vm = DataBackupViewModel(service, FakeExportService())
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.summary)
        assertEquals(1, service.summaryCalls)
    }

    @Test
    fun createBackup_setsSummaryAndNotice() = runTest {
        val service = FakeBackupService()
        val vm = DataBackupViewModel(service, FakeExportService())
        advanceUntilIdle()

        vm.createBackup(uri)
        advanceUntilIdle()

        assertEquals(uri, service.createdUri)
        assertNotNull(vm.uiState.value.lastBackup)
        assertTrue(vm.uiState.value.notice!!.contains("备份已保存"))
    }

    @Test
    fun inspect_setsPreview_andConfirmRestoreClearsIt() = runTest {
        val service = FakeBackupService()
        val vm = DataBackupViewModel(service, FakeExportService())
        advanceUntilIdle()

        vm.inspectBackup(uri)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.preview)
        assertTrue(vm.uiState.value.preview!!.warnings.isNotEmpty())

        vm.confirmRestore()
        advanceUntilIdle()

        assertEquals(1, service.restoreCalls)
        assertNull(vm.uiState.value.preview)
        assertNotNull(vm.uiState.value.lastRestore)
        assertTrue(vm.uiState.value.notice!!.contains("恢复完成"))
    }

    @Test
    fun inspectFailure_setsErrorWithoutPreview() = runTest {
        val service = FakeBackupService(inspectFailure = IllegalStateException("不是有效备份"))
        val vm = DataBackupViewModel(service, FakeExportService())
        advanceUntilIdle()

        vm.inspectBackup(uri)
        advanceUntilIdle()

        assertNull(vm.uiState.value.preview)
        assertTrue(vm.uiState.value.errorMessage!!.contains("备份文件校验失败"))
    }

    @Test
    fun restoreFailure_keepsPreviewAndShowsRollbackMessage() = runTest {
        val service = FakeBackupService(restoreFailure = IllegalStateException("外键失败"))
        val vm = DataBackupViewModel(service, FakeExportService())
        advanceUntilIdle()
        vm.inspectBackup(uri)
        advanceUntilIdle()

        vm.confirmRestore()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.preview)
        assertTrue(vm.uiState.value.errorMessage!!.contains("已回滚"))
    }

    @Test
    fun export_usesRequestedKind() = runTest {
        val exportService = FakeExportService()
        val vm = DataBackupViewModel(FakeBackupService(), exportService)
        advanceUntilIdle()

        vm.export(ExportKind.TIMINGS, uri)
        advanceUntilIdle()

        assertEquals(listOf(ExportKind.TIMINGS), exportService.exported)
        assertTrue(vm.uiState.value.notice!!.contains("学习计时"))
    }

    @Test
    fun cancelPreview_clearsPendingRestore() = runTest {
        val service = FakeBackupService()
        val vm = DataBackupViewModel(service, FakeExportService())
        advanceUntilIdle()
        vm.inspectBackup(uri)
        advanceUntilIdle()

        vm.cancelPreview()
        vm.confirmRestore()
        advanceUntilIdle()

        assertNull(vm.uiState.value.preview)
        assertEquals(0, service.restoreCalls)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataBackupDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dialog_showsCurrentDataAndActions() {
        val vm = DataBackupViewModel(
            object : BackupService {
                override suspend fun currentDataSummary() = CurrentDataSummary(
                    tableCounts = mapOf("study_tasks" to 2, "problem_bank_entries" to 1),
                    documentCount = 0,
                    documentFileCount = 0,
                    documentTotalBytes = 0,
                )

                override suspend fun createBackup(uri: Uri) = throw UnsupportedOperationException()
                override suspend fun inspectBackup(uri: Uri) = throw UnsupportedOperationException()
                override suspend fun restoreBackup(uri: Uri) = throw UnsupportedOperationException()
            },
            object : DataExportService {
                override suspend fun exportTasks(uri: Uri) = Unit
                override suspend fun exportTimings(uri: Uri) = Unit
                override suspend fun exportManualProblems(uri: Uri) = Unit
                override suspend fun exportFinance(uri: Uri) = Unit
            },
        )
        composeRule.setContent {
            StudyOSTheme(darkTheme = false) {
                DataBackupDialog(viewModel = vm, onDismiss = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag("backup_create").assertExists() }.isSuccess
        }
        composeRule.onNodeWithTag("backup_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("backup_create").assertIsDisplayed()
        composeRule.onNodeWithTag("backup_restore").assertIsDisplayed()
        composeRule.onNodeWithText("任务 2", substring = true).assertExists()
    }
}
