package com.zhixu.studyos.core.backup

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhixu.studyos.core.data.local.DailyLogBackfill
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.sync.FileSyncWatermarkStore
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * 写入完整备份 ZIP：
 * - `manifest.json`：格式/数据库/应用版本、创建时间、各表行数、原始文件数量与大小、排除项；
 * - `data.json`：全部业务表的 SQL 级 JSON 快照（不含 AI 表）；
 * - `documents/<relativePath>`：题库原始 PDF/图片文件，保持相对路径以便恢复后「查看原始页面」可用。
 *
 * 备份读取使用单个数据库事务，保证表之间一致；文件在事务外按数据库中的路径逐项写入。
 */
class BackupCreator(
    private val database: StudyOSDatabase,
    private val filesDir: File,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val appVersion: String,
    private val codec: BackupCodec = BackupCodec(),
) {

    suspend fun write(output: OutputStream): BackupSummary {
        val (dataJson, documentPaths, counts) = database.withTransaction {
            val db = database.openHelper.writableDatabase
            val data = codec.exportTables(db)
            val paths = readDocumentPaths(db)
            Triple(data, paths, codec.currentCounts(db))
        }
        val dataBytes = dataJson.toString().toByteArray(Charsets.UTF_8)
        if (dataBytes.size > MAX_DATA_BYTES) {
            throw BackupValidationException(
                "当前数据量超过备份上限（200 MB），请先清理不再需要的题库或学习记录",
            )
        }
        val files = collectDocumentFiles(documentPaths)
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            databaseVersion = dataJson.getInt("databaseVersion"),
            appVersion = appVersion,
            createdAt = Instant.now(clock),
            timezone = zoneId.id,
            tableCounts = counts,
            documentCount = documentPaths.size,
            documentFileCount = files.size,
            documentTotalBytes = files.sumOf { it.second.length() },
            excluded = BackupTables.excluded + listOf("api_keys", "datastore_preferences"),
        )
        var writtenBytes = 0L
        ZipOutputStream(output).use { zip ->
            writtenBytes += writeEntry(zip, MANIFEST_ENTRY, BackupManifestCodec.encode(manifest).toByteArray())
            writtenBytes += writeEntry(zip, DATA_ENTRY, dataBytes)
            files.forEach { (entryName, file) ->
                val entry = ZipEntry(entryName)
                zip.putNextEntry(entry)
                file.inputStream().buffered().use { input ->
                    writtenBytes += input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
        return BackupSummary(
            fileName = defaultFileName(manifest.createdAt, zoneId),
            sizeBytes = writtenBytes,
            manifest = manifest,
        )
    }

    /** 当前数据的摘要，用于备份页展示（不包含 AI 数据）。 */
    suspend fun currentSummary(): CurrentDataSummary {
        val (counts, paths) = database.withTransaction {
            val db = database.openHelper.writableDatabase
            codec.currentCounts(db) to readDocumentPaths(db)
        }
        val files = collectDocumentFiles(paths)
        return CurrentDataSummary(
            tableCounts = counts,
            documentCount = paths.size,
            documentFileCount = files.size,
            documentTotalBytes = files.sumOf { it.second.length() },
        )
    }

    private fun readDocumentPaths(db: androidx.sqlite.db.SupportSQLiteDatabase): List<String> {
        val paths = mutableListOf<String>()
        db.query("SELECT relativePath FROM problem_documents ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)?.trim().orEmpty()
                if (path.isNotEmpty()) paths += path
            }
        }
        return paths
    }

    /** 返回 zip 内条目名与文件；目录来源（图片导入）递归收集。 */
    private fun collectDocumentFiles(paths: List<String>): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        val root = filesDir.canonicalFile
        paths.forEach { relativePath ->
            val file = File(filesDir, relativePath).canonicalFile
            if (!file.path.startsWith(root.path)) return@forEach
            if (file.isDirectory) {
                file.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.path }
                    .forEach { child ->
                        val entryName = DOCUMENTS_PREFIX + child.toRelativeString(filesDir).replace('\\', '/')
                        result += entryName to child
                    }
            } else if (file.isFile) {
                result += DOCUMENTS_PREFIX + file.toRelativeString(filesDir).replace('\\', '/') to file
            }
        }
        return result
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray): Long {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        return bytes.size.toLong()
    }

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATA_ENTRY = "data.json"
        const val DOCUMENTS_PREFIX = "documents/"
        const val MAX_DATA_BYTES = 200L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 300L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

        fun defaultFileName(createdAt: Instant, zoneId: ZoneId): String {
            // 使用 ZoneId 转换，兼容 minSdk 26（LocalDate.ofInstant 需要 API 34）
            val zoned = createdAt.atZone(zoneId)
            val date = zoned.toLocalDate()
            val time = zoned.toLocalTime()
            return "studyos-backup-$date-%02d%02d.zip".format(time.hour, time.minute)
        }
    }
}

