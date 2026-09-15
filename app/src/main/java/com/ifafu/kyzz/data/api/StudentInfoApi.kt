package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.StudentInfoParser
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentInfoApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val studentInfoParser: StudentInfoParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper
) {

    companion object {
        private const val TAG = "StudentInfoApi"

        /** Prefer the school's current menu link over a permanently hard-coded module URL. */
        internal fun resolveStudentInfoUrl(menuHtml: String, menuUrl: String, fallbackUrl: String): String {
            val menuDoc = Jsoup.parse(menuHtml, menuUrl)
            return menuDoc.select("a[href]")
                .firstOrNull { it.attr("href").contains("xsgrxx.aspx", ignoreCase = true) }
                ?.absUrl("href")
                ?.takeIf { it.isNotBlank() }
                ?: fallbackUrl
        }

        /** A valid page can contain access-related alert text inside its own validation scripts. */
        internal fun isStudentInfoPage(doc: Document): Boolean =
            doc.select("[id^=lbxsgrxx_]").isNotEmpty() ||
                (doc.text().contains("姓名") && listOf("学院", "专业名称", "行政班", "身份证号")
                    .any(doc.text()::contains))
    }

    suspend fun getStudentInfo(host: String, token: String, number: String, name: String): StudentInfo? {
        return try {
            fetchStudentInfo(host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) {
            // 登录页或页面级拦截出现时，统一重登一次后按完整菜单链路重取。
            reloginHelper.reloginAndRetry(e, number, host) { user ->
                fetchStudentInfo(host, user.token, user.account, user.name)
            }
        }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch student info", e)
            null
        }
    }

    private suspend fun fetchStudentInfo(host: String, token: String, number: String, name: String): StudentInfo? {
        val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"
        val menuUrl = "${host}/(${token})/xsleft.aspx?xh=${number}"
        val fallbackUrl = "${host}/(${token})/xsgrxx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121501"

        // 模拟网页端真实导航。部分正方部署会校验菜单访问上下文或 Referer，
        // 直接请求 xsgrxx.aspx 即使 token 有效也会提示“禁止使用此功能”。
        val mainHtml = htmlClient.getStringRawWithReferer(mainUrl, mainUrl)
        if (userApi.isSessionExpired(mainHtml)) {
            throw AlertException("会话已过期，请重新登录", isSessionExpired = true)
        }
        val menuHtml = htmlClient.getStringRawWithReferer(menuUrl, mainUrl)
        throwIfSessionOrPageAlert(menuHtml)
        val accessUrl = resolveStudentInfoUrl(menuHtml, menuUrl, fallbackUrl)
        val html = htmlClient.getStringRawWithReferer(accessUrl, menuUrl)
        val doc = Jsoup.parse(html, accessUrl)

        // 学籍页面本身带有大量 alert 校验脚本。只要真实字段已返回，就应优先
        // 解析业务内容，不能再让通用 alert 扫描把正常页面判成访问错误。
        if (isStudentInfoPage(doc)) {
            return studentInfoParser.parseStudentInfo(doc, number)
        }

        throwIfSessionOrPageAlert(html)
        Log.w(TAG, "Student info response contained neither fields nor a recognized error")
        return null
    }

    private fun throwIfSessionOrPageAlert(html: String) {
        htmlClient.checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
        if (userApi.isSessionExpired(html)) {
            throw AlertException("会话已过期，请重新登录", isSessionExpired = true)
        }
    }
}
