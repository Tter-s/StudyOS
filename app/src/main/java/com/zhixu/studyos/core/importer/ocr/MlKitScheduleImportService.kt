package com.zhixu.studyos.core.importer.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.zhixu.studyos.core.importer.OcrDocument
import com.zhixu.studyos.core.importer.OcrLine
import com.zhixu.studyos.core.importer.ScheduleImportException
import com.zhixu.studyos.core.importer.ScheduleImportMethod
import com.zhixu.studyos.core.importer.ScheduleImportRequest
import com.zhixu.studyos.core.importer.ScheduleImportResult
import com.zhixu.studyos.core.importer.ScheduleImportService
import com.zhixu.studyos.core.importer.ScheduleOcrParser
import com.zhixu.studyos.core.importer.ScheduleParseOptions
import com.zhixu.studyos.core.importer.image.ScheduleImageLoader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Bundled on-device Chinese/Latin OCR. The image never leaves the device and
 * the app does not declare INTERNET permission. Recognition is only one
 * provider behind [ScheduleImportService]; parsing and validation share the
 * same pure Kotlin path as any future provider.
 */
class MlKitScheduleImportService(
    private val parser: ScheduleOcrParser = ScheduleOcrParser(),
) : ScheduleImportService {
    override val method: ScheduleImportMethod = ScheduleImportMethod.LOCAL_OCR
    override val displayName: String = "本地 OCR（离线）"

    override suspend fun recognize(request: ScheduleImportRequest): ScheduleImportResult {
        if (request.imageBytes.isEmpty()) throw ScheduleImportException("没有可识别的图片。")
        val started = SystemClock.elapsedRealtime()
        val bitmap = withContext(Dispatchers.Default) {
            ScheduleImageLoader.decodeForRecognition(request)
        }
        try {
            val text = recognizeText(bitmap)
            val document = withContext(Dispatchers.Default) {
                buildDocument(text, bitmap.width, bitmap.height)
            }
            val parsed = withContext(Dispatchers.Default) {
                parser.parse(
                    document = document,
                    options = ScheduleParseOptions(
                        fallbackGrid = request.fallbackGrid,
                        maxWeek = request.maxWeek,
                    ),
                )
            }
            return ScheduleImportResult(
                method = method,
                parsed = parsed,
                elapsedMillis = SystemClock.elapsedRealtime() - started,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: ScheduleImportException) {
            throw error
        } catch (error: Exception) {
            throw ScheduleImportException("本地 OCR 识别失败，原课表未做任何修改。", error)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ScheduleImportException(
                                    "本地 OCR 识别失败，原课表未做任何修改。",
                                    error,
                                ),
                            )
                        }
                    }
            }
        } finally {
            recognizer.close()
        }
    }

    private fun buildDocument(text: Text, width: Int, height: Int): OcrDocument {
        if (width <= 0 || height <= 0) return OcrDocument(emptyList())
        val safeWidth = width.toFloat()
        val safeHeight = height.toFloat()
        val lines = text.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val value = line.text.trim()
                if (value.isEmpty()) return@mapNotNull null
                OcrLine(
                    text = value,
                    left = box.left / safeWidth,
                    top = box.top / safeHeight,
                    right = box.right / safeWidth,
                    bottom = box.bottom / safeHeight,
                ).normalizedOrNull()
            }
            .sortedWith(compareBy(OcrLine::top, OcrLine::left))
        return OcrDocument(lines)
    }
}