/**
 * 读取并校验备份 ZIP，执行恢复。
 *
 * 安全性：
 * - 格式版本/数据库版本/表结构先校验，恢复前预览不修改数据；
 * - zip 条目路径必须位于 `documents/problem_bank_documents/` 内，拒绝绝对路径与 `..`；
 * - 数据库恢复在单个 Room 事务内完成，行数核对失败即回滚；
 * - 原始文件先解压到暂存目录，数据库事务成功后才替换正式目录；数据库失败则把旧目录换回，
 *   因此恢复失败不会丢失当前数据。
 */
class BackupRestorer(
    private val database: StudyOSDatabase,
    private val filesDir: File,
    private val clock: Clock,
    private val codec: BackupCodec = BackupCodec(),
) {

    private data class LoadedArchive(
        val manifest: BackupManifest,
        val data: JSONObject,
        val documentEntries: List<String>,
        val warnings: List<String>,
    )

    suspend fun inspect(input: InputStream, sourceName: String, sizeBytes: Long): BackupPreview {
        val archive = readArchive(input, stagingRoot = null)
        val report = database.withTransaction {
            codec.validate(database.openHelper.writableDatabase, archive.data)
        }
        val referenced = referencedDocumentPaths(archive.data)
        verifyReferencedDocuments(referenced, archive.documentEntries)
        val warnings = buildList {
            addAll(archive.warnings)
            addAll(report.warnings)
            add("恢复会替换当前全部业务数据（待办、总结、睡眠、娱乐、课表、财务与旧版本归档），此操作不可撤销。")
            add("设置与密钥不在备份中，恢复后保持不变。")
            if (archive.manifest.documentCount == 0) {
                add("备份不包含题库原始文件；恢复后现有原始页面文件会被清除。")
            }
            if (archive.manifest.tableCounts != report.counts) {
                add("备份清单中的行数与数据内容不一致，将以数据内容为准。")
            }
        }
        return BackupPreview(
            manifest = archive.manifest,
            sourceName = sourceName,
            sizeBytes = sizeBytes,
            warnings = warnings.distinct(),
            documentPaths = archive.documentEntries.toSet(),
            referencedDocumentPaths = referenced,
        )
    }

    suspend fun restore(input: InputStream): RestoreResult {
        // 上一次恢复如果在替换文件阶段被系统杀死，先把它处理完，避免新旧目录叠加。
        runCatching {
            BackupRecovery.recover(
                filesDir = filesDir,
                isDatabaseCommitted = { token -> isRestoreCommitted(database, token) },
                clearDatabaseMarker = { token -> clearRestoreMarker(database, token) },
            )
        }

        val stagingId = UUID.randomUUID().toString().take(8)
        val restoreToken = UUID.randomUUID().toString()
        val stagingRoot = File(filesDir, ".restore_staging_$stagingId")
        if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        if (!stagingRoot.mkdirs()) {
            throw BackupValidationException("无法创建恢复暂存目录，请检查存储空间")
        }
        var databaseCommitted = false
        try {
            val archive = readArchive(input, stagingRoot = stagingRoot)
            val report = database.withTransaction {
                codec.validate(database.openHelper.writableDatabase, archive.data)
            }
            val referenced = referencedDocumentPaths(archive.data)
            verifyReferencedDocuments(referenced, archive.documentEntries)

            val stagedDocuments = File(stagingRoot, BackupTables.DOCUMENTS_ROOT)
            if (!stagedDocuments.exists() && !stagedDocuments.mkdirs()) {
                throw BackupValidationException("无法准备题库原始文件目录")
            }

            // 数据库提交前就写 journal；同时在同一事务内写入恢复哨兵。
            // 进程若在提交后、替换文件前被杀，启动时通过哨兵判断数据库确实已提交，
            // 不会再把“已回滚的数据库”和“新文件”拼在一起。
            val liveRoot = File(filesDir, BackupTables.DOCUMENTS_ROOT)
            val backupRoot = File(filesDir, ".restore_backup_$stagingId")
            BackupRecovery.write(
                filesDir = filesDir,
                journal = BackupRecovery.Journal(
                    stagingName = stagingRoot.name,
                    backupName = backupRoot.name,
                    token = restoreToken,
                ),
            )

            // 先完成数据库导入与提交。数据库失败时还没有动过任何正式文件，
            // 因此不会出现“新文件 + 旧数据库”的半恢复状态。
            database.withTransaction {
                val db = database.openHelper.writableDatabase
                // 旧备份不含 globalId；先移除唯一索引，回填后再重建，避免空字符串冲突。
                db.execSQL("DROP INDEX IF EXISTS `index_study_tasks_globalId`")
                codec.importTables(db, archive.data, report)
                db.execSQL("UPDATE `study_tasks` SET `globalId` = 'task-' || `id` WHERE `globalId` = '' OR `globalId` IS NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_study_tasks_globalId` ON `study_tasks` (`globalId`)")
                if (archive.manifest.databaseVersion < 10) {
                    // 旧备份没有 V3.1 的 daily_tasks；恢复后补一份，避免旧任务只留在归档表里。
                    DailyLogBackfill.run(db)
                }
                // 恢复不是本地新修改：清空同步队列、基线与墓碑，避免误同步。
                listOf("sync_operations", "sync_baselines", "sync_tombstones", "sync_state").forEach { table ->
                    db.execSQL("DELETE FROM `$table`")
                }
                markRestoreCommitted(db, restoreToken, clock.millis())
            }
            databaseCommitted = true
            // 恢复后的数据不应沿用旧的“已导出”水位，否则下次导出可能漏掉对象。
            runCatching { FileSyncWatermarkStore(filesDir).clear() }
            try {
                swapDocumentsDirectory(
                    liveRoot = liveRoot,
                    stagedRoot = stagedDocuments,
                    backupRoot = backupRoot,
                )
            } catch (error: Exception) {
                // 保留 journal 与两个目录，下一次启动由 BackupRecovery 完成替换。
                throw BackupValidationException(
                    "数据库已恢复，但题库原始文件替换未完成；请重启应用，应用会自动完成文件恢复。" +
                        (error.message?.let { "（$it）" } ?: ""),
                    error,
                )
            }

            // 文件替换成功：清理旧文件、暂存目录与恢复日志；删除失败时保留日志，
            // 下次启动继续清理。
            backupRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
            if (!backupRoot.exists() && !stagingRoot.exists()) {
                val markerCleared = runCatching { clearRestoreMarker(database, restoreToken) }.isSuccess
                if (markerCleared) BackupRecovery.clear(filesDir)
            }
            return RestoreResult(
                restoredCounts = report.counts,
                restoredDocumentFiles = archive.documentEntries.size,
                manifestCreatedAt = archive.manifest.createdAt,
            )
        } catch (error: BackupValidationException) {
            if (!databaseCommitted) {
                cleanupStaging(stagingRoot)
                // 事务未提交：journal 中的 token 在数据库里不存在，启动恢复会安全回滚。
                runCatching { BackupRecovery.clear(filesDir) }
            }
            throw error
        } catch (error: Exception) {
            if (!databaseCommitted) {
                cleanupStaging(stagingRoot)
                runCatching { BackupRecovery.clear(filesDir) }
            }
            throw BackupValidationException(
                if (databaseCommitted) {
                    "数据已恢复，但文件恢复未完成；请重启应用完成恢复：${error.message ?: "未知错误"}"
                } else {
                    "恢复失败，已回滚到恢复前的数据：${error.message ?: "未知错误"}"
                },
                error,
            )
        }
    }

    private fun swapDocumentsDirectory(liveRoot: File, stagedRoot: File, backupRoot: File) {
        if (backupRoot.exists()) backupRoot.deleteRecursively()
        if (liveRoot.exists() && !liveRoot.renameTo(backupRoot)) {
            throw BackupValidationException("无法暂存当前题库原始文件，请检查存储空间")
        }
        if (!stagedRoot.renameTo(liveRoot)) {
            throw BackupValidationException("无法替换题库原始文件，请检查存储空间")
        }
    }

    private fun cleanupStaging(stagingRoot: File) {
        stagingRoot.deleteRecursively()
    }

    private fun readArchive(input: InputStream, stagingRoot: File?): LoadedArchive {
        var manifestText: String? = null
        var dataText: String? = null
        val documentEntries = mutableListOf<String>()
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory) {
                    when {
                        name == BackupCreator.MANIFEST_ENTRY -> {
                            manifestText = readLimited(zip, MAX_MANIFEST_BYTES, "备份清单")
                            totalBytes += manifestText.length.toLong()
                        }
                        name == BackupCreator.DATA_ENTRY -> {
                            dataText = readLimited(zip, BackupCreator.MAX_DATA_BYTES, "备份数据")
                            totalBytes += dataText.length.toLong()
                        }
                        name.startsWith(BackupCreator.DOCUMENTS_PREFIX) -> {
                            val relative = validateDocumentEntry(name)
                            documentEntries += relative
                            if (stagingRoot != null) {
                                extractDocument(zip, stagingRoot, relative)
                            }
                            totalBytes += entry.size.coerceAtLeast(0L)
                        }
                        else -> Unit
                    }
                }
                if (totalBytes > BackupCreator.MAX_TOTAL_BYTES) {
                    throw BackupValidationException("备份文件过大，超过 2 GB 限制")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val manifest = BackupManifestCodec.decode(
            manifestText ?: throw BackupValidationException("不是有效的 StudyOS 备份：缺少 manifest.json"),
        )
        if (manifest.formatVersion != BACKUP_FORMAT_VERSION) {
            throw BackupValidationException(
                "备份格式版本不受支持（v${manifest.formatVersion}），当前支持 v$BACKUP_FORMAT_VERSION",
            )
        }
        val data = try {
            JSONObject(
                dataText ?: throw BackupValidationException("不是有效的 StudyOS 备份：缺少 data.json"),
            )
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("备份数据不是合法 JSON", error)
        }
        val dataVersion = data.optInt("databaseVersion", -1)
        if (dataVersion != manifest.databaseVersion) {
            throw BackupValidationException("备份清单与数据中的数据库版本不一致")
        }
        val warnings = mutableListOf<String>()
        if (documentEntries.size != manifest.documentFileCount) {
            warnings += "备份清单中的文件数量（${manifest.documentFileCount}）与实际文件数（${documentEntries.size}）不一致"
        }
        return LoadedArchive(
            manifest = manifest,
            data = data,
            documentEntries = documentEntries,
            warnings = warnings,
        )
    }

    private fun validateDocumentEntry(name: String): String {
        val relative = name.removePrefix(BackupCreator.DOCUMENTS_PREFIX)
        if (relative.isBlank() || relative.startsWith("/")) {
            throw BackupValidationException("备份包含非法文件路径：$name")
        }
        if (relative.contains('\\')) {
            throw BackupValidationException("备份包含非法文件路径：$name")
        }
        val parts = relative.split('/')
        if (parts.any { it == ".." || it == "." || it.isBlank() }) {
            throw BackupValidationException("备份包含非法文件路径：$name")
        }
        if (!relative.startsWith(BackupTables.DOCUMENTS_ROOT + "/")) {
            throw BackupValidationException("备份包含未知目录的文件：$name")
        }
        return relative
    }

    private fun extractDocument(zip: ZipInputStream, stagingRoot: File, relative: String) {
        val target = File(stagingRoot, relative)
        val canonicalRoot = stagingRoot.canonicalFile
        if (!target.canonicalPath.startsWith(canonicalRoot.path + File.separator)) {
            throw BackupValidationException("备份文件路径越界：$relative")
        }
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw BackupValidationException("无法创建目录，存储空间可能不足")
        }
        var written = 0L
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                written += read
                if (written > BackupCreator.MAX_ENTRY_BYTES) {
                    throw BackupValidationException("备份中的文件过大：$relative")
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun referencedDocumentPaths(data: JSONObject): List<String> {
        val rows = data.optJSONObject("tables")?.optJSONArray("problem_documents") ?: return emptyList()
        val paths = mutableListOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val path = row.optString("relativePath").trim()
            if (path.isNotEmpty()) paths += path
        }
        return paths.distinct()
    }

    private fun verifyReferencedDocuments(referenced: List<String>, entries: List<String>) {
        if (referenced.isEmpty()) return
        val available = entries.toSet()
        val missing = referenced.filterNot { it in available }
        if (missing.isNotEmpty()) {
            val preview = missing.take(3).joinToString("、")
            throw BackupValidationException(
                "备份缺少 ${missing.size} 个题库原始文件（如 $preview），为避免题库页面失效，已取消恢复",
            )
        }
    }

    private fun readLimited(input: InputStream, limit: Long, label: String): String {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw BackupValidationException("$label 超过大小限制")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    }
}

