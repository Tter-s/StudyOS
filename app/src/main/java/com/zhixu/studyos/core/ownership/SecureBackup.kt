package com.zhixu.studyos.core.ownership

import android.net.Uri
import androidx.room.withTransaction
import com.zhixu.studyos.BuildConfig
import com.zhixu.studyos.core.ai.security.ApiKeyStore
import com.zhixu.studyos.core.backup.AppBackupService
import com.zhixu.studyos.core.backup.BackupCreator
import com.zhixu.studyos.core.backup.BackupPreview
import com.zhixu.studyos.core.backup.BackupRestorer
import com.zhixu.studyos.core.backup.BackupSummary
import com.zhixu.studyos.core.backup.BackupTables
import com.zhixu.studyos.core.backup.BackupValidationException
import com.zhixu.studyos.core.backup.BackupCodec
import com.zhixu.studyos.core.backup.OfflineDataExportService
import com.zhixu.studyos.core.backup.RestoreResult
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.data.preferences.AiConfigStore
import com.zhixu.studyos.core.data.preferences.UserPreferencesRepository
import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.sync.FileSyncWatermarkStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 加密备份格式：`STUDYOS-ENC-1` + 盐 + IV + PBKDF2 迭代次数 + AES-256-GCM 密文。
 *
 * - 密钥由用户口令通过 PBKDF2-HMAC-SHA256（120,000 次）派生，不上传、不落盘；
 * - 每次备份使用新的随机盐与 IV；
 * - GCM 自带完整性校验，口令错误或文件被篡改时拒绝解密；
 * - 口令只存在于调用栈，不写日志、不进入普通备份。
 */
object EncryptedBackupCrypto {
    const val MAGIC = "STUDYOS-ENC-1"
    const val MIN_PASSPHRASE_LENGTH = 8
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000

    fun validatePassphrase(passphrase: String): String? = when {
        passphrase.length < MIN_PASSPHRASE_LENGTH -> "备份口令至少需要 $MIN_PASSPHRASE_LENGTH 个字符"
        passphrase.length > 200 -> "备份口令过长"
        else -> null
    }

    suspend fun <T> encrypt(
        passphrase: String,
        output: OutputStream,
        writer: suspend (OutputStream) -> T,
    ): T {
        validatePassphrase(passphrase)?.let { throw BackupValidationException(it) }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val dataOut = DataOutputStream(output)
        dataOut.write(MAGIC.toByteArray(Charsets.US_ASCII))
        dataOut.writeByte(SALT_BYTES)
        dataOut.write(salt)
        dataOut.writeByte(IV_BYTES)
        dataOut.write(iv)
        dataOut.writeInt(ITERATIONS)
        dataOut.flush()
        val cipherOut = CipherOutputStream(dataOut, cipher)
        val result = writer(cipherOut)
        cipherOut.flush()
        cipherOut.close()
        return result
    }

