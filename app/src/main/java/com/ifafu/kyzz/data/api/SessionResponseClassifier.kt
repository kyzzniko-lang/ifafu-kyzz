package com.ifafu.kyzz.data.api

/** Pure response classification kept separate so auth edge cases are unit-testable. */
internal object SessionResponseClassifier {
    fun isSessionExpired(html: String): Boolean {
        if (html.contains("id=\"txtUserName\"") && html.contains("id=\"TextBox2\"")) return true
        if (Regex("""location\s*[=.]\s*["'][^"']{0,30}default[2]?\.aspx""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)) return true
        if (Regex("""window\.location\s*=\s*["'][^"']{0,30}default[2]?\.aspx""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)) return true
        return false
    }

    fun isTransientServerError(html: String): Boolean =
        html.contains("<title>ERROR - 出错啦！</title>") || html.contains("系统正忙")

    fun isAuthenticatedLoginResponse(finalUrl: String, html: String): Boolean =
        finalUrl.contains("xs_main.aspx", ignoreCase = true) ||
            html.contains("id=\"xhxm\"", ignoreCase = true) ||
            html.contains("id='xhxm'", ignoreCase = true)
}
