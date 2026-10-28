package com.zhixu.studyos.core.math

import com.zhixu.studyos.core.ai.AiPlanParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * 五级提示。Level 0 不调用 AI，仅用于独立思考计时。
 * 跳级必须由用户明确确认，Level 5 完整参考证明也必须明确确认。
 */
enum class HintLevel(val label: String, val description: String) {
    LEVEL_0("独立思考", "不显示任何提示，只记录自己的思路"),
    LEVEL_1("方向提示", "只指出可能涉及的知识领域，不透露核心步骤"),
    LEVEL_2("关键观察", "提示值得注意的条件或数学结构"),
    LEVEL_3("方法提示", "给出可尝试的证明策略，不写完整证明"),
    LEVEL_4("详细引导", "把证明拆成若干步骤，引导逐步完成"),
    LEVEL_5("完整参考证明", "显示完整参考证明；必须由用户明确确认"),
    ;

    val requiresExplicitConfirmation: Boolean get() = this == LEVEL_5
    val isAiLevel: Boolean get() = this != LEVEL_0

    companion object {
        fun fromNumber(value: Int): HintLevel = entries.firstOrNull { it.ordinal == value } ?: LEVEL_1
    }
}

data class MathProblemContext(
    val problemType: String,
    val problemId: Long,
    val subject: String,
    val chapter: String,
    val title: String,
    val statement: String,
    val formulas: List<String> = emptyList(),
    val knowledgePoints: List<String> = emptyList(),
    val userThoughts: String = "",
)

data class MathHint(
    val level: HintLevel,
    val hint: String,
    val keyObservation: String? = null,
    val strategy: String? = null,
    val steps: List<String> = emptyList(),
    val referenceProof: String? = null,
    val caveats: List<String> = emptyList(),
    val uncertain: Boolean = false,
)

data class MathHintOutcome(
    val hint: MathHint,
    val model: String,
    val usedCache: Boolean,
    val costMicros: Long,
    val requestedAt: java.time.Instant,
    val recordId: Long? = null,
)

enum class ProofVerdict(val label: String) {
    OK("暂未发现明显问题"),
    LOGIC_GAP("存在明显逻辑缺口"),
    NEEDS_CHECK("需要进一步检查"),
    UNCERTAIN("AI 无法可靠判断"),
}

data class ProofCheck(
    val verdict: ProofVerdict,
    val certainIssues: List<String> = emptyList(),
    val needsCheck: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val uncertainNote: String? = null,
) {
    val disclaimer: String
        get() = "AI 检查不是数学证明的严格验证，结论仅供你参考；最终正确性需要课程或教材确认。"
}

class MathHintSchemaException(message: String, cause: Throwable? = null) : Exception(message, cause)

object MathHintPromptBuilder {
    const val PROMPT_VERSION = "math-hint-v1"
    const val PROOF_PROMPT_VERSION = "proof-check-v1"

    data class Prompts(val system: String, val user: String, val cachePayload: String)

