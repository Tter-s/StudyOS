package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.WeekParity
import java.io.BufferedReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleOcrParserTest {
    private val parser = ScheduleOcrParser()

    @Test
    fun sampleScreenshot_parsesAllCoursesFieldsAndTimes() {
        val parsed = parser.parse(document("timetable_sample_1.tsv"))

        assertEquals(7, parsed.courses.size)
        assertEquals(0, parsed.unparsedLineCount)
        assertTrue(parsed.fallbackPeriods.isEmpty())
        assertTrue(parsed.warnings.none { it.contains("未识别到课程") })

        val analysis = parsed.courses.single { it.name == "数学分析" }
        assertEquals(1, analysis.dayOfWeek)
        assertEquals(1, analysis.periodStart)
        assertEquals(2, analysis.periodEnd)
        assertEquals(1, analysis.startWeek)
        assertEquals(16, analysis.endWeek)
        assertEquals(WeekParity.ALL, analysis.weekParity)
        assertEquals("A101", analysis.classroom)
        assertEquals("王老师", analysis.teacher)
        assertTrue(analysis.needsReview.isEmpty())

        val algebra = parsed.courses.single { it.name == "高等代数" }
        assertEquals(2, algebra.dayOfWeek)
        assertEquals(3, algebra.periodStart)
        assertEquals(4, algebra.periodEnd)
        assertEquals(WeekParity.ODD, algebra.weekParity)
        assertEquals(1, algebra.startWeek)
        assertEquals(8, algebra.endWeek)
        assertEquals("C305", algebra.classroom)
        assertTrue(algebra.needsReview.isEmpty())

        val geometry = parsed.courses.single { it.name == "空间解析几何" }
        assertEquals(6, geometry.periodStart)
        assertEquals(WeekParity.EVEN, geometry.weekParity)
        assertEquals(2, geometry.startWeek)
        assertEquals(16, geometry.endWeek)

        val python = parsed.courses.single { it.name == "Python 程序设计" }
        assertEquals("机房 A-203", python.classroom)
        assertEquals(7, python.periodStart)

        val english = parsed.courses.single { it.name == "大学英语" }
        assertEquals(4, english.periodStart)
        assertEquals(3, english.startWeek)
        assertEquals(18, english.endWeek)

        val policy = parsed.courses.single { it.name == "形势与政策" }
        assertEquals("E201", policy.classroom)
        assertEquals(8, policy.periodStart)
        assertEquals(5, policy.startWeek)
        assertEquals(12, policy.endWeek)
    }

    @Test
    fun noWeekdayHeader_returnsNoCoursesAndExplainsWhy() {
        val parsed = parser.parse(
            OcrDocument(
                listOf(
                    line("高等代数", 0.2f, 0.3f, 0.4f, 0.32f),
                    line("A101", 0.2f, 0.33f, 0.4f, 0.35f),
                ),
            ),
        )

        assertTrue(parsed.courses.isEmpty())
        assertTrue(parsed.warnings.any { it.contains("星期表头") })
    }

    @Test
    fun missingClassroomAndWeeks_areMarkedForReviewNotInvented() {
        val parsed = parser.parse(
            valDocument(
                courseLines = listOf(
                    line("线性代数", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("1-16周", 0.15f, 0.24f, 0.25f, 0.26f),
                ),
            ),
        )

        val course = parsed.courses.single()
        assertEquals("线性代数", course.name)
        assertEquals("", course.classroom)
        assertEquals(1, course.periodStart)
        assertTrue(ScheduleImportField.CLASSROOM in course.needsReview)
        assertFalse(ScheduleImportField.WEEKS in course.needsReview)
        assertTrue(course.notes.any { it.contains("教室") })
    }

    @Test
    fun noClockLabels_usesFallbackGridAndRequiresReview() {
        val parsed = parser.parse(
            valDocument(
                withTimeLabels = false,
                courseLines = listOf(
                    line("数学分析", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("A101", 0.15f, 0.24f, 0.25f, 0.26f),
                    line("1-16周", 0.15f, 0.28f, 0.25f, 0.30f),
                ),
            ),
        )

        val course = parsed.courses.single()
        assertEquals(1, course.periodStart)
        assertTrue(1 in parsed.fallbackPeriods)
        assertTrue(parsed.warnings.any { it.contains("默认节次") })
    }

    @Test
    fun unsupportedWeekList_isMarkedForReview() {
        val parsed = parser.parse(
            valDocument(
                courseLines = listOf(
                    line("概率论", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("A101", 0.15f, 0.24f, 0.25f, 0.26f),
                    line("1-8周,10-16周", 0.15f, 0.28f, 0.25f, 0.30f),
                ),
            ),
        )

        val course = parsed.courses.single()
        assertTrue(ScheduleImportField.WEEKS in course.needsReview)
        assertTrue(course.notes.any { it.contains("多段") || it.contains("周次") })
    }

    @Test
    fun twoMergedCourseBlocks_areSplitBySecondWeekLine() {
        val parsed = parser.parse(
            valDocument(
                courseLines = listOf(
                    line("数学分析", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("A101", 0.15f, 0.225f, 0.25f, 0.245f),
                    line("1-16周", 0.15f, 0.25f, 0.25f, 0.27f),
                    line("高等代数", 0.15f, 0.275f, 0.25f, 0.295f),
                    line("B202", 0.15f, 0.30f, 0.25f, 0.32f),
                    line("1-16周", 0.15f, 0.325f, 0.25f, 0.345f),
                ),
            ),
        )

        assertEquals(parsed.courses.joinToString { "${it.name}|${it.sourceText}" }, 2, parsed.courses.size)
        assertTrue(parsed.courses.joinToString { "${it.name}|${it.sourceText}" }, parsed.courses.any { it.name == "数学分析" })
        assertTrue(parsed.courses.joinToString { "${it.name}|${it.sourceText}" }, parsed.courses.any { it.name == "高等代数" })
    }

    @Test
    fun courseWithoutPeriodLabels_stillMarksPeriodForReview() {
        val parsed = parser.parse(
            OcrDocument(
                listOf(
                    line("星期一", 0.17f, 0.10f, 0.23f, 0.12f),
                    line("星期二", 0.29f, 0.10f, 0.35f, 0.12f),
                    line("星期三", 0.41f, 0.10f, 0.47f, 0.12f),
                    line("数学分析", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("A101", 0.15f, 0.24f, 0.25f, 0.26f),
                    line("1-16周", 0.15f, 0.28f, 0.25f, 0.30f),
                ),
            ),
        )

        val course = parsed.courses.single()
        assertTrue(ScheduleImportField.PERIOD in course.needsReview)
        assertTrue(parsed.fallbackPeriods.isEmpty())
    }

    @Test
    fun textCenteredOnRowBoundaryIsTreatedAsAmbiguousSpan() {
        val parsed = parser.parse(
            valDocument(
                courseLines = listOf(
                    line("线性代数", 0.15f, 0.235f, 0.25f, 0.250f),
                    line("A101", 0.15f, 0.250f, 0.25f, 0.265f),
                    line("1-16周", 0.15f, 0.240f, 0.25f, 0.255f),
                ),
            ),
        )

        val course = parsed.courses.single()
        assertEquals(1, course.periodStart)
        assertEquals(2, course.periodEnd)
        assertTrue(ScheduleImportField.PERIOD in course.needsReview)
        assertTrue(course.notes.any { it.contains("跨") })
    }

    @Test
    fun footerTextBelowGridDoesNotBecomeACourse() {
        val parsed = parser.parse(
            valDocument(
                courseLines = listOf(
                    line("数学分析", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("A101", 0.15f, 0.24f, 0.25f, 0.26f),
                    line("1-16周", 0.15f, 0.28f, 0.25f, 0.30f),
                    line("合成测试样例 · 非真实教务系统截图", 0.20f, 0.94f, 0.80f, 0.96f),
                ),
            ),
        )

        assertEquals(1, parsed.courses.size)
        assertEquals("数学分析", parsed.courses.single().name)
    }

    @Test
    fun emptyDocument_hasExplicitFailureWarning() {
        val parsed = parser.parse(OcrDocument(emptyList()))

        assertTrue(parsed.courses.isEmpty())
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.warnings.single().contains("没有识别到文字"))
    }

    @Test
    fun fullWidthDigitsAndPunctuation_areNormalized() {
        val parsed = parser.parse(
            valDocument(
                courseLines = listOf(
                    line("大学物理", 0.15f, 0.20f, 0.25f, 0.22f),
                    line("Ａ１０１", 0.15f, 0.24f, 0.25f, 0.26f),
                    line("１－１６周", 0.15f, 0.28f, 0.25f, 0.30f),
                ),
            ),
        )

        val course = parsed.courses.single()
        assertEquals("A101", course.classroom)
        assertEquals(1, course.startWeek)
        assertEquals(16, course.endWeek)
    }

    private fun document(name: String): OcrDocument {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("ocr/$name")) {
            "缺少测试样例 $name"
        }
        val lines = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readLines)
            .filter(String::isNotBlank)
            .map { row ->
                val parts = row.split("|")
                require(parts.size == 5) { "样例列数错误：$row" }
                OcrLine(
                    text = parts[0],
                    left = parts[1].toFloat(),
                    top = parts[2].toFloat(),
                    right = parts[3].toFloat(),
                    bottom = parts[4].toFloat(),
                )
            }
        return OcrDocument(lines)
    }

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        OcrLine(text, left, top, right, bottom)

    /** Minimal valid 3-column header with two period labels, optionally with clock text. */
    private fun valDocument(
        withTimeLabels: Boolean = true,
        courseLines: List<OcrLine>,
    ): OcrDocument {
        val header = listOf(
            line("时间", 0.02f, 0.10f, 0.08f, 0.12f),
            line("星期一", 0.17f, 0.10f, 0.23f, 0.12f),
            line("星期二", 0.29f, 0.10f, 0.35f, 0.12f),
            line("星期三", 0.41f, 0.10f, 0.47f, 0.12f),
        )
        val periodRows = buildList {
            val centers = listOf(0.20f, 0.30f, 0.40f, 0.50f)
            val times = listOf("08:00 - 08:45", "08:55 - 09:40", "10:00 - 10:45", "10:55 - 11:40")
            centers.forEachIndexed { index, center ->
                add(line("${index + 1}", 0.025f, center - 0.007f, 0.04f, center + 0.007f))
                if (withTimeLabels) {
                    add(line(times[index], 0.045f, center - 0.007f, 0.115f, center + 0.007f))
                }
            }
        }
        return OcrDocument(header + periodRows + courseLines)
    }
}
