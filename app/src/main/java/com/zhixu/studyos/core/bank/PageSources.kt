package com.zhixu.studyos.core.bank

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.zhixu.studyos.core.model.ProblemDocument
import com.zhixu.studyos.core.model.ProblemDocumentType
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用私有目录中的来源文档：原始 PDF/图片文件被复制保存，
 * 题目通过 documentId + pageIndex 与原始页面关联（V0.8 第五节第 5 条）。
 * 页面渲染始终从保存的原始文件进行，「查看原始页面」不依赖外部 Uri 仍然有效。
 */
class ProblemDocumentStore(private val context: Context) {

    data class StoredSource(
        val relativePath: String,
        val originalName: String,
        val sizeBytes: Long,
        val contentSha256: String,
        val pageCount: Int,
        /** 图片导入时每页的相对路径（PDF 为空）。 */
        val pageRelativePaths: List<String>,
        val type: ProblemDocumentType,
    )

    data class DocumentFileDeleted(val deleted: Boolean)

    private val rootDir: File
        get() = File(context.filesDir, ROOT_DIR_NAME).also { it.mkdirs() }

    suspend fun importPdf(displayName: String, uri: Uri): StoredSource = withContext(Dispatchers.IO) {
        val bytes = readLimited(uri, MAX_PDF_BYTES, "PDF")
        val sha256 = sha256(bytes)
        val dir = File(rootDir, "pdf-${System.currentTimeMillis()}-${(0..9999).random()}").also { it.mkdirs() }
        val file = File(dir, sanitizedFileName(displayName, "pdf"))
        file.outputStream().use { output -> output.write(bytes) }
        val pageCount = openPdfRenderer(file).use { renderer -> renderer.pageCount }.also { count ->
            if (count > MAX_PAGES) {
                file.delete()
                dir.delete()
                throw IllegalArgumentException("PDF 页数超过上限（$MAX_PAGES 页），请分批导入")
            }
        }
        StoredSource(
            relativePath = file.toRelativeString(context.filesDir),
            originalName = displayName,
            sizeBytes = bytes.size.toLong(),
            contentSha256 = sha256,
            pageCount = pageCount,
            pageRelativePaths = emptyList(),
            type = ProblemDocumentType.PDF,
        )
    }

    suspend fun importImages(displayName: String, uris: List<Uri>): StoredSource = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "请至少选择一张图片" }
        if (uris.size > MAX_IMAGES) {
            throw IllegalArgumentException("单次导入图片不超过 $MAX_IMAGES 张，请分批导入")
        }
        val dir = File(rootDir, "img-${System.currentTimeMillis()}-${(0..9999).random()}").also { it.mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        val pagePaths = mutableListOf<String>()
        var totalBytes = 0L
        try {
            uris.forEachIndexed { index, imageUri ->
                val bytes = readLimited(imageUri, MAX_IMAGE_BYTES, "图片")
                digest.update(bytes)
                val pageFile = File(dir, "page-%04d.png".format(index))
                pageFile.outputStream().use { output -> output.write(bytes) }
                // 验证确实是可解码图片
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(pageFile.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    throw IllegalArgumentException("第 ${index + 1} 张文件不是可识别的图片")
                }
                pagePaths += pageFile.toRelativeString(context.filesDir)
                totalBytes += bytes.size
            }
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
        StoredSource(
            relativePath = dir.toRelativeString(context.filesDir),
            originalName = displayName,
            sizeBytes = totalBytes,
            contentSha256 = digest.digest().joinToString("") { "%02x".format(it) },
            pageCount = pagePaths.size,
            pageRelativePaths = pagePaths,
            type = ProblemDocumentType.IMAGES,
        )
    }

    fun resolveFile(relativePath: String): File = File(context.filesDir, relativePath)

    /**
     * PDFBox 的字体资源在首次解析 PDF 时按需初始化（V1.0 启动优化）。
     * 只执行一次；失败时抛出明确异常，避免静默降级成空文本。
     */
    fun ensurePdfBoxInitialized() {
        if (!pdfBoxInitialized.compareAndSet(false, true)) return
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
        } catch (error: Exception) {
            pdfBoxInitialized.set(false)
            throw IllegalStateException("PDF 文本解析组件初始化失败", error)
        }
    }

    /** 来源记录创建失败（如内容重复）时清理已复制的文件，避免孤儿文件。 */
    fun deleteStoredSource(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        val file = resolveFile(relativePath)
        // 防御性检查：只允许删除题库私有目录内的文件
        val root = rootDir.canonicalPath
        val target = file.canonicalPath
        if (!target.startsWith(root)) return false
        return if (file.isDirectory) file.deleteRecursively() else !file.exists() || file.delete()
    }

    /** 删除文档文件；仅在题库没有关联题目时由仓库层调用。 */
    fun deleteDocumentFiles(document: ProblemDocument): Boolean {
        val file = resolveFile(document.relativePath)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        return deleted || !file.exists()
    }

    private fun readLimited(uri: Uri, limitBytes: Int, kind: String): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStreamSaver()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.total += read
                if (buffer.total > limitBytes) {
                    throw IllegalArgumentException("$kind 超过大小上限（${limitBytes / (1024 * 1024)} MB）")
                }
                buffer.data.write(chunk, 0, read)
            }
            buffer.data.toByteArray()
        } ?: throw IOException("无法读取所选文件")
        if (bytes.isEmpty()) throw IllegalArgumentException("$kind 文件为空")
        return bytes
    }

    private fun openPdfRenderer(file: File): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            throw IllegalArgumentException("无法打开 PDF，文件可能已损坏", error)
        }
    }

    private fun sanitizedFileName(displayName: String, fallbackExt: String): String {
        val base = displayName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").trim()
        val ext = displayName.substringAfterLast('.', fallbackExt).lowercase()
        val safeBase = if (base.isBlank()) "document" else base.take(80)
        return "$safeBase.$ext"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class ByteArrayOutputStreamSaver {
        val data = java.io.ByteArrayOutputStream()
        var total = 0
    }

    private val pdfBoxInitialized = AtomicBoolean(false)

    companion object {
        const val ROOT_DIR_NAME = "problem_bank_documents"
        const val MAX_PDF_BYTES = 30 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_IMAGES = 100
        const val MAX_PAGES = 500
    }
}

