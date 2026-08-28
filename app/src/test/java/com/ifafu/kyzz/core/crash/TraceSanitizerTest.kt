package com.ifafu.kyzz.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSanitizerTest {

    @Test
    fun `URL 中的正方会话 token 被掩码`() {
        val raw = "Unexpected response from http://jwgl.fafu.edu.cn/(m3xk21abq0jp4r45isva0q55)/xs_main.aspx?xh=3241100001"
        val out = TraceSanitizer.sanitize(raw)
        assertFalse(out.contains("m3xk21abq0jp4r45isva0q55"))
        assertTrue(out.contains("(TOKEN)/xs_main.aspx"))
        // 学号同样被掩码
        assertFalse(out.contains("3241100001"))
    }

    @Test
    fun `日志式 sessionToken 被掩码`() {
        val out = TraceSanitizer.sanitize("prepareLogin failed: sessionToken=abcdef1234567890abcdef1234567890")
        assertFalse(out.contains("abcdef1234567890"))
        assertTrue(out.contains("sessionToken=<redacted>"))
    }

    @Test
    fun `学号形态数字被掩码`() {
        val out = TraceSanitizer.sanitize("查询成绩失败 account=3241100001")
        assertFalse(out.contains("3241100001"))
        assertTrue(out.contains("[ID]"))
    }

    @Test
    fun `八位日期同样保守掩码`() {
        // 保守策略：8 位日期（如 20240901）与学号形态无法区分，统一掩码
        val out = TraceSanitizer.sanitize("term=20240901")
        assertFalse(out.contains("20240901"))
    }

    @Test
    fun `堆栈行号不误伤`() {
        val raw = """
            java.lang.NullPointerException
                at com.ifafu.kyzz.ui.main.HomeFragment.onViewCreated(HomeFragment.kt:152)
                at com.ifafu.kyzz.Foo(SourceFile)
        """.trimIndent()
        val out = TraceSanitizer.sanitize(raw)
        assertTrue(out.contains("HomeFragment.kt:152"))
        assertTrue(out.contains("(SourceFile)"))
    }

    @Test
    fun `点分 IP 与十三位时间戳不误伤`() {
        val raw = "connect timeout to 116.62.222.192 at 1724900000000ms"
        val out = TraceSanitizer.sanitize(raw)
        assertTrue(out.contains("116.62.222.192"))
        assertTrue(out.contains("1724900000000ms"))
    }

    @Test
    fun `完整崩溃样例整体净化`() {
        val raw = """
            java.io.IOException: unexpected code 302 for http://jwgl.fafu.edu.cn/(dm5xwv3hgfp4r55ws1tbxm45)/xskbcx.aspx?xh=3251200123&xm=%E5%BC%A0%E4%B8%89
                at okhttp3.internal.connection.RealCall.execute(RealCall.kt:85)
        """.trimIndent()
        val out = TraceSanitizer.sanitize(raw)
        assertFalse(out.contains("dm5xwv3hgfp4r55ws1tbxm45"))
        assertFalse(out.contains("3251200123"))
        assertFalse(out.contains("%E5%BC%A0%E4%B8%89") || out.contains("张三"))
        assertEquals("at okhttp3.internal.connection.RealCall.execute(RealCall.kt:85)",
            out.lines().last().trim())
    }
}
