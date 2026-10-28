package com.zhixu.studyos.core.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parseYuanToFen_acceptsTwoDecimalMoneyExactly() {
        assertEquals(1234L, Money.parseYuanToFen("12.34"))
        assertEquals(1L, Money.parseYuanToFen("0.01"))
        assertEquals(1230L, Money.parseYuanToFen("12.3"))
        assertEquals(1200L, Money.parseYuanToFen("12"))
        assertEquals(1200L, Money.parseYuanToFen(" 12.00 "))
        assertEquals(10L, Money.parseYuanToFen("000.10"))
        assertEquals(999_999_999L, Money.parseYuanToFen("9999999.99"))
    }

    @Test
    fun parseYuanToFen_rejectsNegativeZeroInvalidAndOverflow() {
        listOf(
            "",
            "   ",
            "0",
            "0.00",
            "-1",
            "-0.01",
            "- 1",
            "1.234",
            "1,000",
            "1e3",
            "12.",
            ".5",
            "10000000.00",
            "abc",
            "１２.３４",
        ).forEach { input ->
            assertNull("应拒绝金额输入：$input", Money.parseYuanToFen(input))
        }
    }

    @Test
    fun formatFen_usesSymbolGroupingAndTwoDecimals() {
        assertEquals("¥0.00", Money.formatFen(0L))
        assertEquals("¥0.01", Money.formatFen(1L))
        assertEquals("¥1.00", Money.formatFen(100L))
        assertEquals("¥1,234.56", Money.formatFen(123456L))
        assertEquals("¥9,999,999.99", Money.formatFen(999_999_999L))
        assertEquals("-¥1,234.56", Money.formatFen(-123456L))
    }

    @Test
    fun formatPlainYuan_neverUsesGrouping() {
        assertEquals("0.01", Money.formatPlainYuan(1L))
        assertEquals("1234.56", Money.formatPlainYuan(123456L))
        assertEquals("-0.30", Money.formatPlainYuan(-30L))
    }

    @Test
    fun parseAndSum_areNotAffectedByFloatingPointError() {
        val total = listOf("0.10", "0.20", "0.70").sumOf { Money.parseYuanToFen(it)!! }
        assertEquals(100L, total)
        assertEquals("¥1.00", Money.formatFen(total))
        assertEquals(3L, List(3) { Money.parseYuanToFen("0.01")!! }.sum())
    }
}
