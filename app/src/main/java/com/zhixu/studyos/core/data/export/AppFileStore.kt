package com.zhixu.studyos.core.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 Storage Access Framework 读写用户选择的文件（备份 ZIP / CSV），
 * 不申请存储权限。所有方法都在 IO 线程执行。
 */
interface AppFileStore {
    suspend fun writeText(uri: Uri, content: String)

    suspend fun <T> writeBinary(uri: Uri, writer: suspend (OutputStream) -> T): T

    suspend fun <T> readBinary(uri: Uri, reader: suspend (InputStream) -> T): T

    /** 返回展示用文件名与大小；大小未知时为 -1。 */
    suspend fun describe(uri: Uri): Pair<String, Long>
}

class ContentResolverAppFileStore(context: Context) : AppFileStore {
    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun writeText(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        val output = openOutput(uri)
        output.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
        }
    }

    override suspend fun <T> writeBinary(uri: Uri, writer: suspend (OutputStream) -> T): T =
        withContext(Dispatchers.IO) {
            openOutput(uri).use { stream -> writer(stream) }
        }

    override suspend fun <T> readBinary(uri: Uri, reader: suspend (InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use { stream -> reader(stream) }
        }

    override suspend fun describe(uri: Uri): Pair<String, Long> = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment ?: "studyos-backup.zip"
        var size = -1L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        name to size
    }

    private fun openOutput(uri: Uri): OutputStream {
        // "wt" 截断已有文件；部分提供方不支持，回退到默认模式
        return runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法打开所选文件")
    }
}
