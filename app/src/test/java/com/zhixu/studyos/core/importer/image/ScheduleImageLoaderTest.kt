package com.zhixu.studyos.core.importer.image

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhixu.studyos.core.importer.NormalizedCrop
import com.zhixu.studyos.core.importer.ScheduleImportException
import com.zhixu.studyos.core.importer.ScheduleImportRequest
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleImageLoaderTest {
    @Test
    fun rotateQuarterTurnSwapsDimensions() {
        val source = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)

        val rotated = ScheduleImageLoader.rotate(source, 1)

        assertEquals(60, rotated.width)
        assertEquals(120, rotated.height)
    }

    @Test
    fun decodeForRecognition_appliesRotationThenCrop() {
        val source = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.RED)
        val bytes = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }

        val decoded = ScheduleImageLoader.decodeForRecognition(
            ScheduleImportRequest(
                imageBytes = bytes,
                mimeType = "image/png",
                rotationQuarterTurns = 1,
                crop = NormalizedCrop(0f, 0f, 1f, 0.5f),
            ),
        )

        // 120x60 -> rotate 90° -> 60x120 -> keep top half -> 60x60.
        assertEquals(60, decoded.width)
        assertEquals(60, decoded.height)
    }

    @Test
    fun tooSmallCropIsRejected() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }

        assertThrows(ScheduleImportException::class.java) {
            ScheduleImageLoader.decodeForRecognition(
                ScheduleImportRequest(
                    imageBytes = bytes,
                    mimeType = "image/png",
                    crop = NormalizedCrop(0.1f, 0.1f, 0.15f, 0.15f),
                ),
            )
        }
    }

    @Test
    fun exifOrientationMapsToClockwiseQuarterTurns() {
        assertEquals(0, ScheduleImageLoader.orientationToQuarterTurns(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(1, ScheduleImageLoader.orientationToQuarterTurns(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(2, ScheduleImageLoader.orientationToQuarterTurns(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(3, ScheduleImageLoader.orientationToQuarterTurns(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun invalidBytesFailWithUserFacingError() {
        assertThrows(ScheduleImportException::class.java) {
            ScheduleImageLoader.decodeForRecognition(
                ScheduleImportRequest(
                    imageBytes = byteArrayOf(1, 2, 3),
                    mimeType = "image/jpeg",
                ),
            )
        }
    }
}
