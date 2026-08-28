package com.ifafu.kyzz.data.api

import android.util.Log

/** Pure response classification kept separate so auth edge cases are unit-testable. */
internal object SessionResponseClassifier {
    private const val TAG = "SessionClassifier"

    fun isSessionExpired(html: String): Boolean {
        // 经典正方登录页：账号(txtUserName)+密码(TextBox2)双控件。
        // 归一化引号与大小写后比对,单/双引号、任意属性大小写都覆盖
        val norm = html.lowercase().replace('\'', '"')
        if ("id=\"txtusername\"" in norm && "id=\"textbox2\"" in norm) return true
        // 各式跳回登录页的脚本写法：location.href= / location.replace( / location=，
        // 引号可省略，default.aspx 与 default2.aspx 及其大小写变体都要覆盖
        if (Regex("""location\s*(\.href\s*=|\.replace\s*\(|\s*=\s*)["']?\s*[^"'>)]{0,30}default[2]?\.aspx""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)) return true
        if (Regex("""window\.location\s*=\s*["'][^"']{0,30}default[2]?\.aspx""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)) return true
        // 页面标题声明为登录页：教务端换皮肤改控件 id 时标题通常仍是"用户登录"
        if (Regex("""<title>[^<]{0,40}(用户登录|统一身份认证|身份认证)[^<]{0,20}</title>""", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)) {
            Log.w(TAG, "会话过期判定命中:页面标题为登录页")
            return true
        }
        // 兜底：任意包含密码输入框的页面不可能是已登录的业务数据页
        if ("type=\"password\"" in norm || "type=password" in norm) {
            Log.w(TAG, "会话过期判定命中:页面含密码输入框")
            return true
        }
        return false
    }

    /**
     * 页面是否具备本系统各业务页的数据特征（成绩/课表/计划表格、学号姓名栏）。
     * 用于解析结果为空时的二次校验：业务特征全缺失的"成功响应"极可能是
     * 分类器未识别的登录页或中间页，绝不能当空数据入库。
     * 注意不能放 __VIEWSTATE——它同样存在于登录页上，起不到区分作用。
     */
    fun looksLikeBusinessPage(html: String): Boolean = listOf(
        "datagrid1", "datagrid2", "dbgrid", "xhxm", "kbtable",
        "gridlines", "xscjcx", "pyjh"
    ).any { html.lowercase().contains(it) }

    fun isTransientServerError(html: String): Boolean =
        html.contains("<title>ERROR - 出错啦！</title>") || html.contains("系统正忙")

    fun isAuthenticatedLoginResponse(finalUrl: String, html: String): Boolean =
        finalUrl.contains("xs_main.aspx", ignoreCase = true) ||
            html.contains("id=\"xhxm\"", ignoreCase = true) ||
            html.contains("id='xhxm'", ignoreCase = true)
}
