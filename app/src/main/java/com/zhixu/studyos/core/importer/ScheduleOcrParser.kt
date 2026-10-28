package com.zhixu.studyos.core.importer

import com.zhixu.studyos.core.model.WeekParity
import kotlin.math.abs

/**
 * Deterministic layout parser for university timetable screenshots.
 *
 * OCR only supplies text lines and boxes. This parser finds the weekday header,
 * the period column, groups text into course cells, parses weeks/periods/rooms,
 * and marks everything it cannot prove as needing review. It never guesses a
 * missing required field silently.
 */
class ScheduleOcrParser {
    fun parse(
        document: OcrDocument,
        options: ScheduleParseOptions = ScheduleParseOptions(),
    ): ParsedSchedule {
        val lines = document.lines.mapNotNull(::normalize).filter { it.text.isNotBlank() }
        val fallbackGrid = options.fallbackGrid

        if (lines.isEmpty()) {
            return ParsedSchedule(
                courses = emptyList(),
                grid = fallbackGrid,
                fallbackPeriods = emptySet(),
                warnings = listOf("图片中没有识别到文字，请换一张更清晰的课表截图。"),
                textLineCount = 0,
                unparsedLineCount = 0,
            )
        }

        val header = findDayHeader(lines)
            ?: return ParsedSchedule(
                courses = emptyList(),
                grid = fallbackGrid,
                fallbackPeriods = emptySet(),
                warnings = listOf(
                    "未识别到完整的星期表头（周一至周日）。请裁剪到包含星期表头和节次列的完整课表区域后重试。",
                ),
                textLineCount = lines.size,
                unparsedLineCount = 0,
            )

        val warnings = mutableListOf<String>()
        val leftBoundary = header.columns.first().leftBoundary
        val periodAnchors = findPeriodAnchors(lines, leftBoundary)
        val rowGeometry = buildRowGeometry(periodAnchors)
        val gridResolution = resolveGrid(periodAnchors, lines, leftBoundary, fallbackGrid)
        warnings += gridResolution.warnings

        if (periodAnchors.isEmpty()) {
            warnings += "未识别到节次编号，所有课程的节次将标记为待确认。"
        } else if (periodAnchors.map { it.number } != (periodAnchors.first().number..periodAnchors.last().number).toList()) {
            warnings += "节次编号不连续，按网格推断的节次可能不准确，请逐项核对。"
        }
        if (rowGeometry == null) {
            warnings += "节次行位置不足，无法可靠推断节次；请保留左侧节次列后重试。"
        }

        val courseLines = lines.filter { line ->
            line.centerY > header.bottom &&
                (rowGeometry == null || rowGeometry.contains(line.centerY)) &&
                line.centerX >= header.columns.first().leftBoundary &&
                line.centerX < header.columns.last().rightBoundary
        }

        val candidateGroups = mutableListOf<Pair<DayColumn, List<OcrLine>>>()
        header.columns.forEach { column ->
            val columnLines = courseLines
                .filter { it.centerX >= column.leftBoundary && it.centerX < column.rightBoundary }
                .sortedBy(OcrLine::top)
            candidateGroups += groupColumn(column, columnLines, rowGeometry)
                .map { column to it }
        }

        val courses = candidateGroups.mapNotNull { (column, group) ->
            parseGroup(
                column = column,
                group = group,
                geometry = rowGeometry,
                grid = gridResolution.grid,
                options = options,
            )
        }

        if (courses.isEmpty()) {
            warnings += "未识别到课程单元格。请确认截图清晰、网格完整，且课程文字位于星期列内。"
        }
        val unparsed = (candidateGroups.size - courses.size).coerceAtLeast(0)
        if (unparsed > 0) {
            warnings += "有 $unparsed 个文字块未能形成课程，已忽略；如为课程请手动补充。"
        }

        val usedPeriods = courses.flatMap { course ->
            val start = course.periodStart
            val end = course.periodEnd
            if (start != null && end != null && start <= end) (start..end).toList() else listOfNotNull(start, end)
        }.toSet()
        val fallbackPeriods = usedPeriods
            .filter { it in 1..gridResolution.grid.size && it !in gridResolution.parsedPeriods }
            .toSet()
        if (fallbackPeriods.isNotEmpty()) {
            warnings += "第 ${fallbackPeriods.sorted().joinToString("、")} 节未识别到钟点时间，已暂时使用默认节次时间表；导入前必须核对。"
        }

        return ParsedSchedule(
            courses = courses,
            grid = gridResolution.grid,
            fallbackPeriods = fallbackPeriods,
            warnings = warnings.distinct(),
            textLineCount = lines.size,
            unparsedLineCount = unparsed,
        )
    }

