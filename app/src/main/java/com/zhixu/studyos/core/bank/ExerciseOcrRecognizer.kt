package com.zhixu.studyos.core.bank

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 习题页面的本地 OCR（与课表导入同一套随 APK 打包的 ML Kit 中文模型）。
 * 图片只在设备上处理；识别失败抛出异常，由流水线按页记录错误，不影响其他页。
 */
class ExerciseOcrRecognizer {

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            }
            text.text.trim()
        } catch (error: CancellationException) {
            throw error
        } finally {
            recognizer.close()
        }
    }
}