/** 恢复哨兵占用 sync_state 的保留 ID；正常同步只使用 id=1。 */
private const val RESTORE_SENTINEL_ID = Int.MAX_VALUE

internal fun markRestoreCommitted(
    db: SupportSQLiteDatabase,
    token: String,
    nowEpochMillis: Long,
) {
    db.execSQL(
        "INSERT OR REPLACE INTO `sync_state` " +
            "(`id`, `deviceId`, `lastSyncAtEpochMillis`, `lastConflictCount`, `updatedAtEpochMillis`) " +
            "VALUES (?, ?, NULL, 0, ?)",
        arrayOf<Any>(RESTORE_SENTINEL_ID.toLong(), token, nowEpochMillis),
    )
}

internal fun isRestoreCommitted(database: StudyOSDatabase, token: String): Boolean =
    database.openHelper.readableDatabase.query(
        "SELECT 1 FROM `sync_state` WHERE `id` = ? AND `deviceId` = ?",
        arrayOf<Any>(RESTORE_SENTINEL_ID.toLong(), token),
    ).use { cursor -> cursor.moveToFirst() }

internal fun clearRestoreMarker(database: StudyOSDatabase, token: String) {
    database.openHelper.writableDatabase.execSQL(
        "DELETE FROM `sync_state` WHERE `id` = ? AND `deviceId` = ?",
        arrayOf<Any>(RESTORE_SENTINEL_ID.toLong(), token),
    )
}

