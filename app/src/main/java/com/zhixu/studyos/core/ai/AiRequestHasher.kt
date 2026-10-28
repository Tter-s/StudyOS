package com.zhixu.studyos.core.ai

import java.security.MessageDigest

/** 稳定的 SHA-256 工具：用于缓存键与「输入是否变化」判断。 */
object AiRequestHasher {

    const val PROMPT_VERSION = 1

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 缓存键 = 提示词版本 + 模型 + 数据快照的 SHA-256。
     * 数据或模型任一变化都会得到新键，从而避免把旧结果当成新输入的建议。
     */
    fun cacheKey(promptVersion: Int, model: String, payloadJson: String): String =
        cacheKey("v$promptVersion", model, payloadJson)

    /** V2.0 统一 AI 服务使用带任务前缀的字符串版本，避免不同任务缓存串用。 */
    fun cacheKey(promptVersion: String, model: String, payloadJson: String): String =
        sha256("$promptVersion|${model.trim()}|$payloadJson")
}