/**
 * 已打开来源文档的页面访问句柄。批量处理期间保持打开，处理结束或取消时关闭。
 */
interface ExercisePageSource : AutoCloseable {
    val document: ProblemDocument

    /** 页面内嵌文本（PDF）。无内嵌文本返回空串，由流水线决定是否 OCR。 */
    suspend fun embeddedText(pageIndex: Int): String

    /** 渲染页面位图（长边不超过 maxDimension），用于 OCR 与「查看原始页面」。 */
    suspend fun renderPage(pageIndex: Int, maxDimension: Int): Bitmap
}

interface ExercisePageSourceFactory {
    suspend fun open(document: ProblemDocument): ExercisePageSource
}

/** PDF 来源：PdfRenderer 渲染 + PDFBox 内嵌文本提取。 */
class PdfPageSource(
    override val document: ProblemDocument,
    private val file: File,
) : ExercisePageSource {

    private val renderer: PdfRenderer = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).let {
        try {
            PdfRenderer(it)
        } catch (error: Exception) {
            runCatching { it.close() }
            throw IllegalArgumentException("无法打开 PDF，文件可能已损坏", error)
        }
    }
    private val pdfDocument: PDDocument = try {
        PDDocument.load(file)
    } catch (error: Exception) {
        runCatching { renderer.close() }
        throw IllegalArgumentException("无法解析 PDF 文本，文件可能已损坏", error)
    }

    init {
        require(document.pageCount in 1..renderer.pageCount) { "PDF 页数与记录不一致" }
    }

    override suspend fun embeddedText(pageIndex: Int): String = withContext(Dispatchers.IO) {
        if (pageIndex !in 0 until pdfDocument.numberOfPages) return@withContext ""
        val stripper = PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }
        stripper.getText(pdfDocument).trim()
    }

    override suspend fun renderPage(pageIndex: Int, maxDimension: Int): Bitmap =
        withContext(Dispatchers.IO) {
            if (pageIndex !in 0 until renderer.pageCount) {
                throw IllegalArgumentException("页码超出范围：${pageIndex + 1}")
            }
            val page = renderer.openPage(pageIndex)
            try {
                val scale = minOf(
                    maxDimension.toFloat() / page.width.coerceAtLeast(1),
                    maxDimension.toFloat() / page.height.coerceAtLeast(1),
                    1f,
                )
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }

    override fun close() {
        runCatching { pdfDocument.close() }
        runCatching { renderer.close() }
    }
}

/** 图片来源：每页一个图片文件，没有内嵌文本（始终走 OCR）。 */
class ImagesPageSource(
    override val document: ProblemDocument,
    private val pageFiles: List<File>,
) : ExercisePageSource {

    override suspend fun embeddedText(pageIndex: Int): String = ""

    override suspend fun renderPage(pageIndex: Int, maxDimension: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val file = pageFiles.getOrNull(pageIndex)
                ?: throw IllegalArgumentException("页码超出范围：${pageIndex + 1}")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw IllegalArgumentException("页面文件无法解码，可能已损坏")
            }
            var sample = 1
            var longest = maxOf(options.outWidth, options.outHeight)
            while (longest / (sample * 2) >= maxDimension) sample *= 2
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: throw IllegalArgumentException("页面文件无法解码，可能已损坏")
            scaleDown(decoded, maxDimension)
        }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    override fun close() = Unit
}

class DefaultExercisePageSourceFactory(
    private val store: ProblemDocumentStore,
) : ExercisePageSourceFactory {
    override suspend fun open(document: ProblemDocument): ExercisePageSource = withContext(Dispatchers.IO) {
        when (document.type) {
            ProblemDocumentType.PDF -> {
                store.ensurePdfBoxInitialized()
                PdfPageSource(document, store.resolveFile(document.relativePath))
            }
            ProblemDocumentType.IMAGES -> {
                val dir = store.resolveFile(document.relativePath)
                val files = document.pageIndices().map { index ->
                    File(dir, "page-%04d.png".format(index))
                }
                ImagesPageSource(document, files)
            }
        }
    }

    private fun ProblemDocument.pageIndices(): List<Int> = (0 until pageCount).toList()
}