    fun hintPrompts(context: MathProblemContext, level: HintLevel): Prompts {
        require(level != HintLevel.LEVEL_0) { "Level 0 不调用 AI" }
        val levelRules = when (level) {
            HintLevel.LEVEL_1 ->
                "只指出可能涉及的知识领域（例如“考虑与整除性有关的基本性质”）。禁止写出关键步骤或公式推导。"
            HintLevel.LEVEL_2 ->
                "指出题目中值得注意的条件、结构或恒等变形方向；不要给出完整证明步骤。"
            HintLevel.LEVEL_3 ->
                "给出可尝试的证明策略（如反证、数学归纳、构造辅助函数），说明为什么可能有效；不要直接写完整证明。"
            HintLevel.LEVEL_4 ->
                "把证明拆成 3–6 个由浅入深的小步骤，每一步只给目标和提示，不把中间推导全部写出。"
            HintLevel.LEVEL_5 ->
                "给出完整参考证明，并说明每一步的依据。把证明放在 referenceProof 字段。不得声称这是唯一正确做法。"
            HintLevel.LEVEL_0 -> ""
        }
        val system = """
你是数学学习助手，目标是帮助大学生独立完成证明题，而不是代替他们做题。
当前提示等级：${level.label}（Level ${level.ordinal}）。
本等级规则：$levelRules

硬性要求：
- 不自动跳到更高等级，不泄露超过本等级的信息；
- 如果题目条件不足或表述不清，在 caveats 中说明，不要编造条件；
- 数学公式使用 LaTeX（行内用 ${'$'}...${'$'}，独立公式用 ${'$'}${'$'}...${'$'}${'$'}）；
- 不确定时把 uncertain 设为 true，并在 caveats 说明；
- 只输出一个 JSON 对象，不要 Markdown 代码围栏，不要额外解释。

JSON Schema:
{
  "level": ${level.ordinal},
  "hint": "本等级的核心提示文本",
  "keyObservation": "可选，值得注意的条件或结构",
  "strategy": "可选，可尝试的证明策略",
  "steps": ["Level 4/5 时可选，逐步骤引导"],
  "referenceProof": "仅 Level 5 填写完整参考证明，其他等级为 null",
  "caveats": ["可选，题目歧义、AI 不确定或需要人工确认的地方"],
  "uncertain": false
}
        """.trimIndent()
        val user = buildString {
            appendLine("科目：${context.subject.ifBlank { "未标注" }}")
            appendLine("章节：${context.chapter.ifBlank { "未标注" }}")
            appendLine("题目标题：${context.title}")
            appendLine("题目：")
            appendLine(context.statement)
            if (context.formulas.isNotEmpty()) {
                appendLine("公式：")
                context.formulas.forEach { appendLine(it) }
            }
            if (context.knowledgePoints.isNotEmpty()) {
                appendLine("知识点：${context.knowledgePoints.joinToString("、")}")
            }
            if (context.userThoughts.isNotBlank()) {
                appendLine("我目前的思路（可能不完整或错误）：")
                appendLine(context.userThoughts.take(3000))
            }
            appendLine("请按 Level ${level.ordinal} 规则给出提示。")
        }
        val cachePayload = JSONObject()
            .put("problemType", context.problemType)
            .put("problemId", context.problemId)
            .put("statement", context.statement)
            .put("formulas", JSONArray(context.formulas))
            .put("knowledgePoints", JSONArray(context.knowledgePoints))
            .put("userThoughts", context.userThoughts)
            .put("level", level.ordinal)
            .toString()
        return Prompts(system = system, user = user, cachePayload = cachePayload)
    }

    fun proofCheckPrompts(context: MathProblemContext, proof: String, otherMethods: Boolean): Prompts {
        require(proof.isNotBlank()) { "请先填写你的证明" }
        val system = """
你是数学证明检查助手。用户提交了自己写的证明，请帮助他发现问题，而不是重写一份答案。
要求：
- 优先指出确定性的逻辑错误（错误推理、遗漏条件、循环论证）；
- 无法确定的地方放入 needsCheck，并说明需要检查什么；
- 不要把 AI 判断当作严格正确的数学验证；
- 用简短、具体、可操作的语言，不要笼统评价；
- 如用户要求其他证明方法，在 suggestions 中给出 1–2 个不同路线的概述，不写完整答案；
- 只输出 JSON 对象，不要 Markdown 围栏。

JSON Schema:
{
  "verdict": "OK | LOGIC_GAP | NEEDS_CHECK | UNCERTAIN",
  "certainIssues": ["确定的逻辑问题"],
  "needsCheck": ["需要进一步检查的地方"],
  "suggestions": ["改进建议或其他证明路线"],
  "uncertainNote": "可选，说明 AI 的不确定性"
}
        """.trimIndent()
        val user = buildString {
            appendLine("题目：${context.statement}")
            if (context.formulas.isNotEmpty()) {
                appendLine("公式：${context.formulas.joinToString("；")}")
            }
            appendLine("用户证明：")
            appendLine(proof.take(6000))
            appendLine(if (otherMethods) "请同时提供其他证明路线的概述。" else "请检查以上证明。")
        }
        val cachePayload = JSONObject()
            .put("problemId", context.problemId)
            .put("proof", proof)
            .put("otherMethods", otherMethods)
            .toString()
        return Prompts(system = system, user = user, cachePayload = cachePayload)
    }

