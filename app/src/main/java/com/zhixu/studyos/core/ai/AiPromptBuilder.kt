package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AiProviderConfig
import com.zhixu.studyos.core.model.AiStudySnapshot

/**
 * 构造发送给 AI 的系统与用户提示词。
 *
 * 设计原则（V0.9 第三节/第五节）：
 * - 明确要求只输出符合 Schema 的 JSON，便于本地严格校验；
 * - 明确「只能使用给定数据」，禁止编造 taskId/科目；
 * - 明确数值范围，与本地约束检查保持一致；
 * - 不包含任何财务数据；数据范围完全由 [AiSnapshotBuilder.toJson] 的隐私开关决定。
 */
object AiPromptBuilder {

    const val PROMPT_VERSION = AiRequestHasher.PROMPT_VERSION

    private val SYSTEM_PROMPT = """
        你是「知序 StudyOS」的本地学习规划助手。用户会提供一份关于过去七天学习情况和未来一周任务规划的 JSON 数据。
        请只输出一个 JSON 对象，不要输出 Markdown、代码围栏或任何解释性文字。

        输出 JSON 必须严格符合以下结构（字段名区分大小写）：
        {
          "schemaVersion": 1,
          "summary": "对过去一周学习情况的中文总结，200 字以内",
          "subjectAllocations": [
            {"subject": "必须来自输入 subjects[].name", "minutesPerDay": 整数, "reason": "中文理由"}
          ],
          "taskEstimates": [
            {"taskId": 必须来自输入 upcomingTasks[].id, "estimatedMinutes": 整数, "reason": "中文理由"}
          ],
          "restReminders": ["中文休息提醒"],
          "weeklySuggestions": ["中文的下一周学习建议"],
          "riskFlags": [{"kind": "如 POSTPONED_TASKS / OVERLOAD / DIFFICULT_PROBLEMS", "detail": "中文说明"}]
        }

        规则：
        1. 只能使用输入数据中出现过的科目名称和 taskId，不得编造。
        2. estimatedMinutes 取值 5–300；minutesPerDay 取值 0–scheduling.dailyLimitMinutes。
        3. 所有 subjectAllocations 的 minutesPerDay 总和不得超过 scheduling.dailyLimitMinutes。
        4. 最多 10 条科目分配、15 条任务用时估计、5 条休息提醒、5 条周建议、5 条风险提示。
        5. 如果输入数据不足以判断，请在 summary 中说明，不要编造事实。
        6. 不要输出题目解答，不做医疗或心理诊断，只给学习与休息安排建议。
        7. 全部文本使用中文。
    """.trimIndent()

    data class Prompts(val systemPrompt: String, val userPrompt: String)

    fun build(snapshotJson: String, snapshot: AiStudySnapshot): Prompts {
        val user = buildString {
            appendLine("以下是用户授权的学习数据（JSON，仅包含学习统计与任务信息，不含财务数据）：")
            appendLine(snapshotJson)
            appendLine()
            appendLine(
                "统计窗口：${snapshot.windowStart} 至 ${snapshot.windowEnd}；" +
                    "未来任务 ${snapshot.upcomingTasks.size} 个；" +
                    "每日学习上限 ${snapshot.scheduling.dailyLimitMinutes} 分钟。",
            )
            appendLine("请按要求输出符合 Schema 的 JSON。")
        }
        return Prompts(systemPrompt = SYSTEM_PROMPT, userPrompt = user)
    }

    fun privacySummary(config: AiProviderConfig): String = buildString {
        append("将发送：近七天学习分钟数、任务完成与安排数量、未来任务的基本信息")
        if (config.includeTaskTitles) append("（含任务标题）") else append("（不含任务标题）")
        if (config.includeSubjectNames) append("、科目名称") else append("（科目名称使用掩码）")
        append(
            if (config.includeProblemStatements) {
                "、困难题目的题号与知识点摘要"
            } else {
                "、困难题目按科目的数量摘要"
            },
        )
        append("、每日学习目标、排程的每日上限与学习窗口、逐日学习日期与分钟数、时区")
        append("。不会发送财务流水、睡眠原始时刻、笔记正文或任何未列出的数据。")
    }
}