/**
 * 恢复文件替换阶段的崩溃恢复。
 *
 * 恢复顺序是「先提交数据库，再替换题库目录」。数据库提交后写一份很小的
 * `.restore_journal`；如果替换目录时进程被杀，下一次启动会按日志继续把
 * staging 目录换到正式目录，避免出现“数据库已更新、文件还是旧的”的永久不一致。
 */
internal object BackupRecovery {
    private const val JOURNAL_FILE = ".restore_journal"
    private const val JOURNAL_VERSION = "1"

    data class Journal(
        val stagingName: String,
        val backupName: String,
        /** 写入数据库事务的恢复标记；旧版 journal 为空，按“已提交”兼容。 */
        val token: String? = null,
    )

    fun hasPending(filesDir: File): Boolean = File(filesDir, JOURNAL_FILE).isFile

    fun write(filesDir: File, journal: Journal) {
        require(isSafeName(journal.stagingName) && isSafeName(journal.backupName)) {
            "恢复目录名不合法"
        }
        File(filesDir, JOURNAL_FILE).writeText(
            buildString {
                appendLine("version=$JOURNAL_VERSION")
                appendLine("staging=${journal.stagingName}")
                appendLine("backup=${journal.backupName}")
                journal.token?.let { appendLine("token=$it") }
            },
            Charsets.UTF_8,
        )
    }

