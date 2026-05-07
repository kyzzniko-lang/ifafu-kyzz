package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.SyllabusParser
import com.ifafu.kyzz.data.repository.UserRepository
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
                val retryUrl = "${host}/(${user.token})/xskbcx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121603"
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
        } catch (e: Exception) {
            Log.e("SyllabusApi", "Failed to fetch syllabus", e)
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