    fun parseHint(content: String, expectedLevel: HintLevel): MathHint {
        val json = extractJson(content)
        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw MathHintSchemaException("AI 提示不是合法 JSON，请重新请求", error)
        }
        val levelValue = root.optInt("level", expectedLevel.ordinal)
        val level = HintLevel.fromNumber(levelValue)
        if (level != expectedLevel) {
            throw MathHintSchemaException(
                "AI 返回的提示等级（Level ${level.ordinal}）与请求等级（Level ${expectedLevel.ordinal}）不一致，已拒绝采用",
            )
        }
        val hint = root.optString("hint").trim()
        if (hint.isEmpty() && level != HintLevel.LEVEL_5) {
            throw MathHintSchemaException("AI 提示缺少 hint 内容")
        }
        val steps = root.optJSONArray("steps")?.let { array ->
            (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty)
        }.orEmpty()
        val caveats = root.optJSONArray("caveats")?.let { array ->
            (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty)
        }.orEmpty()
        val referenceProof = root.optString("referenceProof").takeIf {
            it.isNotBlank() && it != "null" && level == HintLevel.LEVEL_5
        }
        if (level == HintLevel.LEVEL_5 && referenceProof == null && hint.isEmpty()) {
            throw MathHintSchemaException("AI 未返回完整参考证明")
        }
        return MathHint(
            level = level,
            hint = hint,
            keyObservation = root.optString("keyObservation").takeIf { it.isNotBlank() && it != "null" },
            strategy = root.optString("strategy").takeIf { it.isNotBlank() && it != "null" },
            steps = steps,
            referenceProof = referenceProof,
            caveats = caveats,
            uncertain = root.optBoolean("uncertain", false),
        )
    }

    fun parseProofCheck(content: String): ProofCheck {
        val root = try {
            JSONObject(extractJson(content))
        } catch (error: Exception) {
            throw MathHintSchemaException("AI 证明检查不是合法 JSON，请重试", error)
        }
        val verdict = when (root.optString("verdict").uppercase()) {
            "OK" -> ProofVerdict.OK
            "LOGIC_GAP" -> ProofVerdict.LOGIC_GAP
            "NEEDS_CHECK" -> ProofVerdict.NEEDS_CHECK
            else -> ProofVerdict.UNCERTAIN
        }
        fun list(key: String): List<String> = root.optJSONArray(key)?.let { array ->
            (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty)
        }.orEmpty()
        return ProofCheck(
            verdict = verdict,
            certainIssues = list("certainIssues"),
            needsCheck = list("needsCheck"),
            suggestions = list("suggestions"),
            uncertainNote = root.optString("uncertainNote").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun extractJson(content: String): String = AiPlanParser.extractJson(content)
}

/** 判断当前提示等级下允许请求的下一等级；跳级必须显式确认。 */
object HintProgression {
    fun nextLevel(highestUsed: HintLevel?): HintLevel = when (highestUsed) {
        null, HintLevel.LEVEL_0 -> HintLevel.LEVEL_1
        HintLevel.LEVEL_1 -> HintLevel.LEVEL_2
        HintLevel.LEVEL_2 -> HintLevel.LEVEL_3
        HintLevel.LEVEL_3 -> HintLevel.LEVEL_4
        HintLevel.LEVEL_4, HintLevel.LEVEL_5 -> HintLevel.LEVEL_5
    }

    fun isJump(highestUsed: HintLevel?, requested: HintLevel): Boolean {
        val next = nextLevel(highestUsed)
        return requested != next && requested != HintLevel.LEVEL_0
    }

    fun firstIndependent(law: MathHint): String = when {
        law.steps.isEmpty() -> "先自己写出已知条件和目标，再尝试一个方向。"
        law.steps.size >= 3 -> "先只看第一步，尝试自己完成后，再决定是否继续。"
        else -> "尝试把提示转成自己的第一行推导，而不是照抄。"
    }
}