    fun clear(filesDir: File) {
        File(filesDir, JOURNAL_FILE).delete()
    }

    /**
     * @param isDatabaseCommitted 查询数据库中的恢复哨兵；旧 journal 无 token 时按已提交处理。
     * @param clearDatabaseMarker 删除数据库哨兵。
     */
    fun recover(
        filesDir: File,
        isDatabaseCommitted: (String) -> Boolean = { true },
        clearDatabaseMarker: (String) -> Unit = {},
    ) {
        val journal = read(filesDir) ?: return
        val committed = journal.token?.let(isDatabaseCommitted) ?: true
        val live = File(filesDir, BackupTables.DOCUMENTS_ROOT)
        val stagingRoot = File(filesDir, journal.stagingName)
        val stagedDocuments = File(stagingRoot, BackupTables.DOCUMENTS_ROOT)
        val backup = File(filesDir, journal.backupName)

        if (!committed) {
            // 数据库事务已回滚，正式文件还没有替换；清掉暂存文件并尽量恢复旧目录。
            if (stagedDocuments.exists()) stagedDocuments.deleteRecursively()
            if (!live.exists() && backup.exists()) backup.renameTo(live)
            if (backup.exists() && live.exists()) backup.deleteRecursively()
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
            if (!backup.exists() && !stagingRoot.exists()) clear(filesDir)
            return
        }

        if (stagedDocuments.exists()) {
            if (live.exists()) {
                if (backup.exists() && !backup.deleteRecursively()) return
                if (!live.renameTo(backup)) return
            }
            if (!stagedDocuments.renameTo(live)) return
        }

        if (live.exists()) {
            if (backup.exists()) backup.deleteRecursively()
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
            if (!backup.exists() && !stagingRoot.exists()) {
                journal.token?.let(clearDatabaseMarker)
                clear(filesDir)
            }
        } else if (backup.exists()) {
            // 正式目录缺失、新目录也没就位：只能先回退旧目录。
            if (backup.renameTo(live)) {
                if (stagingRoot.exists()) stagingRoot.deleteRecursively()
                if (!stagingRoot.exists()) {
                    journal.token?.let(clearDatabaseMarker)
                    clear(filesDir)
                }
            }
        } else {
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
            journal.token?.let(clearDatabaseMarker)
            clear(filesDir)
        }
    }

