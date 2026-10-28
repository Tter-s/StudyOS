package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AI_PLAN_SCHEMA_VERSION
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiPlan
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiRiskFlag
import com.zhixu.studyos.core.model.AiSubjectAllocation
import com.zhixu.studyos.core.model.AiTaskEstimate
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 输出的 JSON Schema 校验（V0.9 规划流程的第一步）。
 *
 * 严格校验字段类型、必填项与取值范围，容忍代码围栏与前后说明文字。
 * 任一结构错误都抛出 [AiFailureKind.SCHEMA_INVALID]，不把半合法数据交给后续流程。
 * 未知字段被忽略，便于服务商附加额外信息时仍可解析。
 */
object AiPlanParser {

    const val MAX_ALLOCATIONS = 10
    const val MAX_TASK_ESTIMATES = 15
    const val MAX_REMINDERS = 5
    const val MAX_SUGGESTIONS = 5
    const val MAX_RISK_FLAGS = 5
    const val MAX_SUMMARY_LENGTH = 2000
    const val MIN_ESTIMATE_MINUTES = 5
    const val MAX_ESTIMATE_MINUTES = 300

    fun parse(rawContent: String): AiPlan {
        val json = extractJson(rawContent)
        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw schemaError("AI 返回的内容不是合法 JSON")
        }
        val errors = mutableListOf<String>()

        val schemaVersion = readInt(root, "schemaVersion", errors, required = false) ?: AI_PLAN_SCHEMA_VERSION
        if (schemaVersion != AI_PLAN_SCHEMA_VERSION) {
            errors += "不支持的 Schema 版本：$schemaVersion"
        }
        val summary = readString(root, "summary", MAX_SUMMARY_LENGTH, errors, required = true).orEmpty()

        val allocations = mutableListOf<AiSubjectAllocation>()
        readArray(root, "subjectAllocations", errors, MAX_ALLOCATIONS).forEachIndexed { index, item ->
            if (item !is JSONObject) {
                errors += "subjectAllocations[$index] 必须是对象"
                return@forEachIndexed
            }
            val subject = readString(item, "subject", 100, errors, required = true, path = "subjectAllocations[$index]")
            val minutes = readInt(
                item, "minutesPerDay", errors,
                min = 0, max = 24 * 60, required = true,
                path = "subjectAllocations[$index]",
            )
            val reason = readString(item, "reason", 500, errors, required = false, path = "subjectAllocations[$index]").orEmpty()
            if (subject != null && minutes != null) {
                allocations += AiSubjectAllocation(subject = subject, minutesPerDay = minutes, reason = reason)
            }
        }

        val estimates = mutableListOf<AiTaskEstimate>()
        readArray(root, "taskEstimates", errors, MAX_TASK_ESTIMATES).forEachIndexed { index, item ->
            if (item !is JSONObject) {
                errors += "taskEstimates[$index] 必须是对象"
                return@forEachIndexed
            }
            val taskId = readLong(item, "taskId", errors, required = true, path = "taskEstimates[$index]")
            val minutes = readInt(
                item, "estimatedMinutes", errors,
                min = MIN_ESTIMATE_MINUTES, max = MAX_ESTIMATE_MINUTES, required = true,
                path = "taskEstimates[$index]",
            )
            val reason = readString(item, "reason", 500, errors, required = false, path = "taskEstimates[$index]").orEmpty()
            if (taskId != null && minutes != null) {
                estimates += AiTaskEstimate(taskId = taskId, estimatedMinutes = minutes, reason = reason)
            }
        }

        val reminders = readStringArray(root, "restReminders", errors, MAX_REMINDERS, 300)
        val suggestions = readStringArray(root, "weeklySuggestions", errors, MAX_SUGGESTIONS, 500)
        val riskFlags = mutableListOf<AiRiskFlag>()
        readArray(root, "riskFlags", errors, MAX_RISK_FLAGS).forEachIndexed { index, item ->
            if (item !is JSONObject) {
                errors += "riskFlags[$index] 必须是对象"
                return@forEachIndexed
            }
            val kind = readString(item, "kind", 60, errors, required = true, path = "riskFlags[$index]")
            val detail = readString(item, "detail", 500, errors, required = true, path = "riskFlags[$index]")
            if (kind != null && detail != null) riskFlags += AiRiskFlag(kind = kind, detail = detail)
        }

