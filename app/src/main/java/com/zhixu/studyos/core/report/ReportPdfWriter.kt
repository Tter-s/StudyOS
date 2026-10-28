package com.zhixu.studyos.core.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * 周报 PDF 导出。使用系统 PdfDocument 与默认 CJK 字体回退，全部在本地生成。
 * 导出失败只影响文件，不影响数据库中的原始学习记录。
 */
object ReportPdfWriter {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    fun write(
        report: WeeklyReport,
        output: OutputStream,
        aiSections: AiReportSections? = null,
    ) {
        val document = PdfDocument()
        val writer = PageWriter(document)
        val titlePaint = Paint().apply {
            color = Color.rgb(32, 52, 84)
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        val headingPaint = Paint().apply {
            color = Color.rgb(42, 74, 120)
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.rgb(40, 40, 40)
            textSize = 11f
            isAntiAlias = true
        }
        val mutedPaint = Paint().apply {
            color = Color.rgb(100, 105, 115)
            textSize = 9f
            isAntiAlias = true
        }
        val linePaint = Paint().apply {
            color = Color.rgb(205, 212, 224)
            strokeWidth = 0.8f
        }

        writer.drawText("知序 StudyOS 学习周报", titlePaint)
        writer.drawText(
            "${report.weekStart} 至 ${report.weekEnd} · ${if (report.mode == ReportMode.AI) "含 AI 分析" else "离线统计"}",
            bodyPaint,
        )
        writer.drawText("生成时间：${report.generatedAt}", mutedPaint)
        writer.spacer(8f)

        writer.section("本周学习概览", headingPaint)
        writer.drawText("学习总时长：${report.overview.studyMinutes} 分钟 · 活跃 ${report.overview.activeDays} 天", bodyPaint)
        writer.drawText(
            "任务：${report.overview.completedTasks}/${report.overview.scheduledTasks} 完成" +
                (report.overview.completionRatePercent?.let { "（$it%）" } ?: "（数据不足）"),
            bodyPaint,
        )
        writer.drawText(
            "习题尝试：${report.overview.exerciseAttempts} 次 · 独立完成率 " +
                (report.overview.independentRatePercent?.let { "$it%" } ?: "数据不足"),
            bodyPaint,
        )
        writer.drawText("词汇复习：${report.overview.vocabularyReviews} 次", bodyPaint)
        writer.drawText("平均睡眠：${formatMinutes(report.overview.averageSleepMinutes)}", bodyPaint)

        writer.section("各科目时间分配", headingPaint)
        if (report.subjects.isEmpty()) {
            writer.drawText("本期没有科目计时或计划记录。", mutedPaint)
        } else {
            report.subjects.forEach { subject ->
                writer.drawText(
                    "${subject.subject}：${subject.studyMinutes} 分钟 · 计划 ${subject.scheduledTasks} 项 / " +
                        "完成 ${subject.completedTasks} 项",
                    bodyPaint,
                )
            }
        }

        writer.section("学习效率趋势（每日计时）", headingPaint)
        report.daily.forEach { day ->
            writer.drawText(
                "${day.date}：${day.studyMinutes} 分钟 · 任务 ${day.completedTasks}/${day.scheduledTasks}",
                bodyPaint,
            )
        }

        writer.section("计划执行情况", headingPaint)
        writer.drawText(
            "计划 ${report.planExecution.plannedMinutes} 分钟，实际计时 ${report.planExecution.actualMinutes} 分钟，" +
                "偏差 ${report.planExecution.deviationMinutes} 分钟" +
                (report.planExecution.deviationPercent?.let { "（$it%）" } ?: ""),
            bodyPaint,
        )
        writer.drawText(report.planExecution.interpretation, mutedPaint)

        writer.section("数学习题表现", headingPaint)
        writer.drawText("尝试 ${report.exercise.attempts} 次，其中首次尝试 ${report.exercise.firstAttempts} 次", bodyPaint)
        writer.drawText(
            "独立完成 ${report.exercise.independentAttempts} 次；首次尝试独立率 " +
                (report.exercise.firstAttemptIndependentRatePercent?.let { "$it%" } ?: "数据不足"),
            bodyPaint,
        )
        writer.drawText(
            "使用提示 ${report.exercise.hintUseCount} 次" +
                (report.exercise.averageHintLevel?.let { "，平均最高等级 $it" } ?: ""),
            bodyPaint,
        )
        writer.drawText(
            "高等级提示题成功 ${report.exercise.difficultAttempts} 次尝试" +
                (report.exercise.difficultSuccessRatePercent?.let { "，成功率 $it%" } ?: ""),
            bodyPaint,
        )

        writer.section("英语词汇复习", headingPaint)
        writer.drawText(
            "复习 ${report.vocabulary.reviews} 次，回忆 ${report.vocabulary.recalled} 次" +
                (report.vocabulary.recallRatePercent?.let { "（$it%）" } ?: "（数据不足）") +
                "；新增词 ${report.vocabulary.newWords} 个",
            bodyPaint,
        )

        writer.section("睡眠与自控", headingPaint)
        writer.drawText(
            "睡眠记录 ${report.sleep.recordedDays} 天，平均 ${formatMinutes(report.sleep.averageDurationMinutes)}" +
                (report.sleep.regularityMinutes?.let { "，波动约 ±$it 分钟" } ?: ""),
            bodyPaint,
        )
        writer.drawText(
            "中断 ${report.selfControl.interruptions} 次，主动中断 ${report.selfControl.voluntaryInterruptions} 次，" +
                "拖延记录 ${report.selfControl.procrastinationRecords} 条",
            bodyPaint,
        )

        writer.section("任务积压", headingPaint)
        writer.drawText("本期延期任务 ${report.backlog.postponedTasks} 项", bodyPaint)
        writer.drawText("长期积压（延期 ≥3 次）${report.backlog.longBacklogTasks} 项", bodyPaint)
        writer.drawText("临近或已过截止的未完成任务 ${report.backlog.overdueTasks} 项", bodyPaint)

        writer.section("下周改进建议（规则生成）", headingPaint)
        report.suggestions.forEach { suggestion ->
            writer.drawText("· $suggestion", bodyPaint)
        }

        if (aiSections != null) {
            writer.section("AI 分析（用户主动请求）", headingPaint)
            if (aiSections.facts.isNotEmpty()) {
                writer.drawText("客观数据复述：", mutedPaint)
                aiSections.facts.forEach { writer.drawText("· $it", bodyPaint) }
            }
            if (aiSections.speculation.isNotEmpty()) {
                writer.drawText("推测（非已证明事实）：", mutedPaint)
                aiSections.speculation.forEach { writer.drawText("· $it", bodyPaint) }
            }
            if (aiSections.suggestions.isNotEmpty()) {
                writer.drawText("建议：", mutedPaint)
                aiSections.suggestions.forEach { writer.drawText("· $it", bodyPaint) }
            }
            writer.drawText(aiSections.disclaimer, mutedPaint)
        }

        writer.section("指标定义", headingPaint)
        report.metrics.forEach { metric ->
            writer.drawText("${metric.label}：${metric.definition}", mutedPaint)
        }
        if (report.dataNotes.isNotEmpty()) {
            writer.section("数据说明", headingPaint)
            report.dataNotes.forEach { writer.drawText("· $it", mutedPaint) }
        }
        writer.spacer(6f)
        writer.drawText("本报告只汇总本地真实记录；未记录的学习不会自动出现。学习时长不等于学习效果。", mutedPaint)
        writer.finish()
        document.writeTo(output)
        document.close()
    }

    private fun formatMinutes(minutes: Long?): String {
        if (minutes == null) return "数据不足"
        return "${minutes / 60} 小时 ${minutes % 60} 分"
    }

    private class PageWriter(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = MARGIN

        private fun ensurePage() {
            if (page == null) {
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
                val newPage = document.startPage(info)
                page = newPage
                canvas = newPage.canvas
                y = MARGIN
            }
        }

        fun section(title: String, paint: Paint) {
            ensurePage()
            if (y > PAGE_HEIGHT - MARGIN - 40) newPage()
            y += 6f
            drawText(title, paint)
            val current = canvas ?: return
            val linePaint = Paint().apply {
                color = Color.rgb(205, 212, 224)
                strokeWidth = 0.8f
            }
            current.drawLine(MARGIN, y + 2f, PAGE_WIDTH - MARGIN, y + 2f, linePaint)
            y += 6f
        }

        fun drawText(text: String, paint: Paint) {
            ensurePage()
            val currentCanvas = canvas ?: return
            val lines = wrap(text, paint, CONTENT_WIDTH)
            val lineHeight = paint.textSize * 1.45f
            lines.forEach { line ->
                if (y + lineHeight > PAGE_HEIGHT - MARGIN) newPage()
                canvas?.drawText(line, MARGIN, y + paint.textSize, paint)
                y += lineHeight
            }
        }

        fun spacer(height: Float) {
            ensurePage()
            y += height
        }

        private fun newPage() {
            page?.let { document.finishPage(it) }
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create()
            val newPage = document.startPage(info)
            page = newPage
            canvas = newPage.canvas
            y = MARGIN
        }

        fun finish() {
            page?.let { document.finishPage(it) }
            page = null
            canvas = null
        }

        private fun wrap(text: String, paint: Paint, width: Float): List<String> {
            if (text.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            var remaining = text
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
                result += remaining.take(count)
                remaining = remaining.drop(count)
            }
            return result
        }
    }
}
