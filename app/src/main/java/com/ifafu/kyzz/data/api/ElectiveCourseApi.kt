package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.ElectiveCourseList
import com.ifafu.kyzz.data.model.ElectiveCourse
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ElectiveParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ElectiveCourseApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val electiveParser: ElectiveParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    companion object {
        private const val TAG = "ElectiveCourseApi"

        internal fun confirmsSelection(selected: List<ElectiveCourse>, requested: ElectiveCourse): Boolean =
            selected.count {
                it.name == requested.name &&
                    (requested.code.isBlank() || it.code == requested.code) &&
                    (requested.teacher.isBlank() || it.teacher == requested.teacher) &&
                    (requested.time.isBlank() || it.time == requested.time) &&
                    (requested.location.isBlank() || it.location == requested.location)
            } == 1
    }

    suspend fun getElectiveCourseIndex(
        host: String, token: String, number: String, name: String, courseList: ElectiveCourseList
    ): Response {
        return try {
            val accessUrl = "${host}/(${token})/xf_xsqxxxk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121203"

            // 用 Raw 变体取回原始页面：选课入口页可能以顶层 alert 提示
            // "现在不是选课时间"等，若让 getString 抛 AlertException，
            // 下方 parseElectiveCourseIndex 的 alert 分类就永远走不到，
            // 用户只能看到笼统的"网络异常"。
            val html = htmlClient.getStringRaw(accessUrl)
            if (html.isBlank()) return Response(false, -1, "网络异常")

            if (userApi.isSessionExpired(html)) {
                val reloginResp = reloginHelper.relogin()
                if (!reloginResp.success) return Response(false, -1, reloginResp.message)
                val user = userRepository.getUser()
                // 等待重登期间账号/主机可能已切换,旧会话参数不得混入新用户
                if (user.account != number || userRepository.host != host) {
                    return Response(false, -1, "登录信息已变更，请刷新重试")
                }
                val retryUrl = "${host}/(${user.token})/xf_xsqxxxk.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121203"
                val retryHtml = htmlClient.getStringRaw(retryUrl)
                if (retryHtml.isBlank() || userApi.isSessionExpired(retryHtml)) {
                    return Response(false, -1, "会话已过期，请重新登录")
                }
                return parseElectiveCourseIndex(retryHtml, courseList)
            }

            parseElectiveCourseIndex(html, courseList)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to get elective course index", e)
            Response(false, -1, "网络异常")
        }
    }

    private fun parseElectiveCourseIndex(html: String, courseList: ElectiveCourseList): Response {
        val errorPage = htmlClient.checkErrorPage(html)
        if (errorPage != null) {
            return Response(false, -1, errorPage.message)
        }
        val alert = htmlClient.checkAlert(html)
        if (alert != null) {
            Log.w(TAG, "Alert found: ${alert.message}")
            return Response(false, 0, alert.message)
        }
        if (html.contains("防刷")) return Response(false, 0, "防刷限制")

        val vs = htmlClient.parseViewState(html)
        courseList.viewState = vs.viewState
        courseList.viewStateGenerator = vs.viewStateGenerator
        val doc = org.jsoup.Jsoup.parse(html)
        electiveParser.parseFilter(doc, courseList.filter)
        electiveParser.parseCourseList(doc, courseList)
        Log.d(TAG, "Parsed: ${courseList.courses.size} available, ${courseList.electived.size} selected, htmlLen=${html.length}")
        return Response(true, 0, "获取成功")
    }

    suspend fun searchElectiveCourse(
        host: String, token: String, number: String, name: String, courseList: ElectiveCourseList
    ): Response {
        return try {
            searchElectiveCourseInternal(host, token, number, name, courseList)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to search elective course", e)
            Response(false, -1, "网络异常")
        }
    }

    private suspend fun searchElectiveCourseInternal(
        host: String, token: String, number: String, name: String, courseList: ElectiveCourseList,
        depth: Int = 0
    ): Response {
        val accessUrl = "${host}/(${token})/xf_xsqxxxk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121203"
        val filter = courseList.filter
        val courseName = filter.courseNameFilter ?: ""

        val formBody = htmlClient.buildFormBody(
            "__EVENTTARGET" to "ddl_kcgs",
            "__EVENTARGUMENT" to "",
            "__VIEWSTATE" to courseList.viewState,
            "__VIEWSTATEGENERATOR" to courseList.viewStateGenerator,
            "ddl_kcxz" to filter.courseNature.getOrElse(filter.courseNatureIndex) { "" },
            "ddl_ywyl" to filter.isFree.getOrElse(filter.isFreeIndex) { "" },
            "ddl_kcgs" to filter.courseOwner.getOrElse(filter.courseOwnerIndex) { "" },
            "ddl_xqbs" to filter.courseCampus.getOrElse(filter.courseCampusIndex) { "" },
            "ddl_sksj" to filter.courseTime.getOrElse(filter.courseTimeIndex) { "" },
            "TextBox1" to courseName,
            "dpkcmcGrid:txtChoosePage" to courseList.curPage.toString(),
            "dpkcmcGrid:txtPageSize" to "15"
        )

        // Raw 变体：筛选请求的响应可能带顶层 alert（如防刷/未开放提示），
        // 抛异常会被外层 catch 吞成"网络异常"，这里取回后自行分类。
        val html = htmlClient.postStringRaw(accessUrl, formBody)
        if (html.isBlank()) return Response(false, 0, "网络异常")

        if (userApi.isSessionExpired(html)) {
            if (depth >= 2) return Response(false, -1, "会话已过期，请重新登录")
            Log.d(TAG, "Session expired during search, attempting relogin...")
            val reloginResp = reloginHelper.relogin()
            if (!reloginResp.success) return Response(false, -1, reloginResp.message)
            val user = userRepository.getUser()
            // 重登等待期间账号/主机可能已切换（守卫同 getElectiveCourseIndex）：
            // 继续用 userRepository 当前账号刷新列表并递归搜索，会把 A 界面
            // 触发的搜索/提交落到 B 账号的会话上。
            if (user.account != number || userRepository.host != host) {
                return Response(false, -1, "登录信息已变更，请刷新重试")
            }
            // 重登后旧 __VIEWSTATE 失效：先重新拉取列表页刷新状态再重试搜索。
            val indexResp = getElectiveCourseIndex(host, user.token, user.account, user.name, courseList)
            if (!indexResp.success) return indexResp
            return searchElectiveCourseInternal(host, user.token, user.account, user.name, courseList, depth + 1)
        }

        val searchError = htmlClient.checkErrorPage(html)
        if (searchError != null) {
            return Response(false, -1, searchError.message)
        }

        val searchAlert = htmlClient.checkAlert(html)
        if (searchAlert != null) {
            return Response(false, 0, searchAlert.message)
        }

        val vs = htmlClient.parseViewState(html)
        courseList.viewState = vs.viewState
        courseList.viewStateGenerator = vs.viewStateGenerator
        val doc = org.jsoup.Jsoup.parse(html)
        electiveParser.parseCourseList(doc, courseList)
        return Response(true, 0, "获取成功")
    }

    suspend fun electiveCourse(
        host: String, token: String, number: String, name: String,
        courseList: ElectiveCourseList, courseIndex: String
    ): Response {
        return try {
            electiveCourseInternal(host, token, number, name, courseList, courseIndex)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to select elective course", e)
            Response(false, -1, "网络异常")
        }
    }

    private suspend fun electiveCourseInternal(
        host: String, token: String, number: String, name: String,
        courseList: ElectiveCourseList, courseIndex: String,
        depth: Int = 0
    ): Response {
        val requestedCourse = courseList.courses.singleOrNull { it.courseIndex == courseIndex }?.copy()
        if (courseIndex.isBlank() || requestedCourse == null) {
            return Response(false, -1, "课程列表已变更，请刷新后重新选择")
        }
        val accessUrl = "${host}/(${token})/xf_xsqxxxk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121203"
        val filter = courseList.filter

        val formBody = htmlClient.buildFormBody(
            "__EVENTTARGET" to "",
            "__EVENTARGUMENT" to "",
            "__VIEWSTATE" to courseList.viewState,
            "__VIEWSTATEGENERATOR" to courseList.viewStateGenerator,
            "ddl_kcxz" to filter.courseNature.getOrElse(filter.courseNatureIndex) { "" },
            "ddl_ywyl" to filter.isFree.getOrElse(filter.isFreeIndex) { "" },
            "ddl_kcgs" to filter.courseOwner.getOrElse(filter.courseOwnerIndex) { "" },
            "ddl_xqbs" to filter.courseCampus.getOrElse(filter.courseCampusIndex) { "" },
            "ddl_sksj" to filter.courseTime.getOrElse(filter.courseTimeIndex) { "" },
            "TextBox1" to (filter.courseNameFilter ?: ""),
            "dpkcmcGrid:txtChoosePage" to courseList.curPage.toString(),
            "dpkcmcGrid:txtPageSize" to "15",
            courseIndex to "on",
            "Button1" to "提  交"
        )

        // 必须用 postStringRaw：正方选课提交后无论成功（"选课成功"）还是失败
        // （"该课堂人数已满"）都以顶层 alert 返回。postString 会对 alert 抛
        // AlertException，被外层 catch 吞成"网络异常"——明明选上了却提示失败。
        val html = htmlClient.postStringRaw(accessUrl, formBody)
        if (html.isBlank()) return Response(false, 0, "网络异常")

        if (userApi.isSessionExpired(html)) {
            if (depth >= 2) return Response(false, -1, "会话已过期，请重新登录")
            Log.d(TAG, "Session expired during elective, attempting relogin...")
            val reloginResp = reloginHelper.relogin()
            if (!reloginResp.success) return Response(false, -1, reloginResp.message)
            val user = userRepository.getUser()
            // 重登等待期间账号/主机可能已切换（守卫同 getElectiveCourseIndex）：
            // 不校验就递归重新提交，会把 A 界面触发的选课提交到 B 账号。
            if (user.account != number || userRepository.host != host) {
                return Response(false, -1, "登录信息已变更，请刷新重试")
            }
            // 重登后是全新 ASP.NET 会话，旧的 __VIEWSTATE 大概率失效，
            // 直接原样重发 POST 只会拿到校验失败页；先重新拉取列表页刷新
            // viewState/筛选项，再重试提交。
            val indexResp = getElectiveCourseIndex(host, user.token, user.account, user.name, courseList)
            if (!indexResp.success) return indexResp
            // 控件名标识的是当前页面的行，而不是课程。重登后的行顺序可能变化，
            // 复用旧控件名会提交另一门课；刷新后让用户重新选择。
            return Response(false, -1, "登录已恢复，课程列表已刷新，请重新选择课程")
        }

        val vs = htmlClient.parseViewState(html)
        courseList.viewState = vs.viewState
        courseList.viewStateGenerator = vs.viewStateGenerator
        val alert = htmlClient.checkAlert(html)
        // 错误页（如 __VIEWSTATE 校验失败返回"出错啦"）既无会话过期标记也无
        // alert，不检查会被误报成"选课成功"。
        val errorPage = htmlClient.checkErrorPage(html)
        return when {
            errorPage != null -> Response(false, -1, errorPage.message)
            // 提交成功（"选课成功"）与失败（"该课堂人数已满"）都以顶层 alert 返回，
            // 消息含"成功"必须判成功：否则明明选上了却显示 Error，课表缓存也不失效。
            alert != null && alert.message.contains("成功") -> Response(true, 0, alert.message)
            alert != null -> Response(false, 0, alert.message)
            else -> {
                // 无提示时只以已选列表作成功证据，普通错误 HTML 不能默认为成功。
                val returned = ElectiveCourseList()
                electiveParser.parseCourseList(org.jsoup.Jsoup.parse(html), returned)
                if (confirmsSelection(returned.electived, requestedCourse)) {
                    Response(true, 0, "选课成功")
                } else {
                    Response(false, -1, "教务未确认选课结果，请刷新课程列表后查看")
                }
            }
        }
    }
}
