package com.zhixu.studyos.core.ai.network

import com.zhixu.studyos.core.model.AiCompletionRequest
import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiProviderException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderTest {

    private val apiKey = "sk-secret-test-key"
    private val request = AiCompletionRequest(systemPrompt = "system", userPrompt = "user")

    private class FakeTransport(vararg responses: AiHttpResponse) : AiHttpTransport {
        val queue = ArrayDeque(responses.toList())
        val calls = mutableListOf<Call>()

        data class Call(
            val url: String,
            val headers: Map<String, String>,
            val body: String,
            val timeoutSeconds: Int,
        )

        override suspend fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutSeconds: Int,
        ): AiHttpResponse {
            calls += Call(url, headers, body, timeoutSeconds)
            return queue.removeFirstOrNull()
                ?: throw AssertionError("没有为第 ${calls.size} 次请求准备响应")
        }
    }

    private fun successBody(
        content: String = """{"schemaVersion":1,"summary":"ok"}""",
        promptTokens: Int = 120,
        completionTokens: Int = 45,
    ): String = JSONObject()
        .put("model", "test-model-returned")
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", content),
                ),
            ),
        )
        .put(
            "usage",
            JSONObject().put("prompt_tokens", promptTokens).put("completion_tokens", completionTokens),
        )
        .toString()

    private fun provider(transport: AiHttpTransport, baseUrl: String = "https://api.example.com/v1") =
        OpenAiCompatibleProvider(
            transport = transport,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = "test-model",
            timeoutSeconds = 45,
        )

    @Test
    fun success_parsesContentUsageAndModel() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, successBody()))

        val result = provider(transport).complete(request)

        assertEquals("""{"schemaVersion":1,"summary":"ok"}""", result.content)
        assertEquals(120, result.promptTokens)
        assertEquals(45, result.completionTokens)
        assertEquals("test-model-returned", result.model)
        assertFalse(result.tokensEstimated)
    }

    @Test
    fun request_containsModelMessagesAndAuthHeader() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, successBody()))

        provider(transport).complete(request)

        val call = transport.calls.single()
        assertEquals("https://api.example.com/v1/chat/completions", call.url)
        assertEquals("Bearer $apiKey", call.headers["Authorization"])
        assertEquals(45, call.timeoutSeconds)
        val body = JSONObject(call.body)
        assertEquals("test-model", body.getString("model"))
        assertEquals(2, body.getJSONArray("messages").length())
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertFalse(call.body.contains(apiKey))
    }

    @Test
    fun baseUrlEndingWithChatCompletions_isNotDoubled() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, successBody()))

        provider(transport, baseUrl = "https://api.example.com/v1/chat/completions").complete(request)

        assertEquals("https://api.example.com/v1/chat/completions", transport.calls.single().url)
    }

    @Test
    fun unauthorized_mapsToUnauthorizedWithoutLeakingKey() = runTest {
        val transport = FakeTransport(
            AiHttpResponse(401, """{"error":{"message":"Incorrect API key provided: sk-secret-test-key"}}"""),
        )

        val error = runCatching { provider(transport).complete(request) }.exceptionOrNull()

        assertTrue(error is AiProviderException)
        assertEquals(AiFailureKind.UNAUTHORIZED, (error as AiProviderException).kind)
        assertFalse(error.message!!.contains(apiKey))
    }

    @Test
    fun notFound_andRateLimit_andServerError_areMapped() = runTest {
        assertEquals(
            AiFailureKind.NOT_FOUND,
            failureKind(AiHttpResponse(404, """{"error":{"message":"model not found"}}""")),
        )
        assertEquals(
            AiFailureKind.RATE_LIMITED,
            failureKind(AiHttpResponse(429, """{"error":{"message":"rate limit"}}""")),
        )
        assertEquals(
            AiFailureKind.SERVER_ERROR,
            failureKind(AiHttpResponse(503, "")),
        )
        assertEquals(
            AiFailureKind.REQUEST_REJECTED,
            failureKind(AiHttpResponse(400, """{"error":{"message":"bad parameter"}}""")),
        )
    }

    @Test
    fun responseFormatRejection_isRetriedWithoutTheField() = runTest {
        val transport = FakeTransport(
            AiHttpResponse(400, """{"error":{"message":"response_format is not supported"}}"""),
            AiHttpResponse(200, successBody()),
        )

        val result = provider(transport).complete(request)

        assertEquals(2, transport.calls.size)
        assertTrue(JSONObject(transport.calls[0].body).has("response_format"))
        assertFalse(JSONObject(transport.calls[1].body).has("response_format"))
        assertTrue(result.content.contains("summary"))
    }

    @Test
    fun malformedResponse_mapsToInvalidResponse() = runTest {
        val transport = FakeTransport(AiHttpResponse(200, "not-json"))

        val error = runCatching { provider(transport).complete(request) }.exceptionOrNull()

        assertEquals(AiFailureKind.INVALID_RESPONSE, (error as AiProviderException).kind)
    }

    @Test
    fun emptyChoices_mapsToInvalidResponse() = runTest {
        val body = JSONObject().put("choices", JSONArray()).toString()
        val transport = FakeTransport(AiHttpResponse(200, body))

        val error = runCatching { provider(transport).complete(request) }.exceptionOrNull()

        assertEquals(AiFailureKind.INVALID_RESPONSE, (error as AiProviderException).kind)
    }

    @Test
    fun missingUsage_marksTokensEstimated() = runTest {
        val content = """{"schemaVersion":1,"summary":"ok"}"""
        val body = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
            .toString()
        val transport = FakeTransport(AiHttpResponse(200, body))

        val result = provider(transport).complete(request)

        assertTrue(result.tokensEstimated)
        assertTrue(result.promptTokens > 0)
        assertTrue(result.completionTokens > 0)
    }

    @Test
    fun blankApiKey_isRejectedBeforeNetwork() = runTest {
        val transport = FakeTransport()
        val provider = OpenAiCompatibleProvider(
            transport = transport,
            apiKey = "  ",
            baseUrl = "https://api.example.com/v1",
            model = "m",
            timeoutSeconds = 30,
        )

        val error = runCatching { provider.complete(request) }.exceptionOrNull()

        assertEquals(AiFailureKind.UNAUTHORIZED, (error as AiProviderException).kind)
        assertTrue(transport.calls.isEmpty())
    }

    private suspend fun failureKind(response: AiHttpResponse): AiFailureKind {
        val transport = FakeTransport(response)
        val error = runCatching { provider(transport).complete(request) }.exceptionOrNull()
        return (error as AiProviderException).kind
    }
}
