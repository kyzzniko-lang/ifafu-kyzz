package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.HtmlParser
import com.ifafu.kyzz.data.parser.SyllabusParser
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.TermResolver
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyllabusApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val syllabusParser: SyllabusParser,
    private val htmlParser: HtmlParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    suspend fun getSyllabus(host: String, token: String, number: String, name: String): Syllabus? {
        return try {
            val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"
            htmlClient.setReferer(mainUrl)

            var accessUrl = "${host}/(${token})/xskbcx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
            var doc = htmlClient.get(accessUrl)
            var html = doc.html()
            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                accessUrl = "${host}/(${user.token})/xskbcx.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
                doc = htmlClient.get(accessUrl)
                html = doc.html()
                if (userApi.isSessionExpired(html)) {
                    try {
                        htmlClient.get("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                        htmlClient.get("${host}/(${user.token})/xsleft.aspx?xh=${user.account}")
                    } catch (e: AlertException) {
                        throw e
                    } catch (e: Exception) {
                        // ignore
                    }
                    return null
                }
            }

            htmlClient.throwIfAlert(html)
            val yearResult = htmlParser.parseSearchOptions(doc, "id=\"xnd\"", "学年第")
            val termResult = htmlParser.parseSearchOptions(doc, "学年第", "校区")
            val serverYear = yearResult.options.getOrElse(yearResult.selectedIndex) { "" }
            val serverTerm = termResult.options.getOrElse(termResult.selectedIndex) { "" }

            // 优先使用日期推断的学期；服务器选项里不存在则回退服务器默认值
            val inferred = TermResolver.inferCurrentTerm()
            val picked = TermResolver.pickTerm(
                inferred.year, inferred.term,
                yearResult.options, termResult.options
            )
            val targetYear = picked?.year ?: serverYear
            val targetTerm = picked?.term ?: serverTerm
            val usedInferred = picked != null

            Log.d(
                "SyllabusApi",
                "POST xnd=$targetYear, xqd=$targetTerm (inferred=${inferred.year}-${inferred.term}, server=$serverYear-$serverTerm, usedInferred=$usedInferred)"
            )
            val state = htmlClient.parseViewState(html)
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTTARGET" to "xqd",
                "__EVENTARGUMENT" to "",
                "xnd" to targetYear,
                "xqd" to targetTerm,
                state = state
            )

            val postDoc = htmlClient.post(accessUrl, formBody)
            htmlClient.throwIfAlert(postDoc.html())
            val syllabus = syllabusParser.parseSyllabus(postDoc, number)

            // 健壮性：若使用了推断学期且没有任何课程/实践/实习/未排课，提示用户
            if (usedInferred && syllabus.isEmpty()) {
                throw AlertException("当前学期（${inferred.display()}）暂无课表数据，请确认学校是否已发布或稍后重试")
            }

            saveTermFirstDayIfNeeded(syllabus)
            syllabus
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) { throw e }
        catch (e: Exception) {
            Log.e("SyllabusApi", "Failed to fetch syllabus", e)
            null
        }
    }

    suspend fun getSyllabusWithTerm(
        host: String, token: String, number: String, name: String,
        year: String, term: String
    ): Syllabus? {
        return try {
            val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"
            htmlClient.setReferer(mainUrl)

            var accessUrl = "${host}/(${token})/xskbcx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
            // 1. GET 获取 __VIEWSTATE
            var initialDoc = htmlClient.get(accessUrl)
            var initialHtml = initialDoc.html()
            if (userApi.isSessionExpired(initialHtml)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                accessUrl = "${host}/(${user.token})/xskbcx.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
                initialDoc = htmlClient.get(accessUrl)
                initialHtml = initialDoc.html()
                if (userApi.isSessionExpired(initialHtml)) {
                    try {
                        htmlClient.get("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                        htmlClient.get("${host}/(${user.token})/xsleft.aspx?xh=${user.account}")
                    } catch (e: AlertException) {
                        throw e
                    } catch (e: Exception) {
                        // ignore
                    }
                    return null
                }
            }

            htmlClient.throwIfAlert(initialHtml)

            // 2. POST 切换学期（__EVENTTARGET=xqd），与浏览器抓包完全一致
            Log.d("SyllabusApi", "POST xnd=$year, xqd=$term")
            val state = htmlClient.parseViewState(initialHtml)
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTTARGET" to "xqd",
                "__EVENTARGUMENT" to "",
                "xnd" to year,
                "xqd" to term,
                state = state
            )

            val doc = htmlClient.post(accessUrl, formBody)
            val html = doc.html()
            Log.d("SyllabusApi", "Response length=${html.length}")
            val tableIdx = html.indexOf("kbtable")
            Log.d("SyllabusApi", "kbtable index=$tableIdx")
            if (userApi.isSessionExpired(html)) {
                Log.e("SyllabusApi", "Session expired after POST!")
                return null
            }
            htmlClient.throwIfAlert(html)
            val syllabus = syllabusParser.parseSyllabus(doc, number)
            Log.d("SyllabusApi", "Parsed: courses=${syllabus.courses.size}")
            saveTermFirstDayIfNeeded(syllabus)
            syllabus
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) { throw e }
        catch (e: Exception) {
            Log.e("SyllabusApi", "Failed to fetch syllabus with term", e)
            null
        }
    }

    private fun saveTermFirstDayIfNeeded(syllabus: Syllabus) {
        if (syllabus.currentWeek <= 0) return
        if (userRepository.termFirstDayManual) return

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        val daysToSubtract = (syllabus.currentWeek - 1) * 7 + daysFromMonday
        cal.add(Calendar.DAY_OF_YEAR, -daysToSubtract)

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val termFirstDay = sdf.format(cal.time)
        Log.d("SyllabusApi", "Calculated termFirstDay=$termFirstDay from currentWeek=${syllabus.currentWeek}")
        userRepository.termFirstDay = termFirstDay
    }

    private fun Syllabus.isEmpty(): Boolean {
        return courses.isEmpty() &&
            practiceCourses.isEmpty() &&
            internshipCourses.isEmpty() &&
            unscheduledCourses.isEmpty()
    }
}
