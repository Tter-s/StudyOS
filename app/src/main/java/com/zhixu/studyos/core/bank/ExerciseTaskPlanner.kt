package com.zhixu.studyos.core.bank

import com.zhixu.studyos.core.model.ProblemBankEntry
import com.zhixu.studyos.core.model.ProblemTaskPlan
import com.zhixu.studyos.core.model.ProblemTaskPlanItem
import com.zhixu.studyos.core.model.ProblemTaskPlanRequest
import java.time.LocalDate

/**
 * 把选中的题目拆成每日习题任务（V0.8 第六节）。
 * 只生成任务（无学习时段，状态为待安排），随后由用户在「排程」里交给既有本地排程引擎。
 */
object ExerciseTaskPlanner {

    const val DEFAULT_MINUTES_PER_PROBLEM = 10
    const val MAX_PROBLEMS_PER_DAY = 50

    fun plan(request: ProblemTaskPlanRequest, problems: List<ProblemBankEntry>): ProblemTaskPlan {
        require(request.problemsPerDay in 1..MAX_PROBLEMS_PER_DAY) {
            "每日题量应为 1–$MAX_PROBLEMS_PER_DAY"
        }
        require(problems.isNotEmpty()) { "请先选择至少一道题目" }
        val ordered = problems.sortedWith(
            compareBy({ it.chapter }, { naturalNumberKey(it.problemNumber) }, { it.problemNumber }),
        )
        val items = ordered.chunked(request.problemsPerDay).mapIndexed { index, group ->
            val date = request.startDate.plusDays(index.toLong())
            val numbers = group.map { it.problemNumber }
            val minutes = (group.sumOf { it.estimatedMinutes ?: DEFAULT_MINUTES_PER_PROBLEM })
                .coerceAtLeast(5)
            ProblemTaskPlanItem(
                date = date,
                problemNumbers = numbers,
                title = buildTitle(request, group),
                subject = request.courseName.ifBlank { request.textbook },
                estimatedMinutes = minutes,
            )
        }
        return ProblemTaskPlan(items = items, selectedCount = ordered.size)
    }

    private fun buildTitle(request: ProblemTaskPlanRequest, group: List<ProblemBankEntry>): String {
        val source = listOf(request.textbook, request.chapterOf(group))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val range = numberRangeLabel(group.map { it.problemNumber })
        return buildString {
            append("习题")
            if (source.isNotBlank()) append("：").append(source)
            if (range.isNotBlank()) append(" ").append(range)
        }
    }

    private fun ProblemTaskPlanRequest.chapterOf(group: List<ProblemBankEntry>): String =
        group.map { it.chapter }.distinct().singleOrNull().orEmpty()

    /** 「1、2、3、5」最多显示三个，其余省略。 */
    private fun numberRangeLabel(numbers: List<String>): String {
        if (numbers.isEmpty()) return ""
        val distinct = numbers.distinct()
        return if (distinct.size <= 3) {
            distinct.joinToString("、")
        } else {
            "${distinct.first()}–${distinct.last()}（共 ${distinct.size} 题）"
        }
    }

    /** 题号自然排序键：前导数字优先，便于「2-1、2-10」等场景。 */
    fun naturalNumberKey(problemNumber: String): Long {
        val match = Regex("(\\d+)").find(problemNumber.trim()) ?: return Long.MAX_VALUE
        return match.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE
    }
}