    // ---------------------------------------------------------------- header

    private fun findDayHeader(lines: List<OcrLine>): DayHeader? {
        val candidates = lines.mapNotNull { line ->
            if (line.centerY > 0.4f) return@mapNotNull null
            val match = dayHeaderRegex.find(line.text) ?: return@mapNotNull null
            val day = dayNumberMap[match.groupValues[2].single()] ?: return@mapNotNull null
            val remainder = line.text
                .removeRange(match.range)
                .trim(' ', ':', '-', '/', '|', '·', '(', ')')
            // A header can carry a short date or "Mon"; course cells are not
            // accepted here because they live below 40% of the image.
            if (remainder.length > 6) return@mapNotNull null
            DayCandidate(day, line)
        }
        if (candidates.isEmpty()) return null

        val byDay = candidates
            .groupBy(DayCandidate::dayOfWeek)
            .mapValues { (_, group) -> group.minBy { it.line.top } }
        val days = byDay.keys.sorted()
        if (days.size < 3) return null
        if (days != (days.first()..days.last()).toList()) return null

        val ordered = days.map(byDay::getValue)
        for (index in 1 until ordered.size) {
            if (ordered[index].line.centerX <= ordered[index - 1].line.centerX + 0.01f) return null
        }

        val centers = ordered.map { it.line.centerX }
        val spacings = centers.zipWithNext { first, second -> second - first }
        val medianSpacing = spacings.sorted()[spacings.size / 2]
        val columns = ordered.mapIndexed { index, candidate ->
            val left = if (index == 0) {
                centers.first() - medianSpacing / 2f
            } else {
                (centers[index - 1] + centers[index]) / 2f
            }
            val right = if (index == centers.lastIndex) {
                centers.last() + medianSpacing / 2f
            } else {
                (centers[index] + centers[index + 1]) / 2f
            }
            DayColumn(
                dayOfWeek = candidate.dayOfWeek,
                centerX = centers[index],
                leftBoundary = left.coerceIn(0f, 1f),
                rightBoundary = right.coerceIn(0f, 1f),
            )
        }
        val bottom = (ordered.maxOf { it.line.bottom } + 0.006f).coerceIn(0f, 0.6f)
        return DayHeader(columns, bottom)
    }

    // ---------------------------------------------------------- period layout

    private fun findPeriodAnchors(lines: List<OcrLine>, leftBoundary: Float): List<PeriodAnchor> {
        val candidates = lines
            .filter { it.centerX < leftBoundary + 0.004f }
            .mapNotNull { line ->
                val number = periodNumberInLeftColumn(line.text) ?: return@mapNotNull null
                if (number !in 1..MAX_IMPORT_PERIOD) return@mapNotNull null
                PeriodAnchor(number, line)
            }
            .sortedBy { it.line.centerY }

        if (candidates.size < 2) return candidates

        // Keep the longest strictly increasing subsequence of period numbers by
        // vertical position. This drops stray numbers such as a date in the
        // corner without throwing away the actual period column.
        val best = IntArray(candidates.size) { 1 }
        val previous = IntArray(candidates.size) { -1 }
        for (index in candidates.indices) {
            for (earlier in 0 until index) {
                if (candidates[earlier].number < candidates[index].number && best[earlier] + 1 > best[index]) {
                    best[index] = best[earlier] + 1
                    previous[index] = earlier
                }
            }
        }
        var end = 0
        for (index in candidates.indices) if (best[index] > best[end]) end = index
        if (best[end] < 2) return emptyList()
        val result = mutableListOf<PeriodAnchor>()
        var cursor = end
        while (cursor >= 0) {
            result.add(0, candidates[cursor])
            cursor = previous[cursor]
        }
        return result
    }

    private fun periodNumberInLeftColumn(text: String): Int? {
        val compact = text.replace(" ", "")
        periodLabelRegex.matchEntire(compact)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        periodWithSuffixRegex.matchEntire(compact)?.let { match ->
            return match.groupValues[1].toIntOrNull()
        }
        val leading = periodLeadingRegex.matchEntire(text) ?: return null
        return leading.groupValues[1].toIntOrNull()
    }

