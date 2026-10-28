package com.zhixu.studyos.feature.schedule

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhixu.studyos.core.importer.NormalizedCrop
import kotlin.math.min

@Composable
fun ScheduleImageCropBox(
    bitmap: Bitmap,
    crop: NormalizedCrop,
    onCropChange: (NormalizedCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        if (widthPx <= 0 || heightPx <= 0) return@BoxWithConstraints
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        var imageWidth = widthPx.toFloat()
        var imageHeight = imageWidth / aspect
        if (imageHeight > heightPx) {
            imageHeight = heightPx.toFloat()
            imageWidth = imageHeight * aspect
        }
        val density = LocalDensity.current
        val imageWidthDp = with(density) { imageWidth.toDp() }
        val imageHeightDp = with(density) { imageHeight.toDp() }
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        Box(modifier = Modifier.size(imageWidthDp, imageHeightDp)) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "课表截图预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            ScheduleCropOverlay(
                crop = crop,
                onCropChange = onCropChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private enum class CropDragMode {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    MOVE,
}

@Composable
private fun ScheduleCropOverlay(
    crop: NormalizedCrop,
    onCropChange: (NormalizedCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCrop by rememberUpdatedState(crop)
    val touchRadius = with(LocalDensity.current) { 30.dp.toPx() }
    val minSize = 0.12f

    Box(
        modifier = modifier
            .semantics { contentDescription = "裁剪区域，拖动四角调整范围" }
            .pointerInput(Unit) {
                var working: NormalizedCrop? = null
                var mode: CropDragMode? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val active = currentCrop
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val corners = listOf(
                            CropDragMode.TOP_LEFT to Offset(active.left * width, active.top * height),
                            CropDragMode.TOP_RIGHT to Offset(active.right * width, active.top * height),
                            CropDragMode.BOTTOM_LEFT to Offset(active.left * width, active.bottom * height),
                            CropDragMode.BOTTOM_RIGHT to Offset(active.right * width, active.bottom * height),
                        )
                        val nearest = corners.minByOrNull { (_, point) -> (point - offset).getDistance() }
                        mode = when {
                            nearest != null && (nearest.second - offset).getDistance() <= touchRadius -> nearest.first
                            offset.x in active.left * width..active.right * width &&
                                offset.y in active.top * height..active.bottom * height -> CropDragMode.MOVE
                            else -> null
                        }
                        working = active
                    },
                    onDrag = drag@{ change, dragAmount ->
                        change.consume()
                        val activeMode = mode ?: return@drag
                        val start = working ?: return@drag
                        if (size.width <= 0 || size.height <= 0) return@drag
                        val dx = dragAmount.x / size.width
                        val dy = dragAmount.y / size.height
                        val updated = applyCropDrag(start, activeMode, dx, dy, minSize)
                        working = updated
                        onCropChange(updated)
                    },
                    onDragEnd = {
                        mode = null
                        working = null
                    },
                    onDragCancel = {
                        mode = null
                        working = null
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = crop.left * size.width
            val top = crop.top * size.height
            val right = crop.right * size.width
            val bottom = crop.bottom * size.height
            val scrim = Color.Black.copy(alpha = 0.52f)
            val white = Color.White

            drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, top))
            drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
            drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

            drawRect(
                color = white.copy(alpha = 0.9f),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            for (index in 1..2) {
                val x = left + (right - left) * index / 3f
                val y = top + (bottom - top) * index / 3f
                drawLine(
                    color = white.copy(alpha = 0.28f),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = white.copy(alpha = 0.28f),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val arm = min(22.dp.toPx(), min(right - left, bottom - top) / 3f)
            val stroke = 3.dp.toPx()
            fun corner(x: Float, y: Float, horizontal: Float, vertical: Float) {
                drawLine(
                    color = white,
                    start = Offset(x, y),
                    end = Offset(x + horizontal * arm, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = white,
                    start = Offset(x, y),
                    end = Offset(x, y + vertical * arm),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            corner(left, top, 1f, 1f)
            corner(right, top, -1f, 1f)
            corner(left, bottom, 1f, -1f)
            corner(right, bottom, -1f, -1f)
        }
    }
}

private fun applyCropDrag(
    start: NormalizedCrop,
    mode: CropDragMode,
    dx: Float,
    dy: Float,
    minSize: Float,
): NormalizedCrop = when (mode) {
    CropDragMode.TOP_LEFT -> NormalizedCrop(
        left = (start.left + dx).coerceIn(0f, start.right - minSize),
        top = (start.top + dy).coerceIn(0f, start.bottom - minSize),
        right = start.right,
        bottom = start.bottom,
    )
    CropDragMode.TOP_RIGHT -> NormalizedCrop(
        left = start.left,
        top = (start.top + dy).coerceIn(0f, start.bottom - minSize),
        right = (start.right + dx).coerceIn(start.left + minSize, 1f),
        bottom = start.bottom,
    )
    CropDragMode.BOTTOM_LEFT -> NormalizedCrop(
        left = (start.left + dx).coerceIn(0f, start.right - minSize),
        top = start.top,
        right = start.right,
        bottom = (start.bottom + dy).coerceIn(start.top + minSize, 1f),
    )
    CropDragMode.BOTTOM_RIGHT -> NormalizedCrop(
        left = start.left,
        top = start.top,
        right = (start.right + dx).coerceIn(start.left + minSize, 1f),
        bottom = (start.bottom + dy).coerceIn(start.top + minSize, 1f),
    )
    CropDragMode.MOVE -> {
        val width = start.right - start.left
        val height = start.bottom - start.top
        val left = (start.left + dx).coerceIn(0f, 1f - width)
        val top = (start.top + dy).coerceIn(0f, 1f - height)
        NormalizedCrop(left, top, left + width, top + height)
    }
}