        if (errors.isNotEmpty()) {
            throw schemaError(errors.take(5).joinToString("；"))
        }
        return AiPlan(
            schemaVersion = schemaVersion,
            summary = summary,
            subjectAllocations = allocations,
            taskEstimates = estimates,
            restReminders = reminders,
            weeklySuggestions = suggestions,
            riskFlags = riskFlags,
        )
    }

    /** 从可能带代码围栏或说明文字的响应中提取 JSON 对象文本。 */
    fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) throw schemaError("AI 返回内容中没有 JSON 对象")
        return trimmed.substring(start, end + 1)
    }

    private fun readArray(
        obj: JSONObject,
        key: String,
        errors: MutableList<String>,
        maxItems: Int,
    ): List<Any> {
        if (!obj.has(key) || obj.isNull(key)) return emptyList()
        val value = obj.opt(key)
        if (value !is JSONArray) {
            errors += "$key 必须是数组"
            return emptyList()
        }
        if (value.length() > maxItems) {
            errors += "$key 最多 $maxItems 项，实际 ${value.length()} 项"
            return emptyList()
        }
        return (0 until value.length()).map { value.opt(it) }
    }

    private fun readString(
        obj: JSONObject,
        key: String,
        maxLength: Int,
        errors: MutableList<String>,
        required: Boolean,
        path: String = key,
    ): String? {
        if (!obj.has(key) || obj.isNull(key)) {
            if (required) errors += "$path.$key 缺失"
            return null
        }
        val value = obj.opt(key)
        if (value !is String) {
            errors += "$path.$key 必须是字符串"
            return null
        }
        val trimmed = value.trim()
        if (required && trimmed.isEmpty()) {
            errors += "$path.$key 不能为空"
            return null
        }
        if (trimmed.length > maxLength) {
            errors += "$path.$key 超出长度上限 $maxLength"
            return null
        }
        return trimmed
    }

    private fun readStringArray(
        obj: JSONObject,
        key: String,
        errors: MutableList<String>,
        maxItems: Int,
        maxLength: Int,
    ): List<String> {
        val items = readArray(obj, key, errors, maxItems)
        val result = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            if (item !is String) {
                errors += "$key[$index] 必须是字符串"
                return@forEachIndexed
            }
            val trimmed = item.trim()
            if (trimmed.length > maxLength) {
                errors += "$key[$index] 超出长度上限 $maxLength"
                return@forEachIndexed
            }
            if (trimmed.isNotEmpty()) result += trimmed
        }
        return result
    }

    private fun readInt(
        obj: JSONObject,
        key: String,
        errors: MutableList<String>,
        min: Int = Int.MIN_VALUE,
        max: Int = Int.MAX_VALUE,
        required: Boolean,
        path: String = key,
    ): Int? {
        val number = readNumber(obj, key, errors, required, path) ?: return null
        val value = number.toDouble()
        if (value != value.toLong().toDouble()) {
            errors += "$path.$key 必须是整数"
            return null
        }
        val intValue = value.toLong()
        if (intValue < min || intValue > max) {
            errors += "$path.$key 应在 $min–$max 之间"
            return null
        }
        return intValue.toInt()
    }

    private fun readLong(
        obj: JSONObject,
        key: String,
        errors: MutableList<String>,
        required: Boolean,
        path: String = key,
    ): Long? {
        val number = readNumber(obj, key, errors, required, path) ?: return null
        val value = number.toDouble()
        if (value != value.toLong().toDouble()) {
            errors += "$path.$key 必须是整数"
            return null
        }
        val longValue = value.toLong()
        if (longValue <= 0L) {
            errors += "$path.$key 必须是正整数"
            return null
        }
        return longValue
    }

    private fun readNumber(
        obj: JSONObject,
        key: String,
        errors: MutableList<String>,
        required: Boolean,
        path: String,
    ): Number? {
        if (!obj.has(key) || obj.isNull(key)) {
            if (required) errors += "$path.$key 缺失"
            return null
        }
        val value = obj.opt(key)
        if (value !is Number) {
            errors += "$path.$key 必须是数字"
            return null
        }
        return value
    }

    private fun schemaError(message: String) =
        AiProviderException(AiFailureKind.SCHEMA_INVALID, "AI 返回格式不符合要求：$message")
}
