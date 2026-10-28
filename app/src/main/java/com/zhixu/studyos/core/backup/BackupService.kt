package com.zhixu.studyos.core.backup

import android.net.Uri
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import java.io.File
import java.time.Clock
import java.time.ZoneId

/** 备份页使用的门面：UI 只依赖该接口，便于测试替换。 */
interface BackupService {
    suspend fun currentDataSummary(): CurrentDataSummary
    suspend fun createBackup(uri: Uri): BackupSummary
    suspend fun inspectBackup(uri: Uri): BackupPreview
    suspend fun restoreBackup(uri: Uri): RestoreResult
}

class AppBackupService(
    private val database: StudyOSDatabase,
    private val filesDir: File,
    private val fileStore: AppFileStore,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val appVersion: String,
) : BackupService {

    private val creator = BackupCreator(
        database = database,
        filesDir = filesDir,
        clock = clock,
        zoneId = zoneId,
        appVersion = appVersion,
    )

    private val restorer = BackupRestorer(
        database = database,
        filesDir = filesDir,
        clock = clock,
    )

    override suspend fun currentDataSummary(): CurrentDataSummary = creator.currentSummary()

    override suspend fun createBackup(uri: Uri): BackupSummary {
        val (name, _) = fileStore.describe(uri)
        val summary = fileStore.writeBinary(uri) { output -> creator.write(output) }
        return summary.copy(fileName = name)
    }

    override suspend fun inspectBackup(uri: Uri): BackupPreview {
        val (name, size) = fileStore.describe(uri)
        return fileStore.readBinary(uri) { input -> restorer.inspect(input, name, size) }
    }

    override suspend fun restoreBackup(uri: Uri): RestoreResult =
        fileStore.readBinary(uri) { input -> restorer.restore(input) }
}