    private fun buildRowGeometry(anchors: List<PeriodAnchor>): RowGeometry? {
        if (anchors.size < 2) return null
        val centers = anchors
            .map { it.number to it.line.centerY }
            .sortedBy { it.first }
        val differences = centers.zipWithNext { first, second -> second.second - first.second }
            .filter { it > 0.004f }
        if (differences.isEmpty()) return null
        val rowHeight = differences.sorted()[differences.size / 2]
        val top = (centers.first().second - rowHeight / 2f).coerceAtLeast(0f)
        val bottom = (centers.last().second + rowHeight / 2f).coerceAtMost(1f)
        return RowGeometry(centers, rowHeight, top, bottom)
    }

    private fun resolveGrid(
        anchors: List<PeriodAnchor>,
        lines: List<OcrLine>,
        leftBoundary: Float,
        fallback: PeriodGrid,
    ): GridResolution {
        var grid = fallback
        val parsed = mutableSetOf<Int>()
        val warnings = mutableListOf<String>()
        if (anchors.isEmpty()) {
            return GridResolution(grid, parsed, warnings)
        }
        val centers = anchors.map { it.line.centerY }.sorted()
        val differences = centers.zipWithNext { first, second -> second - first }.filter { it > 0.004f }
        val rowHeight = differences.sorted().getOrNull(differences.size / 2) ?: 0.08f
        val timeLines = lines.filter {
            it.centerX < leftBoundary && timeRangeRegex.containsMatchIn(it.text)
        }
        if (timeLines.isEmpty()) {
            warnings += "未识别到节次时间。默认节次时间仅用于预览，导入前必须核对。"
            return GridResolution(grid, parsed, warnings)
        }

        val candidates = anchors.mapNotNull { anchor ->
            val nearest = timeLines.minByOrNull { abs(it.centerY - anchor.line.centerY) } ?: return@mapNotNull null
            if (abs(nearest.centerY - anchor.line.centerY) > rowHeight * 0.6f) return@mapNotNull null
            val range = parseTimeRange(nearest.text) ?: return@mapNotNull null
            anchor.number to range
        }

        candidates.sortedBy { it.first }.forEach { (number, range) ->
            if (number !in 1..fallback.size) return@forEach
            val candidateGrid = grid.withSlot(PeriodSlot(number, range.first, range.second))
            if (candidateGrid.validationError() == null) {
                grid = candidateGrid
                parsed += number
            } else {
                warnings += "第 $number 节的识别时间与相邻节次不连续，已保留默认时间，请核对。"
            }
        }
        return GridResolution(grid, parsed, warnings)
    }

    // ---------------------------------------------------------------- grouping

    private fun groupColumn(
        column: DayColumn,
        lines: List<OcrLine>,
        geometry: RowGeometry?,
    ): List<List<OcrLine>> {
        if (lines.isEmpty()) return emptyList()
        val heights = lines.map(OcrLine::height).filter { it > 0.002f }.sorted()
        val medianHeight = heights.getOrNull(heights.size / 2) ?: 0.014f
        val rowHeight = geometry?.rowHeight ?: 0.0f
        val gapThreshold = maxOf(rowHeight * 0.45f, medianHeight * 1.4f, 0.012f)

        val preliminary = mutableListOf<MutableList<OcrLine>>()
        lines.forEach { line ->
            val current = preliminary.lastOrNull()
            if (current == null || line.top - current.last().bottom > gapThreshold) {
                preliminary += mutableListOf(line)
            } else {
                current += line
            }
        }
        return preliminary.flatMap { splitMergedBlock(it) }
    }

    /**
     * If two adjacent course cells were merged by OCR gap tolerance, the block
     * contains two week lines. Split at the largest internal gap; each half is
     * then parsed independently. A half without its own name becomes a
     * "needs review" row instead of silently disappearing.
     */
    private fun splitMergedBlock(lines: List<OcrLine>): List<List<OcrLine>> {
        if (lines.size < 2) return listOf(lines)
        val weekIndices = lines.indices.filter { parseWeekInfo(lines[it].text) != null }
        if (weekIndices.size <= 1) return listOf(lines)
        val secondWeekIndex = weekIndices[1]
        var splitIndex = -1
        // The second course's name is normally the last name-like line before
        // its own week line. Splitting at the largest raw gap is unreliable
        // because timetable lines often have nearly identical spacing.
        for (index in secondWeekIndex - 1 downTo 1) {
            if (looksLikeCourseName(lines[index].text) && parsePeriodInfo(lines[index].text) == null) {
                splitIndex = index
                break
            }
        }
        if (splitIndex <= 0) splitIndex = secondWeekIndex
        if (splitIndex <= 0) return listOf(lines)
        val left = lines.take(splitIndex)
        val right = lines.drop(splitIndex)
        if (left.isEmpty() || right.isEmpty()) return listOf(lines)
        return splitMergedBlock(left) + splitMergedBlock(right)
    }

