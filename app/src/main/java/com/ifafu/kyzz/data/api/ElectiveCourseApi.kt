package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.ElectiveCourseList
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ElectiveParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ElectiveCourseApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val electiveParser: ElectiveParser
) {

    suspend fun getElectiveCourseIndex(
        host: String, token: String, number: String, name: String, courseList: ElectiveCourseList
    ): Response {
        val accessUrl = "${host}/(${token})/xf_xsqxxxk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121400"

        val html = htmlClient.getString(accessUrl)
        if (html.isBlank()) return Response(false, -1, "网络异常")

        if (html.contains("账号或密码") || html.contains("请登录") || html.contains("default.aspx") || html.contains("default2.aspx")) {
            return Response(false, -1, "会话已失效，请重新登录")
        }

        val alert = htmlClient.checkAlert(html)
        if (alert != null) return Response(false, 0, alert.message)
        if (html.contains("防刷")) return Response(false, 0, "防刷限制")

        htmlClient.extractViewState(html)
        val doc = org.jsoup.Jsoup.parse(html)
        electiveParser.parseFilter(doc, courseList.filter)
        electiveParser.parseCourseList(doc, courseList)
        return Response(true, 0, "获取成功")
    }

    suspend fun searchElectiveCourse(
        host: String, token: String, number: String, name: String, courseList: ElectiveCourseList
    ): Response {
        val accessUrl = "${host}/(${token})/xf_xsqxxxk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121203"
        val filter = courseList.filter
        val courseName = filter.courseNameFilter ?: ""

        val formBody = htmlClient.buildFormBody(
            "__EVENTTARGET" to "ddl_kcgs",
            "__EVENTARGUMENT" to "",
            "__VIEWSTATE" to htmlClient.viewState,
            "__VIEWSTATEGENERATOR" to htmlClient.viewStateGenerator,
            "ddl_kcxz" to filter.courseNature.getOrElse(filter.courseNatureIndex) { "" },
            "ddl_ywyl" to filter.isFree.getOrElse(filter.isFreeIndex) { "" },
            "ddl_kcgs" to filter.courseOwner.getOrElse(filter.courseOwnerIndex) { "" },
            "ddl_xqbs" to filter.courseCampus.getOrElse(filter.courseCampusIndex) { "" },
            "ddl_sksj" to filter.courseTime.getOrElse(filter.courseTimeIndex) { "" },
            "TextBox1" to courseName,
            "dpkcmcGrid%3AtxtChoosePage" to courseList.curPage.toString(),
            "dpkcmcGrid%3AtxtPageSize" to "15"
        )

        val html = htmlClient.postString(accessUrl, formBody)
        if (html.isBlank()) return Response(false, 0, "网络异常")

        htmlClient.extractViewState(html)
        val doc = org.jsoup.Jsoup.parse(html)
        electiveParser.parseCourseList(doc, courseList)
        return Response(true, 0, "获取成功")
    }

    suspend fun electiveCourse(
        host: String, token: String, number: String, name: String,
        courseList: ElectiveCourseList, courseIndex: String
    ): Response {
        val accessUrl = "${host}/(${token})/xf_xsqxxxk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121203"
        val filter = courseList.filter

        val formBody = htmlClient.buildFormBody(
            "__EVENTTARGET" to "",
            "__EVENTARGUMENT" to "",
            "__VIEWSTATE" to htmlClient.viewState,
            "__VIEWSTATEGENERATOR" to htmlClient.viewStateGenerator,
            "ddl_kcxz" to filter.courseNature.getOrElse(filter.courseNatureIndex) { "" },
            "ddl_ywyl" to filter.isFree.getOrElse(filter.isFreeIndex) { "" },
            "ddl_kcgs" to filter.courseOwner.getOrElse(filter.courseOwnerIndex) { "" },
            "ddl_xqbs" to filter.courseCampus.getOrElse(filter.courseCampusIndex) { "" },
            "ddl_sksj" to filter.courseTime.getOrElse(filter.courseTimeIndex) { "" },
            "TextBox1" to (filter.courseNameFilter ?: ""),
            "dpkcmcGrid%3AtxtChoosePage" to courseList.curPage.toString(),
            "dpkcmcGrid%3AtxtPageSize" to "15",
            courseIndex to "on",
            "Button1" to "提  交"
        )

        val html = htmlClient.postString(accessUrl, formBody)
        if (html.isBlank()) return Response(false, 0, "网络异常")

        htmlClient.extractViewState(html)
        val alert = htmlClient.checkAlert(html)
        return if (alert != null) {
            Response(false, 0, alert.message)
        } else {
            Response(true, 0, "选课成功")
        }
    }
}
