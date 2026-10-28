package com.zhixu.studyos.core.sync

import android.database.Cursor
import android.net.Uri
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhixu.studyos.core.data.export.AppFileStore
import com.zhixu.studyos.core.data.local.SyncBaselineEntity
import com.zhixu.studyos.core.data.local.SyncDao
import com.zhixu.studyos.core.data.local.SyncOperationEntity
import com.zhixu.studyos.core.data.local.SyncTombstoneEntity
import com.zhixu.studyos.core.data.local.StudyOSDatabase
import com.zhixu.studyos.core.ownership.EncryptedBackupCrypto
import com.zhixu.studyos.core.sync.SyncScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地优先的多设备同步引擎。
 *
 * 当前没有配置任何云端账号或服务端，因此不伪造“云端同步成功”。真正可用的是：
 * 1. 本地修改队列：SQLite 触发器记录每次插入/更新/删除；
 * 2. 加密同步包：用户通过系统文件选择器导出/导入，口令派生的 AES-256-GCM 加密；
 * 3. 增量与冲突检测：以最后一次同步基线为准，双方都改过的对象列为冲突，由用户选择；
 * 4. 删除墓碑：避免已删除对象被旧设备复活；
 * 5. 云端传输适配接口 [SyncTransport]：配置真实服务前为未配置状态。
 *
 * 身份与范围：按对象类型 + 本地 ID 合并；同一份数据库在设备间恢复备份后 ID 稳定。
 * 不同设备各自新建的对象可能 ID 相同，这是当前手动同步包的已知限制，
 * 界面会提示优先在“导入方”使用主设备数据；未来云端接入应改用全局 ID。
 */