    // ------------------------------------------------------------ course parse

    private fun parseGroup(
        column: DayColumn,
        group: List<OcrLine>,
        geometry: RowGeometry?,
        grid: PeriodGrid,
        options: ScheduleParseOptions,
    ): ParsedScheduleCourse? {
        if (group.isEmpty()) return null
        val notes = mutableListOf<String>()
        val review = mutableSetOf<ScheduleImportField>()
        val nameCandidates = mutableListOf<String>()
        val teacherCandidates = mutableListOf<String>()
        val roomCandidates = mutableListOf<String>()

        var weekRange: IntRange? = null
        var weekParity = WeekParity.ALL
        var weekIssue: String? = null
        var explicitPeriodStart: Int? = null
        var explicitPeriodEnd: Int? = null
        var periodIssue: String? = null

        group.forEach { line ->
            val week = parseWeekInfo(line.text)
            if (week != null) {
                if (week.startWeek != null && week.endWeek != null) {
                    val newRange = week.startWeek..week.endWeek
                    if (weekRange != null && weekRange != newRange) {
                        weekIssue = "识别到多段周次，当前仅保留第 ${weekRange.first}–${weekRange.last} 周"
                    } else {
                        weekRange = newRange
                    }
                }
                if (week.parity != WeekParity.ALL) {
                    if (weekParity != WeekParity.ALL && weekParity != week.parity) {
                        weekIssue = "单双周信息互相冲突"
                    } else {
                        weekParity = week.parity
                    }
                }
                if (week.ambiguous) {
                    weekIssue = weekIssue ?: "周次文字包含多段信息，请核对"
                }
                if (week.unsupported) {
                    weekIssue = "周次格式暂不支持自动转换，请手动填写"
                }
            }

            val periods = parsePeriodInfo(line.text)
            if (periods != null) {
                if (explicitPeriodStart != null && explicitPeriodStart != periods.first) {
                    periodIssue = "识别到多组节次，当前仅保留第 ${explicitPeriodStart}–${explicitPeriodEnd} 节"
                } else {
                    explicitPeriodStart = periods.first
                    explicitPeriodEnd = periods.second
                }
            }

            val teacher = extractTeacher(line.text)
            val room = extractRoom(line.text)
            if (teacher != null) teacherCandidates += teacher
            if (room != null) roomCandidates += room

            val metadata = week != null || periods != null || isTimeOnly(line.text)
            val residual = residualText(line.text, teacher, room)
            when {
                looksLikeCourseName(residual) -> nameCandidates += residual
                !metadata && teacher == null && room == null -> nameCandidates += line.text
            }
        }

        val name = chooseName(nameCandidates)
        val teacher = teacherCandidates
            .distinct()
            .maxByOrNull { candidate -> candidate.length }
        val classroom = roomCandidates
            .distinct()
            .sortedWith(
                compareByDescending<String> { roomCandidateScore(it) }
                    .thenByDescending { it.length },
            )
            .firstOrNull()
            .orEmpty()

        if (name.isBlank()) {
            review += ScheduleImportField.NAME
            notes += "未识别到课程名称，请手动填写"
        }
        if (classroom.isBlank()) {
            review += ScheduleImportField.CLASSROOM
            notes += "未识别到教室，请手动填写"
        }
        if (roomCandidates.distinct().size > 1) {
            notes += "识别到多个地点，已选择「$classroom」，请核对"
        }
        if (nameCandidates.distinct().size > 1) {
            notes += "识别到多行候选文字，已选择「$name」作为课程名"
        }
        if (weekIssue != null) {
            review += ScheduleImportField.WEEKS
            notes += weekIssue
        }
        if (periodIssue != null) {
            review += ScheduleImportField.PERIOD
            notes += periodIssue
        }

        val rows = group.mapNotNull { geometry?.rowFor(it.centerY) }
        var inferredStart = rows.minOrNull()
        var inferredEnd = rows.maxOrNull()
        if (explicitPeriodStart == null && geometry != null && inferredStart != null && inferredEnd != null) {
            if (inferredStart == inferredEnd) {
                // A merged two-period cell often centers its text block on the
                // row boundary. Treat that as an ambiguous span, not a silent
                // single-period inference.
                val center = (group.minOf(OcrLine::top) + group.maxOf(OcrLine::bottom)) / 2f
                val nearestBoundary = geometry.nearestBoundary(center)
                if (nearestBoundary != null && abs(center - nearestBoundary) <= geometry.rowHeight * 0.2f) {
                    val nextRow = geometry.rowAtOrAfter(nearestBoundary + 0.0005f)
                    if (nextRow != null && nextRow > inferredStart) {
                        inferredEnd = nextRow
                        review += ScheduleImportField.PERIOD
                        notes += "节次跨越网格行边界，已按跨节推断，请核对"
                    }
                }
            }
            val crossed = geometry.crossedBoundary(
                group.minOf(OcrLine::top),
                group.maxOf(OcrLine::bottom),
            )
            if (crossed != null) {
                val nextRow = geometry.rowAtOrAfter(crossed + 0.0005f)
                if (nextRow != null && nextRow > inferredEnd) {
                    inferredEnd = nextRow
                }
                review += ScheduleImportField.PERIOD
                notes += "节次由网格位置推断且跨行，请核对"
            }
        }
        if (explicitPeriodStart != null && inferredStart != null && inferredEnd != null &&
            (explicitPeriodStart != inferredStart || explicitPeriodEnd != inferredEnd)
        ) {
            notes += "课程内节次文字与网格位置不完全一致，已采用文字标注的节次"
        }

        val periodStart = explicitPeriodStart ?: inferredStart
        val periodEnd = explicitPeriodEnd ?: inferredEnd
        if (periodStart == null || periodEnd == null) {
            review += ScheduleImportField.PERIOD
            notes += "未识别到节次，请手动选择"
        } else if (periodStart < 1 || periodEnd < periodStart) {
            review += ScheduleImportField.PERIOD
            notes += "节次范围异常，请手动选择"
        } else if (grid.slot(periodStart) == null || grid.slot(periodEnd) == null) {
            review += ScheduleImportField.PERIOD
            notes += "节次超出当前节次时间表，请调整节次时间或手动选择"
        }

        if (weekRange == null) {
            review += ScheduleImportField.WEEKS
            notes += "未识别到教学周，请手动填写"
        } else if (weekRange.first < 1 || weekRange.last > options.maxWeek) {
            review += ScheduleImportField.WEEKS
            notes += "教学周超出 1–${options.maxWeek} 的范围"
        }

        if (column.dayOfWeek !in 1..7) {
            review += ScheduleImportField.DAY
            notes += "未识别到星期"
        }

        val hasUsefulContent = name.isNotBlank() ||
            classroom.isNotBlank() ||
            teacher != null ||
            weekRange != null ||
            explicitPeriodStart != null
        if (!hasUsefulContent) return null

        return ParsedScheduleCourse(
            name = name.ifBlank { "" },
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = column.dayOfWeek.takeIf { it in 1..7 },
            periodStart = periodStart,
            periodEnd = periodEnd,
            startWeek = weekRange?.first,
            endWeek = weekRange?.last,
            weekParity = weekParity,
            needsReview = review,
            notes = notes.distinct(),
            sourceText = group.joinToString(" / ", transform = OcrLine::text),
        )
    }

