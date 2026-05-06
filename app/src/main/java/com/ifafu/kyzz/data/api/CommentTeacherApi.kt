package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.network.HtmlClient
import kotlinx.coroutines.delay
import java.net.URLEncoder
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentTeacherApi @Inject constructor(
    private val htmlClient: HtmlClient
) {

    suspend fun commentAllTeachers(host: String, token: String, number: String, name: String): Response {
        val accessUrl = "${host}/(${token})/xsjxpj2fafu.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121400"

        val html = htmlClient.getString(accessUrl)
        if (html.isBlank()) return Response(false, -1, "网络异常")

        val alert = htmlClient.checkAlert(html)
        if (alert != null) return Response(false, -2, alert.message)

        val patternList = Regex("open\\('(.*?)',")
        patternList.findAll(html).forEach { match ->
            delay(300)
            if (!commentTeacher(host, token, match.groupValues[1])) {
                return Response(false, -3, "网络异常")
            }
        }

        htmlClient.extractViewState(html)
        val formBody = htmlClient.buildFormBody(
            "__EVENTARGUMENT" to "",
            "__EVENTTARGET" to "",
            "__VIEWSTATE" to htmlClient.viewState,
            "__VIEWSTATEGENERATOR" to htmlClient.viewStateGenerator,
            "btn_tj" to "提 交"
        )

        val postHtml = htmlClient.postString(accessUrl, formBody)
        if (postHtml.isBlank()) return Response(false, -4, "网络异常")

        val m = Regex("alert\\('(.*?)'\\)").find(postHtml)
        return if (m != null && m.groupValues[1].contains("完成评价")) {
            Response(true, 0, "评教成功")
        } else {
            Response(false, -5, m?.groupValues?.get(1) ?: "评教失败")
        }
    }

    private suspend fun commentTeacher(host: String, token: String, path: String): Boolean {
        val accessUrl = "${host}/(${token})/${path}"
        val random = Random()

        val html = htmlClient.getString(accessUrl)
        if (html.isBlank()) return false

        htmlClient.extractViewState(html)

        val formBuilder = okhttp3.FormBody.Builder(charset("GBK"))
        val pattern = Regex("table id=\"Datagrid1__(.*?)_rb\"")
        pattern.findAll(html).forEach { match ->
            val value = if (random.nextInt(100) > 10) "94" else "82"
            formBuilder.add("Datagrid1%3A_${match.groupValues[1]}%3Arb", value)
        }

        formBuilder.add("Datagrid1%3A_${String.format("ctl%d", 4 + random.nextInt(2))}%3Arb", "94")
        formBuilder.add("Datagrid1%3A_${String.format("ctl%d", 2 + random.nextInt(2))}%3Arb", "82")
        formBuilder.add("__VIEWSTATE", htmlClient.viewState)
        formBuilder.add("__VIEWSTATEGENERATOR", htmlClient.viewStateGenerator)
        formBuilder.add("txt_pjxx", "")
        formBuilder.add("Button1", "提  交")

        val postHtml = htmlClient.postString(accessUrl, formBuilder.build())
        return postHtml.contains("提交成功")
    }
}
