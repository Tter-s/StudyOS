package com.zhixu.studyos.core.ai

import com.zhixu.studyos.core.model.AiProviderConfig
import java.util.Locale

/**
 * Token 费用估算。价格以「每百万 token 的微元数」配置（1 元 = 1,000,000 微元），
 * 全程整数运算并把结果四舍五入到微元，避免浮点累积误差。
 * 这是估算值：实际账单以服务商为准。
 */
object AiCostCalculator {

    fun estimateMicros(
        promptTokens: Int,
        completionTokens: Int,
        config: AiProviderConfig,
    ): Long {
        val safePrompt = promptTokens.coerceAtLeast(0).toLong()
        val safeCompletion = completionTokens.coerceAtLeast(0).toLong()
        val raw = safePrompt * config.inputPriceMicrosPerMillionTokens +
            safeCompletion * config.outputPriceMicrosPerMillionTokens
        // 除以 1,000,000 并四舍五入
        return (raw + 500_000L) / 1_000_000L
    }

    /** 用于在缺少 usage 字段时粗略估算 token 数：按字符数/4 向上取整。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return ((text.length + 3) / 4).coerceAtLeast(1)
    }

    fun formatYuan(micros: Long): String {
        val value = micros / 1_000_000.0
        return "%.4f 元".format(Locale.ROOT, value)
    }

    fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> "%.2fM".format(Locale.ROOT, tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.1fk".format(Locale.ROOT, tokens / 1_000.0)
        else -> tokens.toString()
    }
}