    private fun chooseName(candidates: List<String>): String {
        val cleaned = candidates
            .map(::cleanCandidate)
            .filter(::looksLikeCourseName)
            .distinct()
        if (cleaned.isEmpty()) return ""
        var best = cleaned.first()
        var bestScore = nameScore(best)
        cleaned.drop(1).forEach { candidate ->
            val score = nameScore(candidate)
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun nameScore(value: String): Int {
        val hanCount = value.count { it in '\u4e00'..'\u9fa5' }
        val latinCount = value.count { it in 'A'..'Z' || it in 'a'..'z' }
        var score = when {
            hanCount >= 2 -> 3
            latinCount >= 3 -> 2
            hanCount + latinCount >= 2 -> 1
            else -> 0
        }
        if (value.length in 2..20) score += 2
        if (value.contains("第") && value.contains("章")) score -= 2
        return score
    }

    private fun cleanCandidate(value: String): String = value
        .trim(' ', ':', '-', '/', '|', '·', '.', ',', '(', ')', '（', '）')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun looksLikeCourseName(value: String): Boolean {
        val trimmed = cleanCandidate(value)
        if (trimmed.length < 2) return false
        if (isIgnorable(trimmed)) return false
        if (parseWeekInfo(trimmed) != null) return false
        if (parsePeriodInfo(trimmed) != null) return false
        if (isTimeOnly(trimmed)) return false
        if (extractTeacher(trimmed) != null) return false
        val room = extractRoom(trimmed)
        if (room != null && trimmed.length <= 6) return false
        val hasHan = trimmed.any { it in '\u4e00'..'\u9fa5' }
        val hasLatin = trimmed.any { it in 'A'..'Z' || it in 'a'..'z' }
        return hasHan || hasLatin
    }

    private fun residualText(text: String, teacher: String?, room: String?): String {
        var residual = text
        if (teacher != null) residual = residual.replace(teacher, " ")
        if (room != null) residual = residual.replace(room, " ")
        listOf(
            weekRangeRegex,
            weekRangeWithoutSuffixRegex,
            weekListRegex,
            weekSingleRegex,
            periodRangeRegex,
            periodSingleRegex,
            barePeriodRangeRegex,
            timeRangeRegex,
            parityTokenRegex,
        ).forEach { regex ->
            residual = regex.replace(residual, " ")
        }
        residual = residual.replace(Regex("(周次|上课时间|授课教师|教师|地点|教室)[:：]?"), " ")
        return cleanCandidate(residual)
    }

    // ------------------------------------------------------------ field parse

    private fun parseWeekInfo(text: String): WeekInfo? {
        val compact = text.replace(" ", "")
        if (compact.isEmpty()) return null
        val hasWeekContext = compact.contains("周") ||
            parenthesizedParityRegex.containsMatchIn(compact) ||
            slashParityRegex.containsMatchIn(compact)
        val parity = if (hasWeekContext) parseParity(compact) else WeekParity.ALL
        val list = weekListRegex.containsMatchIn(compact)
        if (list) {
            return WeekInfo(null, null, parity, ambiguous = true, unsupported = true)
        }
        val ranges = weekRangeRegex.findAll(compact).toList()
        if (ranges.isNotEmpty()) {
            val range = ranges.first()
            val start = range.groupValues[1].toIntOrNull()
            val end = range.groupValues[2].toIntOrNull()
            if (start == null || end == null) return null
            val afterRanges = weekRangeRegex.replace(compact, "|")
            val extraSingles = weekSingleRegex.findAll(afterRanges).toList()
            return WeekInfo(
                startWeek = start,
                endWeek = end,
                parity = parity,
                ambiguous = ranges.size > 1 || extraSingles.isNotEmpty(),
                unsupported = false,
            )
        }
        val noSuffixRange = weekRangeWithoutSuffixRegex.find(compact)
        if (noSuffixRange != null) {
            val start = noSuffixRange.groupValues[1].toIntOrNull()
            val end = noSuffixRange.groupValues[2].toIntOrNull()
            if (start != null && end != null) {
                return WeekInfo(start, end, parity, ambiguous = false, unsupported = false)
            }
        }
        val singles = weekSingleRegex.findAll(compact).toList()
        if (singles.isNotEmpty()) {
            val weeks = singles.mapNotNull { it.groupValues[1].toIntOrNull() }.distinct()
            if (weeks.size == 1) {
                return WeekInfo(weeks[0], weeks[0], parity, ambiguous = false, unsupported = false)
            }
            return WeekInfo(null, null, parity, ambiguous = true, unsupported = true)
        }
        if (parity != WeekParity.ALL && hasWeekContext) {
            return WeekInfo(null, null, parity, ambiguous = false, unsupported = false)
        }
        return null
    }

    private fun parseParity(compact: String): WeekParity {
        val hasOdd = compact.contains("单")
        val hasEven = compact.contains("双")
        return when {
            hasOdd && hasEven -> WeekParity.ALL
            hasOdd -> WeekParity.ODD
            hasEven -> WeekParity.EVEN
            else -> WeekParity.ALL
        }
    }

    private fun parsePeriodInfo(text: String): Pair<Int, Int>? {
        val compact = text.replace(" ", "")
        val range = periodRangeRegex.find(compact)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull()
            val end = range.groupValues[2].toIntOrNull()
            if (start != null && end != null && start in 1..MAX_IMPORT_PERIOD && end in start..MAX_IMPORT_PERIOD) {
                return start to end
            }
        }
        val single = periodSingleRegex.find(compact)
        if (single != null) {
            val number = single.groupValues[1].toIntOrNull()
            if (number != null && number in 1..MAX_IMPORT_PERIOD) return number to number
        }
        val bare = barePeriodRangeRegex.matchEntire(compact) ?: return null
        val start = bare.groupValues[1].toIntOrNull() ?: return null
        val end = bare.groupValues[2].toIntOrNull() ?: return null
        if (start in 1..MAX_IMPORT_PERIOD && end in start..MAX_IMPORT_PERIOD) return start to end
        return null
    }

    private fun parseTimeRange(text: String): Pair<Int, Int>? {
        val match = timeRangeRegex.find(text) ?: return null
        val startHour = match.groupValues[1].toIntOrNull() ?: return null
        val startMinute = match.groupValues[2].toIntOrNull() ?: return null
        val endHour = match.groupValues[3].toIntOrNull() ?: return null
        val endMinute = match.groupValues[4].toIntOrNull() ?: return null
        if (startHour !in 0..23 || endHour !in 0..23) return null
        if (startMinute !in 0..59 || endMinute !in 0..59) return null
        val start = startHour * 60 + startMinute
        val end = endHour * 60 + endMinute
        if (end <= start || end - start < 5 || end - start > 300) return null
        return start to end
    }

    private fun extractTeacher(text: String): String? {
        val labelFirst = teacherLabelFirstRegex.find(text)
        if (labelFirst != null) {
            val name = labelFirst.groupValues[1].trim()
            if (name.isNotEmpty()) return name
        }
        val match = teacherRegex.find(text) ?: return null
        val prefix = match.groupValues[1].trim()
        if (prefix.isEmpty()) return null
        return prefix + match.groupValues[2]
    }

    private fun extractRoom(text: String): String? {
        val withLabelRemoved = roomLabelRegex.replace(text, " ").trim()
        if (withLabelRemoved.isEmpty()) return null
        val hasKeyword = roomKeywordRegex.containsMatchIn(withLabelRemoved)
        val chineseCode = chineseRoomCodeRegex.find(withLabelRemoved)?.value
        val code = roomCodeRegex.find(withLabelRemoved)?.value
        val short = shortRoomCodeRegex.find(withLabelRemoved)?.value
        val pure = pureRoomNumberRegex.matchEntire(withLabelRemoved)?.value
        return when {
            chineseCode != null -> {
                if (hasKeyword && withLabelRemoved.length <= 24) withLabelRemoved else chineseCode
            }
            code != null -> {
                if (hasKeyword && withLabelRemoved.length <= 24) withLabelRemoved else code
            }
            hasKeyword -> withLabelRemoved.takeIf { it.length <= 20 }
            short != null -> short
            pure != null -> pure
            else -> null
        }
    }

    private fun roomCandidateScore(value: String): Int {
        var score = 0
        if (value.any { it in 'A'..'Z' || it in 'a'..'z' }) score += 4
        if (roomKeywordRegex.containsMatchIn(value)) score += 3
        if (value.any(Char::isDigit)) score += 2
        return score
    }

    private fun isTimeOnly(text: String): Boolean {
        if (!timeRangeRegex.containsMatchIn(text)) return false
        val remaining = timeRangeRegex.replace(text, " ")
            .replace(Regex("(时间|上课时间|节次)[:：]?"), " ")
            .replace(Regex("[\\s:：\\-/~至到]+"), "")
        return remaining.isEmpty()
    }

    private fun isIgnorable(text: String): Boolean {
        val compact = text.trim()
        if (compact.isEmpty()) return true
        if (!compact.any { it.isLetterOrDigit() || it in '\u4e00'..'\u9fa5' }) return true
        return ignorableRegexes.any { it.matches(compact) }
    }

    private fun normalize(line: OcrLine): OcrLine? {
        val safe = line.normalizedOrNull() ?: return null
        val normalized = buildString {
            safe.text.forEach { char ->
                append(
                    when (char) {
                        in '０'..'９' -> '0' + (char - '０')
                        in 'Ａ'..'Ｚ' -> 'A' + (char - 'Ａ')
                        in 'ａ'..'ｚ' -> 'a' + (char - 'ａ')
                        '：' -> ':'
                        '－', '—', '–', '−' -> '-'
                        '～' -> '~'
                        '　', '\u00A0' -> ' '
                        '（' -> '('
                        '）' -> ')'
                        '，' -> ','
                        '．' -> '.'
                        '\t', '\u200B', '\uFEFF' -> ' '
                        else -> char
                    },
                )
            }
        }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return null
        return safe.copy(text = normalized.take(200))
    }

    // -------------------------------------------------------------- internals

    private data class DayCandidate(val dayOfWeek: Int, val line: OcrLine)

    private data class DayColumn(
        val dayOfWeek: Int,
        val centerX: Float,
        val leftBoundary: Float,
        val rightBoundary: Float,
    )

    private data class DayHeader(val columns: List<DayColumn>, val bottom: Float)

    private data class PeriodAnchor(val number: Int, val line: OcrLine)

    private data class GridResolution(
        val grid: PeriodGrid,
        val parsedPeriods: Set<Int>,
        val warnings: List<String>,
    )

    private data class WeekInfo(
        val startWeek: Int?,
        val endWeek: Int?,
        val parity: WeekParity,
        val ambiguous: Boolean,
        val unsupported: Boolean,
    )

    private class RowGeometry(
        val centers: List<Pair<Int, Float>>,
        val rowHeight: Float,
        private val topBoundary: Float,
        private val bottomBoundary: Float,
    ) {
        fun contains(y: Float): Boolean = y >= topBoundary && y <= bottomBoundary

        fun rowFor(y: Float): Int? {
            if (!contains(y)) return null
            return centers.minBy { (_, center) -> abs(center - y) }.first
        }

        fun nearestBoundary(y: Float): Float? = (1 until centers.size)
            .map { index -> (centers[index - 1].second + centers[index].second) / 2f }
            .minByOrNull { boundary -> abs(boundary - y) }

        fun crossedBoundary(top: Float, bottom: Float): Float? {
            for (index in 1 until centers.size) {
                val boundary = (centers[index - 1].second + centers[index].second) / 2f
                if (top < boundary && bottom > boundary) return boundary
            }
            return null
        }

        fun rowAtOrAfter(y: Float): Int? = centers
            .filter { (_, center) -> center >= y - 0.0005f }
            .minByOrNull { (_, center) -> center }
            ?.first
            ?: centers.lastOrNull()?.first
    }

    private companion object {
        val dayHeaderRegex = Regex("(星期|周|礼拜)\\s*([一二三四五六日天1-7])")
        val dayNumberMap = mapOf(
            '一' to 1,
            '二' to 2,
            '三' to 3,
            '四' to 4,
            '五' to 5,
            '六' to 6,
            '日' to 7,
            '天' to 7,
            '1' to 1,
            '2' to 2,
            '3' to 3,
            '4' to 4,
            '5' to 5,
            '6' to 6,
            '7' to 7,
        )
        val periodLabelRegex = Regex("^(?:第)?(\\d{1,2})(?:节)?$")
        val periodWithSuffixRegex = Regex("^(?:第)?(\\d{1,2})节.*$")
        val periodLeadingRegex = Regex("^(?:第)?(\\d{1,2})节?(?:\\s|$).*$")
        val weekRangeRegex = Regex("(?:第)?(\\d{1,2})[-~](\\d{1,2})周")
        val weekRangeWithoutSuffixRegex = Regex("周次[:：]?(\\d{1,2})[-~](\\d{1,2})")
        val weekListRegex = Regex("\\d{1,2}(?:[,、]\\d{1,2})+周")
        val weekSingleRegex = Regex("(?:第)?(\\d{1,2})周")
        val parenthesizedParityRegex = Regex("[(]([单双])[)]")
        val slashParityRegex = Regex("/([单双])")
        val parityTokenRegex = Regex("(单周|双周|单数周|双数周|[(]单[)]|[(]双[)]|/单|/双)")
        val periodRangeRegex = Regex("(?:第)?(\\d{1,2})[-~](\\d{1,2})节")
        val periodSingleRegex = Regex("(?:第)?(\\d{1,2})节")
        val barePeriodRangeRegex = Regex("^(\\d{1,2})[-~](\\d{1,2})$")
        val timeRangeRegex = Regex("(\\d{1,2}):(\\d{2})\\s*[-~至]\\s*(\\d{1,2}):(\\d{2})")
        val teacherLabelFirstRegex = Regex("(?:授课)?教师[:：]\\s*([\\u4e00-\\u9fa5]{2,4})")
        val teacherRegex = Regex("([\\u4e00-\\u9fa5]{1,4})(副教授|教授|讲师|老师|教师)")
        val roomLabelRegex = Regex("(?:上课地点|地点|教室)[:：]?\\s*")
        val roomKeywordRegex = Regex("教室|教学楼|楼|室|馆|场|中心|机房|实验室|报告厅|阶梯|校区|园区")
        val roomCodeRegex = Regex("(?<![A-Za-z0-9])([A-Za-z]{1,3}-?\\d{2,4}[A-Za-z]?)(?![A-Za-z0-9])")
        val chineseRoomCodeRegex = Regex("([\\u4e00-\\u9fa5]{1,4}\\d{1,3}-\\d{2,4})")
        val shortRoomCodeRegex = Regex("(\\d{1,3}-\\d{3,4})")
        val pureRoomNumberRegex = Regex("(\\d{3,4})")
        val ignorableRegexes = listOf(
            Regex("^(上午|下午|中午|晚上|早晨|课间|休息)$"),
            Regex("^(时间|星期|课程表|课表|时间表|备注|说明|教务系统|教务处|打印|导出|学期)$"),
            Regex("^(课程名称|教师姓名|上课地点)$"),
        )
    }
}
