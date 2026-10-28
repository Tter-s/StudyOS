package com.zhixu.studyos.core.finance

/**
 * 人民币金额工具。所有金额以 Long 分存储和计算，解析与格式化都不经过 Double。
 */
object Money {
    /** ¥9,999,999.99；限制输入规模，避免超长数字和无意义的预算。 */
    const val MAX_AMOUNT_FEN: Long = 999_999_999L

    private val amountPattern = Regex("""^\d{1,7}(\.\d{1,2})?$""")

    /**
     * 把用户输入的元金额解析为分。只接受非负、最多两位小数的十进制数字；
     * 负数、科学计数法、千分位分隔符、超过上限和零都返回 null。
     */
    fun parseYuanToFen(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !amountPattern.matches(trimmed)) return null
        val parts = trimmed.split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        val fenPart = if (parts.size == 2) {
            parts[1].padEnd(2, '0').toLongOrNull() ?: return null
        } else {
            0L
        }
        val fen = yuan * 100 + fenPart
        if (fen <= 0L || fen > MAX_AMOUNT_FEN) return null
        return fen
    }

    /** 带货币符号和千分位的展示格式，例如 ¥1,234.56；负数为 -¥1,234.56。 */
    fun formatFen(fen: Long): String {
        val negative = fen < 0L
        val absolute = if (negative) -fen else fen
        val yuan = absolute / 100L
        val cents = absolute % 100L
        val grouped = yuan.toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
        return buildString {
            if (negative) append('-')
            append('¥')
            append(grouped)
            append('.')
            append(cents.toString().padStart(2, '0'))
        }
    }

    /** 不带符号、千分位和货币单位的元金额，用于 CSV 和输入框回填。 */
    fun formatPlainYuan(fen: Long): String {
        val negative = fen < 0L
        val absolute = if (negative) -fen else fen
        val yuan = absolute / 100L
        val cents = absolute % 100L
        return buildString {
            if (negative) append('-')
            append(yuan)
            append('.')
            append(cents.toString().padStart(2, '0'))
        }
    }
}
