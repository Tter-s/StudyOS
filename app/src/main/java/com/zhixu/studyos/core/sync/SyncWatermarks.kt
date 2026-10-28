package com.zhixu.studyos.core.sync

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 每个对象上次成功导出的 payload hash。
 *
 * 它只用于决定“下次导出是否还需要包含这个对象”，与冲突检测使用的
 * `sync_baselines`（双方共同版本）是两件事：
 * - 导出水位在导出成功后推进，避免每次导出都重发全部对象；
 * - 同步基线只在导入/确认后变化，用于判断双方是否都改过。
 * 如果把导出水位直接写进 baseline，会让“本地刚导出、远端随后修改”被误判成
 * 只有远端变化，从而静默覆盖本地。
 */
interface SyncWatermarkStore {
    suspend fun load(): Map<String, String>
    suspend fun save(watermarks: Map<String, String>)
    suspend fun clear()
}

class FileSyncWatermarkStore(private val filesDir: File) : SyncWatermarkStore {
    private val file = File(filesDir, FILE_NAME)

    override suspend fun load(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching emptyMap()
            val root = JSONObject(file.readText(Charsets.UTF_8))
            root.keys().asSequence().associateWith { key -> root.optString(key) }
        }.getOrDefault(emptyMap())
    }

    override suspend fun save(watermarks: Map<String, String>) = withContext(Dispatchers.IO) {
        val root = JSONObject()
        watermarks.toSortedMap().forEach { (key, hash) -> root.put(key, hash) }
        val temporary = File(filesDir, "$FILE_NAME.tmp")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            file.delete()
            File(filesDir, "$FILE_NAME.tmp").delete()
        }
    }

    companion object {
        const val FILE_NAME = "sync_export_watermarks.json"
    }
}

/** 测试或内存场景使用的简单实现。 */
class InMemorySyncWatermarkStore(
    initial: Map<String, String> = emptyMap(),
) : SyncWatermarkStore {
    private var watermarks: Map<String, String> = initial

    override suspend fun load(): Map<String, String> = watermarks

    override suspend fun save(watermarks: Map<String, String>) {
        this.watermarks = watermarks.toMap()
    }

    override suspend fun clear() {
        watermarks = emptyMap()
    }
}
