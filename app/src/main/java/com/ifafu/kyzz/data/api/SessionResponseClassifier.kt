package com.ifafu.kyzz.data.api

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

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
        // 兜底：页面含密码输入框且不具备任何业务页特征才判过期。修改密码页
        // (mmxg.aspx)等合法业务页同样内嵌密码输入框，只看密码框会把整页误判
        // 为会话过期，触发清 cookie + 无限重登循环。
        if (("type=\"password\"" in norm || "type=password" in norm) &&
            !looksLikeBusinessPage(html)
        ) {
            Log.w(TAG, "会话过期判定命中:页面含密码输入框且无业务页特征")
            return true
        }
        // 正方对"会话失效后直连功能页"（URL 带过期 token 访问 xsgrxx 等）的
        // 一种拦截响应：不返回登录页，而是顶层 alert（对不起,禁止使用此功能）。
        // 不识别它的话，各 API 的自动重登链路会被跳过，教务原文被直接甩给用户。
        // 判为过期以复用既有的重登重试；若重登后仍被拒（学校真禁用该功能），
        // 重试路径的 throwIfAlert 会照常抛出原文，不受此影响。
        // 正常业务页也可能在校验函数中预置同名 alert（个人信息页即如此），
        // 因此只有完全不具备业务页特征的纯拦截响应才能据此判会话过期。
        if (("禁止使用此功能" in html || "禁止使用该功能" in html) &&
            !looksLikeBusinessPage(html)
        ) {
            Log.w(TAG, "会话过期判定命中:页面为\"禁止使用此功能\"拦截 alert")
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
        "gridlines", "xscjcx", "pyjh", "lbxsgrxx_"
    ).any { html.lowercase().contains(it) }

    fun isTransientServerError(html: String): Boolean =
        html.contains("<title>ERROR - 出错啦！</title>") || html.contains("系统正忙")

    fun isAuthenticatedLoginResponse(finalUrl: String, html: String): Boolean {
        if (isSessionExpired(html) || isTransientServerError(html)) return false
        val mainPage = finalUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
            ?.equals("xs_main.aspx", ignoreCase = true) == true
        return mainPage || Jsoup.parse(html).getElementById("xhxm") != null
    }
}