    suspend fun <T> decrypt(
        passphrase: String,
        input: InputStream,
        reader: suspend (InputStream) -> T,
    ): T {
        validatePassphrase(passphrase)?.let { throw BackupValidationException(it) }
        val dataIn = DataInputStream(input)
        val magicBytes = ByteArray(MAGIC.length)
        try {
            dataIn.readFully(magicBytes)
        } catch (error: Exception) {
            throw BackupValidationException("不是有效的加密备份：文件过短", error)
        }
        if (String(magicBytes, Charsets.US_ASCII) != MAGIC) {
            throw BackupValidationException("不是 StudyOS 加密备份，或文件格式版本不受支持")
        }
        val saltLength = dataIn.readUnsignedByte()
        if (saltLength !in 8..64) throw BackupValidationException("加密备份盐值长度无效")
        val salt = ByteArray(saltLength).also { dataIn.readFully(it) }
        val ivLength = dataIn.readUnsignedByte()
        if (ivLength !in 8..32) throw BackupValidationException("加密备份 IV 长度无效")
        val iv = ByteArray(ivLength).also { dataIn.readFully(it) }
        val iterations = dataIn.readInt()
        if (iterations !in 10_000..1_000_000) throw BackupValidationException("加密备份参数无效")
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return try {
            CipherInputStream(dataIn, cipher).use { cipherIn -> reader(cipherIn) }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException(
                "无法解密备份：口令错误或文件已损坏",
                error,
            )
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(encoded, "AES")
    }
}

interface SecureBackupService {
    suspend fun createEncryptedBackup(uri: Uri, passphrase: CharArray): BackupSummary
    suspend fun inspectEncryptedBackup(uri: Uri, passphrase: CharArray): BackupPreview
    suspend fun restoreEncryptedBackup(uri: Uri, passphrase: CharArray): RestoreResult
}

class AppSecureBackupService(
    private val database: StudyOSDatabase,
    private val filesDir: File,
    private val fileStore: AppFileStore,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val appVersion: String,
) : SecureBackupService {
    private val creator = BackupCreator(database, filesDir, clock, zoneId, appVersion)
    private val restorer = BackupRestorer(database, filesDir, clock)

    override suspend fun createEncryptedBackup(uri: Uri, passphrase: CharArray): BackupSummary {
        val (name, _) = fileStore.describe(uri)
        val text = String(passphrase)
        try {
            val summary = fileStore.writeBinary(uri) { stream ->
                EncryptedBackupCrypto.encrypt(text, stream) { plain -> creator.write(plain) }
            }
            return summary.copy(fileName = name)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun inspectEncryptedBackup(uri: Uri, passphrase: CharArray): BackupPreview {
        val (name, size) = fileStore.describe(uri)
        val text = String(passphrase)
        try {
            return fileStore.readBinary(uri) { stream ->
                EncryptedBackupCrypto.decrypt(text, stream) { plain ->
                    restorer.inspect(plain, name, size)
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun restoreEncryptedBackup(uri: Uri, passphrase: CharArray): RestoreResult {
        val text = String(passphrase)
        try {
            return fileStore.readBinary(uri) { stream ->
                EncryptedBackupCrypto.decrypt(text, stream) { plain -> restorer.restore(plain) }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }
}

data class JsonExportSummary(
    val fileName: String,
    val sizeBytes: Long,
    val tableCount: Int,
    val rowCount: Int,
    val attachmentCount: Int,
    val createdAt: Instant,
)

data class WipeResult(
    val deletedRows: Int,
    val deletedFiles: Int,
    val deletedBytes: Long,
    val apiKeyCleared: Boolean,
)

/**
 * 数据所有权：版本化 JSON 导出与彻底删除。
 *
 * JSON 格式与数据库表结构解耦（表名 + 行对象 + 版本号），附带附件清单；
 * 需要包含二进制附件的完整恢复请使用加密/普通 ZIP 备份。
 */
interface DataOwnershipService {
    suspend fun exportJson(uri: Uri): JsonExportSummary
    suspend fun wipeAllData(deleteApiKey: Boolean): WipeResult
}

class OfflineDataOwnershipService(
    private val database: StudyOSDatabase,
    private val filesDir: File,
    private val fileStore: AppFileStore,
    private val apiKeyStore: ApiKeyStore,
    private val aiConfigStore: AiConfigStore,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val appVersion: String,
) : DataOwnershipService {
    private val codec = BackupCodec()

    override suspend fun exportJson(uri: Uri): JsonExportSummary {
        val createdAt = Instant.now(clock)
        val theme = userPreferencesRepository.themePreference.first()
        val aiConfig = aiConfigStore.getConfig()
        val (tablesJson, counts, documents) = database.withTransaction {
            val db = database.openHelper.writableDatabase
            val tables = codec.exportTables(db)
            val counts = codec.currentCounts(db)
            val documents = queryDocumentPaths(db)
            Triple(tables, counts, documents)
        }
        val attachments = JSONArray()
        documents.forEach { path ->
            val file = File(filesDir, path)
            attachments.put(
                JSONObject()
                    .put("path", path)
                    .put("exists", file.exists())
                    .put("sizeBytes", if (file.exists()) file.length() else 0L),
            )
        }
        val preferences = JSONObject()
            .put("theme", theme.name)
            .put("ai", aiConfig.toSanitizedJson())
        val root = JSONObject()
            .put("app", "zhixu-studyos")
            .put("format", JSON_EXPORT_FORMAT)
            .put("formatVersion", JSON_EXPORT_VERSION)
            .put("databaseVersion", database.openHelper.writableDatabase.version)
            .put("appVersion", appVersion)
            .put("createdAt", createdAt.toString())
            .put("timezone", zoneId.id)
            .put("tables", tablesJson.optJSONObject("tables") ?: JSONObject())
            .put("attachments", attachments)
            .put("preferences", preferences)
            .put(
                "excluded",
                JSONArray(
                    listOf(
                        "API Key（不进入备份或导出）",
                        "AI 使用量、缓存与计划记录（可重新生成）",
                    ),
                ),
            )
            .put("privacyNote", "本文件包含你的完整学习与财务记录，请妥善保管。")
        val (name, _) = fileStore.describe(uri)
        val text = root.toString(2)
        fileStore.writeText(uri, text)
        return JsonExportSummary(
            fileName = name,
            sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            tableCount = counts.size,
            rowCount = counts.values.sum(),
            attachmentCount = attachments.length(),
            createdAt = createdAt,
        )
    }

    override suspend fun wipeAllData(deleteApiKey: Boolean): WipeResult {
        var deletedRows = 0
        val dbVersion = database.openHelper.writableDatabase.version
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            // 先删业务表。业务表的 DELETE 触发器会写入同步墓碑/操作队列，
            // 因此 sync_* 必须留到最后清，否则“彻底删除”后会残留一批墓碑，
            // 下次导出还会把删除传播到另一台设备。
            val businessTables = BackupTables.ordered
                .filterNot { it.startsWith("sync_") }
                .asReversed()
            businessTables.forEach { table ->
                deletedRows += codec.countRows(db, table)
                db.execSQL("DELETE FROM `$table`")
            }
            listOf("ai_usage_daily", "ai_analysis_cache", "ai_plan_records").forEach { table ->
                deletedRows += codec.countRows(db, table)
                db.execSQL("DELETE FROM `$table`")
            }
            listOf("sync_tombstones", "sync_baselines", "sync_operations", "sync_state").forEach { table ->
                deletedRows += codec.countRows(db, table)
                db.execSQL("DELETE FROM `$table`")
            }
            // 恢复预设科目与财务分类，避免删除后应用没有基础分类可用。
            StudyOSDatabase.seedPresetSubjects(db)
            StudyOSDatabase.seedPresetFinanceCategories(db)
        }
        var fileCount = 0
        var bytes = 0L
        // 题库附件以及恢复中断留下的 .restore_* 临时目录都属于用户数据，必须一起删。
        val dataRoots = buildList {
            File(filesDir, BackupTables.DOCUMENTS_ROOT).let { if (it.exists()) add(it) }
            filesDir.listFiles()
                ?.filter { it.name.startsWith(".restore_") }
                ?.forEach(::add)
        }.distinctBy { it.absolutePath }
        dataRoots.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                fileCount += 1
                bytes += file.length()
            }
            root.deleteRecursively()
        }
        File(filesDir, FileSyncWatermarkStore.FILE_NAME).delete()
        val keyCleared = if (deleteApiKey) {
            apiKeyStore.clear()
            aiConfigStore.saveConfig(AiProviderConfig.DeepSeekDefault)
            true
        } else {
            false
        }
        return WipeResult(
            deletedRows = deletedRows,
            deletedFiles = fileCount,
            deletedBytes = bytes,
            apiKeyCleared = keyCleared,
        )
    }

    private fun queryDocumentPaths(db: androidx.sqlite.db.SupportSQLiteDatabase): List<String> {
        val paths = mutableListOf<String>()
        if (db.version >= 7) {
            db.query("SELECT relativePath FROM problem_documents ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(paths::add)
                }
            }
        }
        return paths
    }

    private fun AiProviderConfig.toSanitizedJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("baseUrl", baseUrl)
        .put("model", model)
        .put("timeoutSeconds", timeoutSeconds)
        .put("inputPriceMicrosPerMillionTokens", inputPriceMicrosPerMillionTokens)
        .put("outputPriceMicrosPerMillionTokens", outputPriceMicrosPerMillionTokens)
        .put("dailyCallLimit", dailyCallLimit)
        .put("dailyCostLimitMicros", dailyCostLimitMicros)
        .put("includeTaskTitles", includeTaskTitles)
        .put("includeSubjectNames", includeSubjectNames)
        .put("includeProblemStatements", includeProblemStatements)

    companion object {
        const val JSON_EXPORT_FORMAT = "studyos-data-export"
        const val JSON_EXPORT_VERSION = 1
    }
}
