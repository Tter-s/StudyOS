package com.zhixu.studyos.core.ai.network

import com.zhixu.studyos.core.model.AiFailureKind
import com.zhixu.studyos.core.model.AiProviderException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AiHttpResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * 最小 HTTP JSON POST 抽象。默认实现基于 HttpURLConnection，
 * 不引入任何 HTTP 或 AI SDK；测试通过假实现验证请求构造与错误映射。
 */
interface AiHttpTransport {
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSeconds: Int,
    ): AiHttpResponse
}

class UrlConnectionAiHttpTransport : AiHttpTransport {

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSeconds: Int,
    ): AiHttpResponse = withContext(Dispatchers.IO) {
        val connection = try {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (error: Exception) {
            throw AiProviderException(AiFailureKind.NOT_CONFIGURED, "服务地址无效，请检查设置", error)
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutSeconds * 1000
            connection.readTimeout = timeoutSeconds * 1000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            AiHttpResponse(statusCode = status, body = text)
        } catch (error: SocketTimeoutException) {
            throw AiProviderException(AiFailureKind.TIMEOUT, "AI 请求超时（${timeoutSeconds} 秒），请稍后重试", error)
        } catch (error: UnknownHostException) {
            throw AiProviderException(AiFailureKind.NETWORK, "无法解析服务地址，请检查网络与服务地址", error)
        } catch (error: ConnectException) {
            throw AiProviderException(AiFailureKind.NETWORK, "无法连接 AI 服务，请检查网络与服务地址", error)
        } catch (error: AiProviderException) {
            throw error
        } catch (error: IOException) {
            throw AiProviderException(AiFailureKind.NETWORK, "AI 请求失败：网络错误", error)
        } finally {
            connection.disconnect()
        }
    }
}
