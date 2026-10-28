package com.zhixu.studyos.core.backup

import android.net.Uri
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.repository.FinanceRepository
import com.zhixu.studyos.core.data.repository.LearningRepository
import com.zhixu.studyos.core.data.repository.TaskRepository
import com.zhixu.studyos.core.finance.FinanceCsv
import kotlinx.coroutines.flow.first

/** 面向用户的 CSV 导出（任务、计时、习题、财务）。 */
interface DataExportService {
    suspend fun exportTasks(uri: Uri)
    suspend fun exportTimings(uri: Uri)
    suspend fun exportManualProblems(uri: Uri)
    suspend fun exportFinance(uri: Uri)
}

class OfflineDataExportService(
    private val taskRepository: TaskRepository,
    private val learningRepository: LearningRepository,
    private val financeRepository: FinanceRepository,
    private val fileStore: AppFileStore,
) : DataExportService {

    override suspend fun exportTasks(uri: Uri) {
        val tasks = taskRepository.observeAllTasks().first()
        fileStore.writeText(uri, CsvExport.tasks(tasks))
    }

    override suspend fun exportTimings(uri: Uri) {
        val snapshot = learningRepository.observeSnapshot().first()
        fileStore.writeText(uri, CsvExport.timings(snapshot.timings, snapshot.subjects))
    }

    override suspend fun exportManualProblems(uri: Uri) {
        val snapshot = learningRepository.observeSnapshot().first()
        fileStore.writeText(uri, CsvExport.manualProblems(snapshot.problems, snapshot.subjects))
    }

    override suspend fun exportFinance(uri: Uri) {
        val snapshot = financeRepository.getSnapshot()
        fileStore.writeText(uri, FinanceCsv.encode(snapshot))
    }
}
