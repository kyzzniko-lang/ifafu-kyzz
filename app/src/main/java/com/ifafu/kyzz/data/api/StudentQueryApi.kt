package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.CourseSelection
import com.ifafu.kyzz.data.model.MakeupExam
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.HtmlParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentQueryApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository,
    private val htmlParser: HtmlParser
) {
    companion object {
        private const val TAG = "StudentQueryApi"
    }

    suspend fun getCourseSelections(
        host: String, token: String, number: String, name: String
    ): QueryResult<List<CourseSelection>> {
        return try {
            val url = "${host}/(${token})/xsxkqk.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121615"
            val html = htmlClient.getString(url)
            if (html.isBlank()) return QueryResult(false, "网络异常")

            if (userApi.isSessionExpired(html)) {
                val reloginResp = reloginHelper.relogin()
                if (!reloginResp.success) return QueryResult(false, reloginResp.message)
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/xsxkqk.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121615"
                val retryHtml = htmlClient.getString(retryUrl)
                if (retryHtml.isBlank() || userApi.isSessionExpired(retryHtml)) {
                    return QueryResult(false, "会话已过期，请重新登录")
                }
                return parseCourseSelections(retryHtml)
            }

            parseCourseSelections(html)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to get course selections", e)
            QueryResult(false, "网络异常")
        }
    }

    private fun parseCourseSelections(html: String): QueryResult<List<CourseSelection>> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table")
        val selections = mutableListOf<CourseSelection>()

        for (table in tables) {
            val headers = table.select("tr").firstOrNull()?.select("th, td") ?: continue
            val headerTexts = headers.map { it.text().trim() }
            if (!headerTexts.any { it.contains("课程名称") }) continue

            val colMap = mutableMapOf<String, Int>()
            headerTexts.forEachIndexed { i, h ->
                when {
                    h.contains("选课课号") -> colMap["number"] = i
                    h.contains("课程代码") -> colMap["code"] = i
                    h.contains("课程名称") -> colMap["name"] = i
                    h.contains("课程性质") -> colMap["nature"] = i
                    h.contains("是否选课") -> colMap["selected"] = i
                    h.contains("教师") -> colMap["teacher"] = i
                    h.contains("学分") -> colMap["credits"] = i
                    h.contains("周学时") -> colMap["weekHours"] = i
                    h.contains("上课时间") -> colMap["time"] = i
                    h.contains("上课地点") -> colMap["location"] = i
                    h.contains("教材") -> colMap["textbook"] = i
                    h.contains("修读标记") -> colMap["mark"] = i
                    h.contains("备注") -> colMap["remark"] = i
                }
            }

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val cells = rows[i].select("td")
                if (cells.size < 4) continue

                fun cell(key: String): String {
                    val idx = colMap[key] ?: return ""
                    return if (idx < cells.size) htmlParser.cleanNbsp(cells[idx].text().trim()) else ""
                }

                val sel = CourseSelection(
                    courseNumber = cell("number"),
                    courseCode = cell("code"),
                    courseName = cell("name"),
                    courseNature = cell("nature"),
                    selected = cell("selected"),
                    teacher = cell("teacher"),
                    credits = cell("credits"),
                    weekHours = cell("weekHours"),
                    time = cell("time"),
                    location = cell("location"),
                    textbook = cell("textbook"),
                    studyMark = cell("mark"),
                    remark = cell("remark")
                )
                if (sel.courseName.isNotBlank()) {
                    selections.add(sel)
                }
            }
            break
        }

        return QueryResult(true, "获取成功", selections)
    }

    suspend fun getMakeupExams(
        host: String, token: String, number: String, name: String
    ): QueryResult<List<MakeupExam>> {
        return try {
            val url = "${host}/(${token})/xsbkkscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121617"
            val html = htmlClient.getString(url)
            if (html.isBlank()) return QueryResult(false, "网络异常")

            if (userApi.isSessionExpired(html)) {
                val reloginResp = reloginHelper.relogin()
                if (!reloginResp.success) return QueryResult(false, reloginResp.message)
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/xsbkkscx.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121617"
                val retryHtml = htmlClient.getString(retryUrl)
                if (retryHtml.isBlank() || userApi.isSessionExpired(retryHtml)) {
                    return QueryResult(false, "会话已过期，请重新登录")
                }
                return parseMakeupExams(retryHtml)
            }

            parseMakeupExams(html)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to get makeup exams", e)
            QueryResult(false, "网络异常")
        }
    }

    private fun parseMakeupExams(html: String): QueryResult<List<MakeupExam>> {
        val doc = Jsoup.parse(html)
        val tables = doc.select("table")
        val exams = mutableListOf<MakeupExam>()

        for (table in tables) {
            val headers = table.select("tr").firstOrNull()?.select("th, td") ?: continue
            val headerTexts = headers.map { it.text().trim() }
            if (!headerTexts.any { it.contains("课程名称") }) continue

            val colMap = mutableMapOf<String, Int>()
            headerTexts.forEachIndexed { i, h ->
                when {
                    h.contains("选课课号") -> colMap["number"] = i
                    h.contains("课程名称") -> colMap["name"] = i
                    h.contains("姓名") -> colMap["student"] = i
                    h.contains("考试时间") -> colMap["time"] = i
                    h.contains("考试地点") -> colMap["location"] = i
                    h.contains("座位号") -> colMap["seat"] = i
                    h.contains("考试形式") -> colMap["form"] = i
                }
            }

            val rows = table.select("tr")
            for (i in 1 until rows.size) {
                val cells = rows[i].select("td")
                if (cells.size < 4) continue

                fun cell(key: String): String {
                    val idx = colMap[key] ?: return ""
                    return if (idx < cells.size) htmlParser.cleanNbsp(cells[idx].text().trim()) else ""
                }

                val exam = MakeupExam(
                    courseNumber = cell("number"),
                    courseName = cell("name"),
                    studentName = cell("student"),
                    examTime = cell("time"),
                    examLocation = cell("location"),
                    seatNumber = cell("seat"),
                    examForm = cell("form")
                )
                if (exam.courseName.isNotBlank()) {
                    exams.add(exam)
                }
            }
            break
        }

        return QueryResult(true, "获取成功", exams)
    }

    data class QueryResult<T>(
        val success: Boolean,
        val message: String,
        val data: T? = null
    )
}
