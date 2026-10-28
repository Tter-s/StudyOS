package com.zhixu.studyos.core.importer.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.ScheduleImportException
import com.zhixu.studyos.core.importer.ScheduleImportRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class PickedScheduleImage(
    val bytes: ByteArray,
    val mimeType: String,
    /** EXIF orientation expressed as clockwise quarter turns; mirrored EXIF is not applied. */
    val exifQuarterTurns: Int,
    /** Already EXIF-oriented, downsampled bitmap used only for the preview and crop UI. */
    val previewBitmap: Bitmap,
)

object ScheduleImageLoader {
    const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
    private const val PREVIEW_MAX_DIMENSION = 1600
    private const val OCR_MAX_DIMENSION = 2600

    fun load(contentResolver: ContentResolver, uri: Uri): PickedScheduleImage {
        val declaredSize = querySize(contentResolver, uri)
        if (declaredSize != null && declaredSize > MAX_IMAGE_BYTES) {
            throw ScheduleImportException("图片超过 25 MB，请先裁剪或压缩后再导入。")
        }
        val bytes = try {
            contentResolver.openInputStream(uri)?.use(::readCapped)
        } catch (error: ScheduleImportException) {
            throw error
        } catch (error: Exception) {
            throw ScheduleImportException("无法读取所选图片，请重新选择。", error)
        } ?: throw ScheduleImportException("无法打开所选图片，请重新选择。")
        if (bytes.isEmpty()) throw ScheduleImportException("所选图片为空，请重新选择。")

        val exifTurns = exifQuarterTurns(bytes)
        val decoded = decodeSampled(bytes, PREVIEW_MAX_DIMENSION)
        val preview = if (exifTurns == 0) decoded else rotate(decoded, exifTurns)
        if (preview !== decoded) decoded.recycle()
        return PickedScheduleImage(
            bytes = bytes,
            mimeType = contentResolver.getType(uri) ?: "image/*",
            exifQuarterTurns = exifTurns,
            previewBitmap = preview,
        )
    }

    fun readCapped(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_IMAGE_BYTES) {
                throw ScheduleImportException("图片超过 25 MB，请先裁剪或压缩后再导入。")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun exifQuarterTurns(bytes: ByteArray): Int = try {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        orientationToQuarterTurns(
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
        )
    } catch (_: Exception) {
        0
    }

    internal fun orientationToQuarterTurns(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSPOSE,
        -> 1

        ExifInterface.ORIENTATION_ROTATE_180,
        -> 2

        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSVERSE,
        -> 3

        else -> 0
    }

    fun rotate(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = Math.floorMod(quarterTurns, 4)
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(turns * 90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun crop(bitmap: Bitmap, crop: NormalizedCrop): Bitmap {
        val safe = crop.clamped()
        if (safe.left <= 0.0001f && safe.top <= 0.0001f &&
            safe.right >= 0.9999f && safe.bottom >= 0.9999f
        ) {
            return bitmap
        }
        val left = (safe.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (safe.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (safe.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (safe.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /**
     * Decodes the original bytes at OCR resolution, applies the total rotation
     * (EXIF + user rotation) and then the normalized crop.
     */
    fun decodeForRecognition(request: ScheduleImportRequest): Bitmap {
        val decoded = decodeSampled(request.imageBytes, OCR_MAX_DIMENSION)
        val rotated = rotate(decoded, request.rotationQuarterTurns)
        if (rotated !== decoded) decoded.recycle()
        val cropped = crop(rotated, request.crop)
        if (cropped !== rotated) rotated.recycle()
        if (cropped.width < 32 || cropped.height < 32) {
            cropped.recycle()
            throw ScheduleImportException("裁剪区域过小，请扩大框选范围后重试。")
        }
        return cropped
    }

    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            throw ScheduleImportException("无法解码所选图片，请使用 JPG、PNG 或 WebP 截图。")
        }
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (error: OutOfMemoryError) {
            throw ScheduleImportException("图片过大，设备内存不足；请先裁剪或压缩后重试。", error)
        } ?: throw ScheduleImportException("无法解码所选图片，请使用 JPG、PNG 或 WebP 截图。")
    }

    private fun querySize(contentResolver: ContentResolver, uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { !cursor.isNull(0) } else null
        }
    } catch (_: Exception) {
        null
    }
}
