package com.zhixu.studyos.core.bank

import android.graphics.Bitmap
import com.zhixu.studyos.core.model.ProblemDocument

/**
 * 「查看原始页面」的渲染入口。
 * 页面始终从应用私有目录保存的原始文件渲染，不依赖外部 Uri 的生命周期。
 */
class ProblemPageRenderer(
    private val sourceFactory: ExercisePageSourceFactory,
) {
    suspend fun render(document: ProblemDocument, pageIndex: Int, maxDimension: Int): Bitmap =
        sourceFactory.open(document).use { source ->
            source.renderPage(pageIndex, maxDimension)
        }
}