    private fun read(filesDir: File): Journal? {
        val file = File(filesDir, JOURNAL_FILE)
        if (!file.isFile) return null
        val values = runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
        }.getOrNull()
        val stagingName = values?.get("staging")
        val backupName = values?.get("backup")
        if (values?.get("version") != JOURNAL_VERSION ||
            stagingName == null ||
            backupName == null ||
            !isSafeName(stagingName) ||
            !isSafeName(backupName)
        ) {
            clear(filesDir)
            return null
        }
        return Journal(
            stagingName = stagingName,
            backupName = backupName,
            token = values?.get("token")?.takeIf { it.isNotBlank() },
        )
    }

    private fun isSafeName(name: String): Boolean =
        name.startsWith(".restore_") &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\')
}

/** manifest.json 编解码。字段顺序固定，便于人工检查。 */
object BackupManifestCodec {

    fun encode(manifest: BackupManifest): String {
        val counts = JSONObject()
        manifest.tableCounts.toSortedMap().forEach { (table, count) -> counts.put(table, count) }
        val excluded = org.json.JSONArray()
        manifest.excluded.forEach { excluded.put(it) }
        return JSONObject()
            .put("app", "zhixu-studyos")
            .put("formatVersion", manifest.formatVersion)
            .put("databaseVersion", manifest.databaseVersion)
            .put("appVersion", manifest.appVersion)
            .put("createdAt", manifest.createdAt.toString())
            .put("timezone", manifest.timezone)
            .put("tableCounts", counts)
            .put("documentCount", manifest.documentCount)
            .put("documentFileCount", manifest.documentFileCount)
            .put("documentTotalBytes", manifest.documentTotalBytes)
            .put("excluded", excluded)
            .toString(2)
    }

    fun decode(text: String): BackupManifest {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw BackupValidationException("备份清单不是合法 JSON", error)
        }
        val createdAt = runCatching { Instant.parse(root.getString("createdAt")) }.getOrNull()
            ?: throw BackupValidationException("备份清单缺少有效的创建时间")
        val countsObject = root.optJSONObject("tableCounts")
            ?: throw BackupValidationException("备份清单缺少表行数")
        val counts = mutableMapOf<String, Int>()
        countsObject.keys().forEach { table -> counts[table] = countsObject.optInt(table, 0) }
        val excluded = mutableListOf<String>()
        root.optJSONArray("excluded")?.let { array ->
            for (index in 0 until array.length()) excluded += array.optString(index)
        }
        return BackupManifest(
            formatVersion = root.optInt("formatVersion", -1),
            databaseVersion = root.optInt("databaseVersion", -1),
            appVersion = root.optString("appVersion"),
            createdAt = createdAt,
            timezone = root.optString("timezone"),
            tableCounts = counts,
            documentCount = root.optInt("documentCount", 0),
            documentFileCount = root.optInt("documentFileCount", 0),
            documentTotalBytes = root.optLong("documentTotalBytes", 0),
            excluded = excluded,
        )
    }
}
