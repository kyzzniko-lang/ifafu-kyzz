package com.ifafu.kyzz.core.crash

/**
 * 崩溃/ANR 上报内容脱敏器（纯函数，可单测）。
 *
 * 上报目标是公开可读的 GitHub Issue，异常消息可能内嵌：
 * - 正方教务系统的会话 token（形如 `/(m3xk21abq0jp4r45isva0q55)/xs_main.aspx`）
 * - 日志格式 `sessionToken=xxx`
 * - 学号（8-12 位连续数字，亦出现在 `?xh=` 查询参数中）
 *
 * 规则刻意保守：宁可多掩码也不漏。掩码后保留结构，诊断价值不受影响。
 */
object TraceSanitizer {

    /**
     * 正方 URL 路径中的会话 token：括号内 20-64 位字母数字（兼容大写与更长
     * 的变体）。堆栈帧里的 `(SourceFile)`/`(Foo.kt:12)` 含标点或长度不足，
     * 不会误伤；个别纯字母异常类名（如 `(StreamResetException)`）会被过度
     * 掩码为 (TOKEN)——按"宁可多掩码"原则接受。
     */
    private val TOKEN_IN_URL = Regex("""\(([a-zA-Z0-9]{20,64})\)""")

    /** 日志式 token 打印（大小写兼容） */
    private val TOKEN_LOGGED = Regex("""sessionToken=\S+""", RegexOption.IGNORE_CASE)

    /** 正方 URL 的 xm 查询参数携带 URL 编码的真实姓名（如 xm=%E5%BC%A0...） */
    private val NAME_QUERY = Regex("""\bxm=[^&\s]+""")

    /** 学号形态：8-12 位连续数字（13 位毫秒时间戳、IP 点分四段、行号均不命中） */
    private val STUDENT_ID_LIKE = Regex("""\b\d{8,12}\b""")

    fun sanitize(text: String): String = text
        .replace(TOKEN_IN_URL, "(TOKEN)")
        .replace(TOKEN_LOGGED, "sessionToken=<redacted>")
        .replace(NAME_QUERY, "xm=<NAME>")
        .replace(STUDENT_ID_LIKE, "[ID]")
}
