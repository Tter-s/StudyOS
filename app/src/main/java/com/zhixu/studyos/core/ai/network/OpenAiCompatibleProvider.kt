package com.zhixu.studyos.core.ai.network

import com.zhixu.studyos.core.ai.AiCostCalculator
import com.zhixu.studyos.core.model.AiCompletionRequest
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiProvider
import com.zhixu.studyos.core.model.AiProviderException
import com.zhixu.studyos.core.model.AiProviderResult
import org.json.JSONObject

/**
 * OpenAI 兼容的 Chat Completions 提供方实现。
 *
 * - 只使用 [AiHttpTransport]，不引入任何 AI SDK，业务逻辑不感知厂商；
 * - API Key 只放在 Authorization 头，任何错误信息都不回显 Key 或完整请求头；
 * - 超时/网络/401/403/404/429/5xx/非 JSON 响应都有明确分类；
 * - 部分兼容服务不支持 `response_format`，首次 400 时去掉该字段重试一次。
 */
class OpenAiCompatibleProvider(
    private val transport: AiHttpTransport,
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    private val timeoutSeconds: Int,
) : AiProvider {

    override val providerName: String = "OpenAI 兼容接口"

    private val endpoint: String = normalizeEndpoint(baseUrl)

    override suspend fun complete(request: AiCompletionRequest): AiProviderResult {
        if (apiKey.isBlank()) {
            throw AiProviderException(AiFailureKind.UNAUTHORIZED, "尚未配置 API Key")
        }
        val startedAt = System.currentTimeMillis()
        var response = post(request, includeResponseFormat = true)
        if (response.statusCode == 400 && looksLikeResponseFormatRejected(response.body)) {
            // 兼容不支持 response_format 的服务；提示词本身已要求输出 JSON。
            response = post(request, includeResponseFormat = false)
        }
        val latency = System.currentTimeMillis() - startedAt
        if (response.statusCode !in 200..299) {
            throw mapHttpError(response)
        }
        return parseResponse(response.body, latency, request)
    }

    private suspend fun post(
        request: AiCompletionRequest,
        includeResponseFormat: Boolean,
    ): AiHttpResponse = transport.postJson(
        url = endpoint,
        headers = mapOf("Authorization" to "Bearer $apiKey"),
        body = buildRequestBody(request, includeResponseFormat),
        timeoutSeconds = timeoutSeconds,
    )

    /** 供测试与调试使用的请求体构造函数；不包含密钥。 */
    fun buildRequestBody(request: AiCompletionRequest, includeResponseFormat: Boolean): String {
        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("content", request.systemPrompt))
            .put(JSONObject().put("role", "user").put("content", request.userPrompt))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", request.maxOutputTokens)
        if (includeResponseFormat) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body.toString()
    }

    private fun parseResponse(
        body: String,
        latencyMillis: Long,
        request: AiCompletionRequest,
    ): AiProviderResult {
        val root = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI 响应不是合法 JSON", error)
        }
        val choices = root.optJSONArray("choices")
            ?: throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI 响应缺少 choices 字段")
        if (choices.length() == 0) {
            throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI 响应没有返回任何内容")
        }
        val first = choices.optJSONObject(0)
            ?: throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI 响应格式异常")
        val message = first.optJSONObject("message")
        val content = message?.optString("content").orEmpty().trim()
        if (content.isEmpty()) {
            throw AiProviderException(AiFailureKind.INVALID_RESPONSE, "AI 返回内容为空")
        }
        val usage = root.optJSONObject("usage")
        val promptTokens: Int
        val completionTokens: Int
        val estimated: Boolean
        if (usage != null && usage.has("prompt_tokens") && usage.has("completion_tokens")) {
            promptTokens = usage.optInt("prompt_tokens", 0).coerceAtLeast(0)
            completionTokens = usage.optInt("completion_tokens", 0).coerceAtLeast(0)
            estimated = false
        } else {
            // 服务端没有 usage 时，输入 token 也要估算；否则 prompt 费用永远是 0，
            // 每日费用上限会被严重低估。
            promptTokens = AiCostCalculator.estimateTokens(
                request.systemPrompt + "\n" + request.userPrompt,
            )
            completionTokens = AiCostCalculator.estimateTokens(content)
            estimated = true
        }
        return AiProviderResult(
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            model = root.optString("model").ifBlank { model },
            latencyMillis = latencyMillis,
            tokensEstimated = estimated,
        )
    }

    private fun mapHttpError(response: AiHttpResponse): AiProviderException {
        val serverMessage = sanitizeServerMessage(response.body)
        val suffix = if (serverMessage.isBlank()) "" else "：$serverMessage"
        return when (response.statusCode) {
            400, 422 -> AiProviderException(
                AiFailureKind.REQUEST_REJECTED,
                "AI 服务拒绝了请求（HTTP ${response.statusCode}），请检查模型名称与参数$suffix",
            )
            401, 403 -> AiProviderException(
                AiFailureKind.UNAUTHORIZED,
                "API Key 无效或无权限（HTTP ${response.statusCode}），请在设置中更新密钥",
            )
            404 -> AiProviderException(
                AiFailureKind.NOT_FOUND,
                "找不到服务地址或模型（HTTP 404），请检查服务地址与模型名称",
            )
            429 -> AiProviderException(
                AiFailureKind.RATE_LIMITED,
                "AI 服务限流或额度不足（HTTP 429），请稍后重试$suffix",
            )
            in 500..599 -> AiProviderException(
                AiFailureKind.SERVER_ERROR,
                "AI 服务暂时不可用（HTTP ${response.statusCode}），请稍后重试",
            )
            else -> AiProviderException(
                AiFailureKind.UNKNOWN,
                "AI 请求失败（HTTP ${response.statusCode}）$suffix",
            )
        }
    }

    /** 只截取服务端返回的 error.message，去掉换行并限制长度，绝不包含请求头或密钥。 */
    private fun sanitizeServerMessage(body: String): String {
        if (body.isBlank()) return ""
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return message.replace(Regex("\\s+"), " ").trim().take(200)
    }

    private fun looksLikeResponseFormatRejected(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("response_format") || lower.contains("response format")
    }

    companion object {
        /** 允许用户填写以 `/v1` 结尾或完整的 `/chat/completions` 路径。 */
        fun normalizeEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }
    }
}
