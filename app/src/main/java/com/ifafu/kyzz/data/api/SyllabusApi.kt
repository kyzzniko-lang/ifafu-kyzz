package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.HtmlParser
import com.ifafu.kyzz.data.parser.SyllabusParser
import com.ifafu.kyzz.data.repository.UserRepository
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
            val accessUrl = "${host}/(${token})/xskbcx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
            val doc = htmlClient.get(accessUrl)
            val html = doc.html()
            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/xskbcx.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) return null
                val syllabus = syllabusParser.parseSyllabus(retryDoc, user.account)
                saveTermFirstDayIfNeeded(syllabus)
                return syllabus
            }
            val syllabus = syllabusParser.parseSyllabus(doc, number)
            saveTermFirstDayIfNeeded(syllabus)
            syllabus
        } catch (e: CancellationException) { throw e }
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
            }

            // 2. POST 切换学年+学期（__EVENTTARGET=xnd），与浏览器抓包完全一致
            Log.d("SyllabusApi", "POST xnd=$year, xqd=$term")
            val state = htmlClient.parseViewState(initialHtml)
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTTARGET" to "xnd",
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
            val syllabus = syllabusParser.parseSyllabus(doc, number)
            Log.d("SyllabusApi", "Parsed: courses=${syllabus.courses.size}")
            saveTermFirstDayIfNeeded(syllabus)
            syllabus
        } catch (e: CancellationException) { throw e }
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
}