class LocalSyncService(
    private val database: StudyOSDatabase,
    private val dao: SyncDao,
    private val fileStore: AppFileStore,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val watermarks: SyncWatermarkStore,
    private val transport: SyncTransport = UnconfiguredCloudSyncTransport(),
) {
    val cloudConfigured: Boolean get() = transport.configured
    val transportName: String get() = transport.name

    fun observePendingCount(): Flow<Int> = dao.observeOperations()
        .map { rows -> rows.count { it.syncedAtEpochMillis == null } }

    fun observeRecentOperations(): Flow<List<SyncOperationEntity>> = dao.observeOperations(60)

    suspend fun pendingCount(): Int = dao.pendingCount()

    suspend fun buildBundle(scopes: Set<SyncScope>): SyncBundle {
        require(scopes.isNotEmpty()) { "请至少选择一类要同步的数据" }
        val device = deviceId()
        val exported = runCatching { watermarks.load() }.getOrDefault(emptyMap())
        return database.withTransaction {
            val db = database.openHelper.writableDatabase
            val pendingKeys = dao.pendingOperations()
                .map { "${it.objectType}:${it.objectId}" }
                .toSet()
            val objects = mutableListOf<SyncObject>()
            scopes.forEach { scope ->
                exportScope(db, scope).forEach { obj ->
                    // 只导出从未导出过、自上次成功导出后变化、或仍有待同步操作的行。
                    // 不能用 sync_baselines 代替：baseline 是双方共同版本，导出会破坏冲突检测。
                    if (exported[obj.key] != obj.payloadHash || obj.key in pendingKeys) {
                        objects += obj
                    }
                }
            }
            val tombstones = dao.getTombstones()
                .filter { tombstone -> scopes.any { it.objectType == tombstone.objectType } }
                .map {
                    SyncTombstone(
                        objectType = it.objectType,
                        objectId = it.objectId,
                        deletedAtEpochMillis = it.deletedAtEpochMillis,
                    )
                }
            SyncBundle(
                formatVersion = SYNC_FORMAT_VERSION,
                deviceId = device,
                exportedAt = Instant.now(clock),
                scopes = scopes.toList(),
                objects = objects,
                tombstones = tombstones,
            )
        }
    }

    suspend fun exportEncryptedBundle(
        uri: Uri,
        passphrase: CharArray,
        scopes: Set<SyncScope>,
    ): SyncExportResult {
        val text = String(passphrase)
        try {
            val exported = runCatching { watermarks.load() }.getOrDefault(emptyMap())
            val bundle = buildBundle(scopes)
            val (name, _) = fileStore.describe(uri)
            val json = encodeBundle(bundle)
            val bytes = fileStore.writeBinary(uri) { output ->
                EncryptedBackupCrypto.encrypt(text, output) { plain ->
                    plain.write(json.toByteArray(Charsets.UTF_8))
                    plain.flush()
                    json.toByteArray(Charsets.UTF_8).size.toLong()
                }
            }
            val now = clock.millis()
            database.withTransaction {
                // 只把本次选择的同步范围标为已同步；其他范围的待同步计数不能被误清零。
                dao.markSynced(bundle.scopes.map { it.objectType }, now)
                updateState(bundle.deviceId, now, 0)
            }
            // 导出水位单独保存，不碰 sync_baselines（冲突检测基线）。
            val updatedWatermarks = exported.toMutableMap()
            bundle.objects.forEach { obj -> updatedWatermarks[obj.key] = obj.payloadHash }
            bundle.tombstones.forEach { tombstone ->
                updatedWatermarks.remove("${tombstone.objectType}:${tombstone.objectId}")
            }
            // 水位写失败只会导致下次重复导出，不能影响已经写好的同步包。
            runCatching { watermarks.save(updatedWatermarks) }
            return SyncExportResult(
                fileName = name,
                sizeBytes = bytes,
                objectCount = bundle.objects.size,
                tombstoneCount = bundle.tombstones.size,
                scopeLabels = scopes.map { it.label },
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun inspectEncryptedBundle(uri: Uri, passphrase: CharArray): SyncPreview {
        val text = String(passphrase)
        try {
            val name = fileStore.describe(uri).first
            return fileStore.readBinary(uri) { input ->
                EncryptedBackupCrypto.decrypt(text, input) { plain ->
                    val json = plain.readBytes().toString(Charsets.UTF_8)
                    val bundle = decodeBundle(json)
                    val result = preview(bundle)
                    result.copy(warnings = result.warnings + "来源文件：$name")
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun preview(bundle: SyncBundle): SyncPreview = database.withTransaction {
        validateBundle(bundle)
        val db = database.openHelper.writableDatabase
        val baselines = dao.getBaselines().associateBy { it.objectType to it.objectId }
        val localTombstones = dao.getTombstones().associateBy { it.objectType to it.objectId }
        val newObjects = mutableListOf<SyncObjectSummary>()
        val updated = mutableListOf<SyncObjectSummary>()
        val conflicts = mutableListOf<SyncConflict>()
        val remoteDeletions = mutableListOf<SyncObjectSummary>()
        val warnings = mutableListOf<String>()
        if (!cloudConfigured) {
            warnings += "云端同步未配置；本次只处理手动加密同步包，不会上传任何数据。"
        }
        bundle.objects.forEach { remote ->
            if (bundle.scopes.none { it.objectType == remote.objectType }) {
                warnings += "同步包包含未选择的对象类型 ${remote.objectType}，已忽略"
                return@forEach
            }
            val local = loadLocalObject(db, remote.objectType, remote.objectId)
            val localTombstone = localTombstones[remote.objectType to remote.objectId]
            if (local == null) {
                if (localTombstone != null && localTombstone.deletedAtEpochMillis >= remote.updatedAtEpochMillis) {
                    return@forEach
                }
                newObjects += summary(remote)
                return@forEach
            }
            val localHash = hashPayload(local)
            // 用重新计算的 hash 做比较，兼容旧版同步包（子行 id 曾参与 hash）。
            val remoteHash = hashPayload(remote.payloadJson)
            if (localHash == remoteHash) return@forEach
            val baseline = baselines[remote.objectType to remote.objectId]
            val localChanged = baseline == null || baseline.payloadHash != localHash
            val remoteChanged = baseline == null || baseline.payloadHash != remoteHash
            when {
                localChanged && remoteChanged -> conflicts += SyncConflict(
                    objectType = remote.objectType,
                    objectId = remote.objectId,
                    local = summary(remote.objectType, remote.objectId, local.optJSONObject("row")),
                    remote = summary(remote),
                    description = "本地和另一台设备都修改了这个对象，需要你选择保留哪一个",
                )
                remoteChanged -> updated += summary(remote)
                else -> Unit
            }
        }
        bundle.tombstones.forEach { tombstone ->
            if (bundle.scopes.none { it.objectType == tombstone.objectType }) return@forEach
            val local = loadLocalObject(db, tombstone.objectType, tombstone.objectId) ?: return@forEach
            val localHash = hashPayload(local)
            val baseline = baselines[tombstone.objectType to tombstone.objectId]
            val localChanged = baseline == null || baseline.payloadHash != localHash
            if (localChanged) {
                conflicts += SyncConflict(
                    objectType = tombstone.objectType,
                    objectId = tombstone.objectId,
                    local = summary(tombstone.objectType, tombstone.objectId, local.optJSONObject("row")),
                    remote = null,
                    description = "另一台设备删除了这个对象，但本地在最后一次同步后修改过它",
                )
            } else {
                remoteDeletions += summary(tombstone.objectType, tombstone.objectId, local.optJSONObject("row"))
            }
        }
        SyncPreview(
            bundle = bundle,
            scopeLabels = bundle.scopes.map { it.label },
            newObjects = newObjects,
            updatedObjects = updated,
            conflicts = conflicts.distinctBy { it.objectType to it.objectId },
            remoteDeletions = remoteDeletions,
            warnings = warnings,
        )
    }

    suspend fun applyPreview(
        preview: SyncPreview,
        resolutions: Map<String, ConflictResolution>,
    ): SyncApplyResult {
        val bundle = preview.bundle
        val device = deviceId()
        val watermarkUpdates = mutableMapOf<String, String>()
        val watermarkRemovals = mutableSetOf<String>()
        val result = database.withTransaction {
            validateBundle(bundle)
            val db = database.openHelper.writableDatabase
            var imported = 0
            var updatedCount = 0
            var deleted = 0
            var resolved = 0
            var remaining = 0
            val newKeys = preview.newObjects.associateBy { it.objectType to it.objectId }
            val updatedKeys = preview.updatedObjects.associateBy { it.objectType to it.objectId }
            val conflictKeys = preview.conflicts.associateBy { it.objectType to it.objectId }
            val deletionKeys = preview.remoteDeletions.associateBy { it.objectType to it.objectId }
            val now = clock.millis()
            val baselineUpdates = mutableListOf<SyncBaselineEntity>()

            bundle.objects.forEach { remote ->
                val key = remote.objectType to remote.objectId
                if (!newKeys.containsKey(key) && !updatedKeys.containsKey(key)) return@forEach
                applyObject(db, remote)
                // 对象被接受后，本地旧墓碑不能继续存在，否则下次导出会同时发送
                // “对象”和“删除标记”，对端又把它删掉。
                dao.deleteTombstone(remote.objectType, remote.objectId)
                if (newKeys.containsKey(key)) imported += 1 else updatedCount += 1
                val localHash = loadLocalObject(db, remote.objectType, remote.objectId)
                    ?.let(::hashPayload) ?: hashPayload(remote.payloadJson)
                baselineUpdates += baseline(remote.objectType, remote.objectId, localHash, now)
                // 导入的对象已经是本地版本，不应在下次导出时原样回传给对端。
                watermarkUpdates[remote.key] = localHash
                dao.markObjectSynced(remote.objectType, remote.objectId, now)
                if (remote.objectType == "TASK" && newKeys.containsKey(key).not()) {
                    recordSyncHistory(db, remote.objectId, "从加密同步包更新任务", now)
                }
            }

            bundle.objects.filter { remote ->
                conflictKeys.containsKey(remote.objectType to remote.objectId)
            }.forEach { remote ->
                when (resolutions[remote.key]) {
                    ConflictResolution.USE_REMOTE -> {
                        applyObject(db, remote)
                        dao.deleteTombstone(remote.objectType, remote.objectId)
                        val localHash = loadLocalObject(db, remote.objectType, remote.objectId)
                            ?.let(::hashPayload) ?: hashPayload(remote.payloadJson)
                        baselineUpdates += baseline(remote.objectType, remote.objectId, localHash, now)
                        watermarkUpdates[remote.key] = localHash
                        dao.markObjectSynced(remote.objectType, remote.objectId, now)
                        resolved += 1
                    }
                    ConflictResolution.KEEP_LOCAL -> {
                        // 保留本地时，基线记成“对端版本”而不是本地版本：
                        // - 下次同一远端包再导入时 remoteChanged=false，不会静默覆盖本地；
                        // - 本地版本相对基线的 hash 仍不同，下次导出会把它推给对端。
                        // 若写成本地 hash，本地对象会被当作“已同步”，既不再导出，
                        // 又会在下一次导入时被对端版本覆盖。
                        baselineUpdates += baseline(
                            remote.objectType,
                            remote.objectId,
                            hashPayload(remote.payloadJson),
                            now,
                        )
                        // 本地版本必须进入下一次导出；即使它之前导出过，也要移除水位。
                        watermarkRemovals += remote.key
                        resolved += 1
                    }
                    null -> remaining += 1
                }
            }

            bundle.tombstones.forEach { tombstone ->
                val key = tombstone.objectType to tombstone.objectId
                if (deletionKeys.containsKey(key)) {
                    deleteLocalObject(db, tombstone.objectType, tombstone.objectId)
                    deleted += 1
                    dao.deleteBaseline(tombstone.objectType, tombstone.objectId)
                    dao.markObjectSynced(tombstone.objectType, tombstone.objectId, now)
                    watermarkRemovals += "${tombstone.objectType}:${tombstone.objectId}"
                } else if (conflictKeys.containsKey(key)) {
                    when (resolutions["${tombstone.objectType}:${tombstone.objectId}"]) {
                        ConflictResolution.USE_REMOTE -> {
                            deleteLocalObject(db, tombstone.objectType, tombstone.objectId)
                            dao.deleteBaseline(tombstone.objectType, tombstone.objectId)
                            dao.markObjectSynced(tombstone.objectType, tombstone.objectId, now)
                            watermarkRemovals += "${tombstone.objectType}:${tombstone.objectId}"
                            resolved += 1
                        }
                        ConflictResolution.KEEP_LOCAL -> {
                            // 对端删除、本地保留：不能把本地 hash 写成基线，否则下一次
                            // 同一删除墓碑会被当作“本地未变化”而直接删除本地对象。
                            // 删除基线让本地对象保持“待导出”，并让再次导入时继续提示冲突。
                            dao.deleteBaseline(tombstone.objectType, tombstone.objectId)
                            watermarkRemovals += "${tombstone.objectType}:${tombstone.objectId}"
                            resolved += 1
                        }
                        null -> remaining += 1
                    }
                }
            }

            // 未变化但双方都有基线的对象也推进基线，避免下次重复比较同一版本。
            bundle.objects.forEach { remote ->
                val key = remote.objectType to remote.objectId
                if (newKeys.containsKey(key) || updatedKeys.containsKey(key) || conflictKeys.containsKey(key)) {
                    return@forEach
                }
                val local = loadLocalObject(db, remote.objectType, remote.objectId) ?: return@forEach
                baselineUpdates += baseline(remote.objectType, remote.objectId, hashPayload(local), now)
            }

            dao.upsertBaselines(baselineUpdates.distinctBy { it.objectType to it.objectId })
            updateState(device, now, remaining)
            SyncApplyResult(
                imported = imported,
                updated = updatedCount,
                deleted = deleted,
                conflictsResolved = resolved,
                conflictsRemaining = remaining,
                baselinesUpdated = baselineUpdates.size,
            )
        }
        // 水位更新放在数据库事务之后；写失败最多导致下次重复导出，不影响已导入的数据。
        if (watermarkUpdates.isNotEmpty() || watermarkRemovals.isNotEmpty()) {
            val updated = runCatching { watermarks.load() }.getOrDefault(emptyMap()).toMutableMap()
            updated.putAll(watermarkUpdates)
            watermarkRemovals.forEach(updated::remove)
            runCatching { watermarks.save(updated) }
        }
        return result
    }

    // ---------------- 读取与写入对象 ----------------

    private fun exportScope(db: SupportSQLiteDatabase, scope: SyncScope): List<SyncObject> {
        val rows = queryRows(db, scope.table)
        val result = mutableListOf<SyncObject>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optLong("id")
            if (id <= 0) continue
            val payload = when (scope) {
                SyncScope.TASKS -> JSONObject()
                    .put("row", row)
                    .put("sessions", queryChildRows(db, "task_sessions", "taskId", id))
                SyncScope.GOALS -> JSONObject()
                    .put("row", row)
                    .put("milestones", queryChildRows(db, "goal_milestones", "goalId", id))
                SyncScope.FINANCE -> JSONObject()
                    .put("row", row)
                    .put("categoryName", categoryName(db, row.optLong("categoryId")))
                SyncScope.KNOWLEDGE -> JSONObject()
                    .put("row", row)
                    .put("dependencies", knowledgeDependencies(db, id))
                SyncScope.VOCABULARY, SyncScope.EXAMS -> JSONObject().put("row", row)
            }
            result += SyncObject(
                objectType = scope.objectType,
                objectId = id,
                revision = row.optInt("revision", 1),
                updatedAtEpochMillis = row.optLong("updatedAtEpochMillis", 0L),
                payloadHash = hashPayload(payload),
                payloadJson = payload.toString(),
            )
        }
        return result
    }

    private fun loadLocalObject(db: SupportSQLiteDatabase, objectType: String, objectId: Long): JSONObject? {
        val scope = SyncScope.entries.firstOrNull { it.objectType == objectType } ?: return null
        val rows = queryRowsWhere(db, scope.table, "id", objectId)
        val row = rows.optJSONObject(0) ?: return null
        return when (scope) {
            SyncScope.TASKS -> JSONObject()
                .put("row", row)
                .put("sessions", queryChildRows(db, "task_sessions", "taskId", objectId))
            SyncScope.GOALS -> JSONObject()
                .put("row", row)
                .put("milestones", queryChildRows(db, "goal_milestones", "goalId", objectId))
            SyncScope.FINANCE -> JSONObject()
                .put("row", row)
                .put("categoryName", categoryName(db, row.optLong("categoryId")))
            SyncScope.KNOWLEDGE -> JSONObject()
                .put("row", row)
                .put("dependencies", knowledgeDependencies(db, objectId))
            SyncScope.VOCABULARY, SyncScope.EXAMS -> JSONObject().put("row", row)
        }
    }

    private fun applyObject(db: SupportSQLiteDatabase, remote: SyncObject) {
        val payload = try {
            JSONObject(remote.payloadJson)
        } catch (error: Exception) {
            throw SyncValidationException("同步对象 ${remote.key} 不是合法 JSON", error)
        }
        val row = payload.optJSONObject("row")
            ?: throw SyncValidationException("同步对象 ${remote.key} 缺少 row")
        when (remote.objectType) {
            "TASK" -> {
                val columns = row.keys().asSequence().filter { it != "id" }.toList()
                upsertRow(db, "study_tasks", row, columns)
                db.execSQL("DELETE FROM task_sessions WHERE taskId = ?", arrayOf<Any>(remote.objectId))
                insertChildRows(db, "task_sessions", "taskId", remote.objectId, payload.optJSONArray("sessions"))
            }
            "FINANCE" -> {
                val resolvedRow = JSONObject(row.toString())
                val categoryName = payload.optString("categoryName")
                if (row.optString("type") != "INCOME") {
                    if (categoryName.isNotBlank()) {
                        resolvedRow.put("categoryId", resolveCategory(db, categoryName))
                    }
                } else {
                    resolvedRow.put("categoryId", JSONObject.NULL)
                }
                upsertRow(db, "finance_transactions", resolvedRow, row.keys().asSequence().filter { it != "id" }.toList())
            }
            "VOCABULARY" -> {
                val normalized = row.optString("normalizedWord")
                val existing = db.query(
                    "SELECT id FROM vocabulary_words WHERE normalizedWord = ? LIMIT 1",
                    arrayOf<Any>(normalized),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                val targetId = existing ?: remote.objectId
                val target = JSONObject(row.toString()).put("id", targetId)
                upsertRow(db, "vocabulary_words", target, row.keys().asSequence().filter { it != "id" }.toList())
            }
            "KNOWLEDGE", "EXAM", "GOAL" -> {
                if (remote.objectType == "KNOWLEDGE") {
                    val subject = row.optString("subject")
                    val name = row.optString("name")
                    val existing = db.query(
                        "SELECT id FROM knowledge_nodes WHERE subject = ? AND name = ? LIMIT 1",
                        arrayOf<Any>(subject, name),
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                    val targetId = existing ?: remote.objectId
                    val target = JSONObject(row.toString()).put("id", targetId)
                    upsertRow(db, "knowledge_nodes", target, row.keys().asSequence().filter { it != "id" }.toList())
                    addKnowledgeDependencies(db, targetId, payload.optJSONArray("dependencies"))
                } else {
                    val table = if (remote.objectType == "EXAM") "exams" else "long_term_goals"
                    upsertRow(db, table, row, row.keys().asSequence().filter { it != "id" }.toList())
                }
                if (remote.objectType == "GOAL") {
                    db.execSQL("DELETE FROM goal_milestones WHERE goalId = ?", arrayOf<Any>(remote.objectId))
                    insertChildRows(
                        db,
                        "goal_milestones",
                        "goalId",
                        remote.objectId,
                        payload.optJSONArray("milestones"),
                    )
                }
            }
            else -> throw SyncValidationException("不支持的同步对象类型 ${remote.objectType}")
        }
    }

    private fun deleteLocalObject(db: SupportSQLiteDatabase, objectType: String, objectId: Long) {
        val table = when (objectType) {
            "TASK" -> "study_tasks"
            "FINANCE" -> "finance_transactions"
            "VOCABULARY" -> "vocabulary_words"
            "KNOWLEDGE" -> "knowledge_nodes"
            "EXAM" -> "exams"
            "GOAL" -> "long_term_goals"
            else -> throw SyncValidationException("不支持的删除类型 $objectType")
        }
        db.execSQL("DELETE FROM `$table` WHERE id = ?", arrayOf<Any>(objectId))
    }

    private fun upsertRow(
        db: SupportSQLiteDatabase,
        table: String,
        row: JSONObject,
        columns: List<String>,
    ) {
        val id = row.optLong("id")
        if (id <= 0) throw SyncValidationException("同步对象缺少有效 ID")
        val exists = db.query("SELECT COUNT(*) FROM `$table` WHERE id = ?", arrayOf<Any>(id))
            .use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
        val statementSql = if (exists) {
            val assignments = columns.joinToString(",") { "`$it` = ?" }
            "UPDATE `$table` SET $assignments WHERE id = ?"
        } else {
            "INSERT INTO `$table` (`id`, ${columns.joinToString(",") { "`$it`" }}) VALUES (?${
                columns.joinToString("") { ", ?" }
            })"
        }
        db.compileStatement(statementSql).use { statement ->
            var index = 1
            if (!exists) statement.bindLong(index++, id)
            columns.forEach { column ->
                bindJsonValue(statement, index++, row.opt(column))
            }
            if (exists) statement.bindLong(index, id)
            statement.executeInsert()
        }
    }

    private fun insertChildRows(
        db: SupportSQLiteDatabase,
        table: String,
        parentColumn: String,
        parentId: Long,
        rows: JSONArray?,
    ) {
        if (rows == null) return
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val copy = JSONObject(row.toString())
            copy.remove("id")
            copy.put(parentColumn, parentId)
            val columns = copy.keys().asSequence().toList()
            val placeholders = columns.joinToString(",") { "?" }
            db.compileStatement(
                "INSERT INTO `$table` (${columns.joinToString(",") { "`$it`" }}) VALUES ($placeholders)",
            ).use { statement ->
                columns.forEachIndexed { columnIndex, column ->
                    bindJsonValue(statement, columnIndex + 1, copy.opt(column))
                }
                statement.executeInsert()
            }
        }
    }

    private fun queryRows(db: SupportSQLiteDatabase, table: String): JSONArray = queryRowsWhere(db, table, null, null)

    private fun queryRowsWhere(
        db: SupportSQLiteDatabase,
        table: String,
        column: String?,
        value: Long?,
    ): JSONArray {
        val where = if (column != null && value != null) " WHERE `$column` = ?" else ""
        val args = if (column != null && value != null) arrayOf<Any>(value) else emptyArray()
        val rows = JSONArray()
        db.query("SELECT * FROM `$table`$where", args).use { cursor ->
            while (cursor.moveToNext()) {
                rows.put(cursorToJson(cursor))
            }
        }
        return rows
    }

    private fun queryChildRows(
        db: SupportSQLiteDatabase,
        table: String,
        parentColumn: String,
        parentId: Long,
    ): JSONArray = queryRowsWhere(db, table, parentColumn, parentId)

    private fun cursorToJson(cursor: Cursor): JSONObject {
        val row = JSONObject()
        for (index in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(index)
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(index))
                Cursor.FIELD_TYPE_BLOB -> row.put(name, JSONObject.NULL)
                else -> row.put(name, cursor.getString(index))
            }
        }
        return row
    }

    private fun bindJsonValue(
        statement: androidx.sqlite.db.SupportSQLiteStatement,
        index: Int,
        value: Any?,
    ) {
        when (value) {
            null, JSONObject.NULL -> statement.bindNull(index)
            is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
            is Int -> statement.bindLong(index, value.toLong())
            is Long -> statement.bindLong(index, value)
            is Double -> statement.bindDouble(index, value)
            is Float -> statement.bindDouble(index, value.toDouble())
            is String -> statement.bindString(index, value)
            else -> statement.bindString(index, value.toString())
        }
    }

    private fun categoryName(db: SupportSQLiteDatabase, categoryId: Long): String {
        if (categoryId <= 0) return ""
        return db.query("SELECT name FROM finance_categories WHERE id = ?", arrayOf<Any>(categoryId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
    }

    private fun resolveCategory(db: SupportSQLiteDatabase, name: String): Long {
        db.query("SELECT id FROM finance_categories WHERE name = ? LIMIT 1", arrayOf<Any>(name))
            .use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        val sortOrder = db.query("SELECT COALESCE(MAX(sortOrder),0) FROM finance_categories")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        return db.compileStatement(
            "INSERT INTO finance_categories (`name`, `isPreset`, `sortOrder`) VALUES (?, 0, ?)",
        ).use { statement ->
            statement.bindString(1, name)
            statement.bindLong(2, sortOrder + 1L)
            statement.executeInsert()
        }
    }

    private fun knowledgeDependencies(db: SupportSQLiteDatabase, nodeId: Long): JSONArray {
        val result = JSONArray()
        db.query(
            "SELECT n.subject, n.name FROM knowledge_dependencies d " +
                "INNER JOIN knowledge_nodes n ON n.id = d.toNodeId WHERE d.fromNodeId = ?",
            arrayOf<Any>(nodeId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(JSONObject().put("subject", cursor.getString(0)).put("name", cursor.getString(1)))
            }
        }
        return result
    }

    private fun addKnowledgeDependencies(db: SupportSQLiteDatabase, fromNodeId: Long, deps: JSONArray?) {
        if (deps == null) return
        for (index in 0 until deps.length()) {
            val dep = deps.optJSONObject(index) ?: continue
            val subject = dep.optString("subject")
            val name = dep.optString("name")
            if (subject.isBlank() || name.isBlank()) continue
            val targetId = db.query(
                "SELECT id FROM knowledge_nodes WHERE subject = ? AND name = ? LIMIT 1",
                arrayOf<Any>(subject, name),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: continue
            if (targetId == fromNodeId) continue
            db.execSQL(
                "INSERT OR IGNORE INTO knowledge_dependencies (`fromNodeId`,`toNodeId`,`source`,`createdAtEpochMillis`) VALUES (?, ?, 'SYNC', ?)",
                arrayOf<Any>(fromNodeId, targetId, clock.millis()),
            )
        }
    }

    private fun recordSyncHistory(db: SupportSQLiteDatabase, taskId: Long, reason: String, now: Long) {
        db.execSQL(
            "INSERT INTO task_events (`objectType`,`objectId`,`eventType`,`occurredAtEpochMillis`,`beforeJson`,`afterJson`,`source`,`reason`,`sessionId`,`revision`) " +
                "VALUES ('TASK', ?, 'TASK_EDITED', ?, NULL, NULL, 'SYSTEM', ?, NULL, 0)",
            arrayOf<Any>(taskId, now, reason),
        )
    }

    // ---------------- 摘要 / 哈希 / 编解码 ----------------

    private fun summary(remote: SyncObject): SyncObjectSummary = summary(
        remote.objectType,
        remote.objectId,
        runCatching { JSONObject(remote.payloadJson).optJSONObject("row") }.getOrNull(),
    )

    private fun summary(objectType: String, objectId: Long, row: JSONObject?): SyncObjectSummary {
        val title = when (objectType) {
            "TASK" -> row?.optString("title").orEmpty()
            "FINANCE" -> row?.optString("note").orEmpty().ifBlank {
                row?.optString("type").orEmpty()
            }
            "VOCABULARY" -> row?.optString("word").orEmpty()
            "KNOWLEDGE" -> listOf(row?.optString("subject"), row?.optString("name"))
                .filterNotNull().filter(String::isNotBlank).joinToString(" · ")
            "EXAM" -> row?.optString("name").orEmpty()
            "GOAL" -> row?.optString("title").orEmpty()
            else -> ""
        }
        val detail = when (objectType) {
            "TASK" -> listOfNotNull(
                row?.optString("subject")?.takeIf(String::isNotBlank),
                row?.optString("status"),
                row?.optString("dueDateEpochDay")?.takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "FINANCE" -> "金额 ${row?.optLong("amountFen") ?: 0} 分"
            "VOCABULARY" -> row?.optString("meaning").orEmpty()
            "KNOWLEDGE" -> row?.optString("chapter").orEmpty()
            "EXAM" -> row?.optString("subject").orEmpty()
            "GOAL" -> row?.optString("category").orEmpty()
            else -> ""
        }
        return SyncObjectSummary(
            objectType = objectType,
            objectId = objectId,
            title = title.ifBlank { "$objectType #$objectId" },
            detail = detail,
        )
    }

    private fun hashPayload(payload: JSONObject): String = SyncPayloadHasher.hash(payload)

    private fun hashPayload(payloadJson: String): String = SyncPayloadHasher.hash(payloadJson)

    private fun encodeBundle(bundle: SyncBundle): String {
        val objects = JSONArray()
        bundle.objects.forEach { obj ->
            objects.put(
                JSONObject()
                    .put("objectType", obj.objectType)
                    .put("objectId", obj.objectId)
                    .put("revision", obj.revision)
                    .put("updatedAtEpochMillis", obj.updatedAtEpochMillis)
                    .put("payloadHash", obj.payloadHash)
                    .put("payload", JSONObject(obj.payloadJson)),
            )
        }
        val tombstones = JSONArray()
        bundle.tombstones.forEach { tombstone ->
            tombstones.put(
                JSONObject()
                    .put("objectType", tombstone.objectType)
                    .put("objectId", tombstone.objectId)
                    .put("deletedAtEpochMillis", tombstone.deletedAtEpochMillis),
            )
        }
        val scopes = JSONArray()
        bundle.scopes.forEach { scopes.put(it.key) }
        return JSONObject()
            .put("app", "zhixu-studyos")
            .put("format", SYNC_FORMAT)
            .put("formatVersion", bundle.formatVersion)
            .put("deviceId", bundle.deviceId)
            .put("exportedAt", bundle.exportedAt.toString())
            .put("timezone", zoneId.id)
            .put("scopes", scopes)
            .put("objects", objects)
            .put("tombstones", tombstones)
            .toString()
    }

    private fun decodeBundle(json: String): SyncBundle {
        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw SyncValidationException("同步包不是合法 JSON", error)
        }
        if (root.optString("format") != SYNC_FORMAT) {
            throw SyncValidationException("不是 StudyOS 同步包")
        }
        val version = root.optInt("formatVersion", -1)
        if (version != SYNC_FORMAT_VERSION) {
            throw SyncValidationException("同步包版本不受支持（$version）")
        }
        val scopeKeys = root.optJSONArray("scopes")
            ?: throw SyncValidationException("同步包缺少同步范围")
        val scopes = (0 until scopeKeys.length()).mapNotNull { index ->
            SyncScope.entries.firstOrNull { it.key == scopeKeys.optString(index) }
        }
        if (scopes.isEmpty()) throw SyncValidationException("同步包没有可识别的同步范围")
        val objectsJson = root.optJSONArray("objects") ?: JSONArray()
        val objects = (0 until objectsJson.length()).mapNotNull { index ->
            val obj = objectsJson.optJSONObject(index) ?: return@mapNotNull null
            val payload = obj.optJSONObject("payload") ?: return@mapNotNull null
            SyncObject(
                objectType = obj.optString("objectType"),
                objectId = obj.optLong("objectId"),
                revision = obj.optInt("revision", 1),
                updatedAtEpochMillis = obj.optLong("updatedAtEpochMillis"),
                payloadHash = obj.optString("payloadHash"),
                payloadJson = payload.toString(),
            )
        }
        val tombstonesJson = root.optJSONArray("tombstones") ?: JSONArray()
        val tombstones = (0 until tombstonesJson.length()).mapNotNull { index ->
            val tombstone = tombstonesJson.optJSONObject(index) ?: return@mapNotNull null
            SyncTombstone(
                objectType = tombstone.optString("objectType"),
                objectId = tombstone.optLong("objectId"),
                deletedAtEpochMillis = tombstone.optLong("deletedAtEpochMillis"),
            )
        }
        val exportedAt = runCatching { Instant.parse(root.optString("exportedAt")) }
            .getOrDefault(Instant.now(clock))
        return SyncBundle(
            formatVersion = version,
            deviceId = root.optString("deviceId"),
            exportedAt = exportedAt,
            scopes = scopes,
            objects = objects,
            tombstones = tombstones,
        )
    }

    private fun validateBundle(bundle: SyncBundle) {
        if (bundle.formatVersion != SYNC_FORMAT_VERSION) {
            throw SyncValidationException("同步包版本不受支持")
        }
        bundle.objects.forEach { obj ->
            if (SyncScope.entries.none { it.objectType == obj.objectType }) {
                throw SyncValidationException("同步包包含未知对象类型 ${obj.objectType}")
            }
            if (obj.objectId <= 0) throw SyncValidationException("同步包包含无效对象 ID")
            val payload = runCatching { JSONObject(obj.payloadJson) }.getOrElse {
                throw SyncValidationException("同步包对象 ${obj.key} 的内容损坏", it)
            }
            if (hashPayload(payload) != obj.payloadHash &&
                SyncPayloadHasher.hashLegacy(obj.payloadJson) != obj.payloadHash
            ) {
                throw SyncValidationException("同步包对象 ${obj.key} 的校验值不一致，文件可能已损坏")
            }
        }
    }

    private fun baseline(objectType: String, objectId: Long, hash: String, now: Long) = SyncBaselineEntity(
        objectType = objectType,
        objectId = objectId,
        revision = 1,
        payloadHash = hash,
        syncedAtEpochMillis = now,
    )

    private suspend fun updateState(deviceId: String, now: Long, conflicts: Int) {
        dao.upsertState(
            com.zhixu.studyos.core.data.local.SyncStateEntity(
                id = 1,
                deviceId = deviceId,
                lastSyncAtEpochMillis = now,
                lastConflictCount = conflicts,
                updatedAtEpochMillis = now,
            ),
        )
    }

    private suspend fun deviceId(): String {
        val existing = dao.getState()?.deviceId
        if (!existing.isNullOrBlank()) return existing
        val created = "device-${UUID.randomUUID().toString().take(8)}"
        dao.upsertState(
            com.zhixu.studyos.core.data.local.SyncStateEntity(
                id = 1,
                deviceId = created,
                lastSyncAtEpochMillis = null,
                lastConflictCount = 0,
                updatedAtEpochMillis = clock.millis(),
            ),
        )
        return created
    }

    companion object {
        const val SYNC_FORMAT = "studyos-sync-bundle"
        const val SYNC_FORMAT_VERSION = 1
    }
}
