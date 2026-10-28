package com.zhixu.studyos.core.data.export

import android.content.Context
import android.net.Uri
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 通过系统文件选择器写入用户指定位置的文本文件，不需要存储权限。 */
interface FinanceDocumentStore {
    suspend fun writeText(uri: Uri, content: String)
}

class ContentResolverFinanceDocumentStore(context: Context) : FinanceDocumentStore {
    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun writeText(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        val stream = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法打开所选文件")
        stream.use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }
}
