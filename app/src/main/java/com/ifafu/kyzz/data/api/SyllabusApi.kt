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
import org.jsoup.nodes.Document
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
            val yearResult = htmlParser.parseSearchOptions(doc, "id=\"xnd\"", "</select>")
            val termResult = htmlParser.parseSearchOptions(doc, "id=\"xqd\"", "</select>").excludeTerms("3")
            val serverYear = yearResult.options.getOrElse(yearResult.selectedIndex) { "" }
            val serverTerm = termResult.options.getOrElse(termResult.selectedIndex) { "" }

            val inferred = TermResolver.inferCurrentTerm()
            val picked = TermResolver.pickTerm(
                inferred.year, inferred.term,
                yearResult.options, termResult.options
            )
            val targetYear = picked?.year ?: serverYear
            val targetTerm = picked?.term ?: serverTerm
            val usedInferred = picked != null

            val syllabus = if (targetYear == serverYear && targetTerm == serverTerm) {
                Log.d("SyllabusApi", "Target matches server default ($serverYear-$serverTerm), using GET response directly")
                syllabusParser.parseSyllabus(doc, number)
            } else {
                Log.d(
                    "SyllabusApi",
                    "选择课表 xnd=$targetYear, xqd=$targetTerm (inferred=${inferred.year}-${inferred.term}, server=$serverYear-$serverTerm, usedInferred=$usedInferred)"
                )
                val selectedDoc = selectTerm(
                    accessUrl, doc, targetYear, targetTerm, serverYear, serverTerm
                )
                syllabusParser.parseSyllabus(selectedDoc, number)
            }

            val transition = TermResolver.breakTransition()
            if (transition != null) {
                val upcoming = transition.upcoming
                val upcomingAvailable = yearResult.options.contains(upcoming.year) &&
                    termResult.options.contains(upcoming.term)
                val upcomingSyllabus = when {
                    !upcomingAvailable -> null
                    targetYear == upcoming.year && targetTerm == upcoming.term -> syllabus
                    else -> getSyllabusWithTerm(
                        host, userRepository.getUser().token, number, name,
                        upcoming.year, upcoming.term
                    )
                }
                if (upcomingSyllabus?.hasPublishedContent() == true) {
                    Log.i("SyllabusApi", "检测到${upcoming.display()}课表已发布，提升为默认课表")
                    return upcomingSyllabus
                }

                // 新课表尚未发布时继续使用上一学期，避免寒暑假进入空白课表。
                val previous = transition.previous
                val previousSyllabus = when {
                    targetYear == previous.year && targetTerm == previous.term -> syllabus
                    serverYear == previous.year && serverTerm == previous.term ->
                        syllabusParser.parseSyllabus(doc, number)
                    yearResult.options.contains(previous.year) &&
                        termResult.options.contains(previous.term) ->
                        getSyllabusWithTerm(
                            host, userRepository.getUser().token, number, name,
                            previous.year, previous.term
                        )
                    else -> null
                }
                if (previousSyllabus != null) {
                    if (previous.year == inferred.year && previous.term == inferred.term) {
                        saveTermFirstDayIfNeeded(previousSyllabus)
                    }
                    return previousSyllabus
                }
            }

            if (usedInferred && syllabus.isEmpty()) {
                Log.i("SyllabusApi", "当前学期（${inferred.display()}）暂无课表数据，返回空课表")
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

            val yearResult = htmlParser.parseSearchOptions(initialDoc, "id=\"xnd\"", "</select>")
            val termResult = htmlParser.parseSearchOptions(initialDoc, "id=\"xqd\"", "</select>").excludeTerms("3")
            val serverYear = yearResult.options.getOrElse(yearResult.selectedIndex) { "" }
            val serverTerm = termResult.options.getOrElse(termResult.selectedIndex) { "" }

            val syllabus = if (year == serverYear && term == serverTerm) {
                Log.d("SyllabusApi", "Target matches server default ($serverYear-$serverTerm), using GET response directly")
                syllabusParser.parseSyllabus(initialDoc, number)
            } else {
                Log.d("SyllabusApi", "选择课表 xnd=$year, xqd=$term (server=$serverYear-$serverTerm)")
                val selectedDoc = selectTerm(
                    accessUrl, initialDoc, year, term, serverYear, serverTerm
                )
                syllabusParser.parseSyllabus(selectedDoc, number)
            }

            Log.d("SyllabusApi", "Parsed: courses=${syllabus.courses.size}")
            val inferred = TermResolver.inferCurrentTerm()
            if (year == inferred.year && term == inferred.term) {
                saveTermFirstDayIfNeeded(syllabus)
            }
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

    /**
     * 正方教务的学年下拉框是 AutoPostBack。跨学年选择时，第一次 POST 只更新学年，
     * 学期可能仍停留在旧值，因此必须读取新 VIEWSTATE 后再补一次学期 POST。
     */
    private suspend fun selectTerm(
        accessUrl: String,
        initialDoc: Document,
        targetYear: String,
        targetTerm: String,
        initialYear: String,
        initialTerm: String
    ): Document {
        var doc = initialDoc
        var selectedYear = initialYear
        var selectedTerm = initialTerm

        if (selectedYear != targetYear) {
            doc = postTermSelection(accessUrl, doc, "xnd", targetYear, targetTerm)
            selectedYear = selectedOption(doc, "xnd")
            selectedTerm = selectedOption(doc, "xqd")
        }

        if (selectedYear != targetYear || selectedTerm != targetTerm) {
            doc = postTermSelection(accessUrl, doc, "xqd", targetYear, targetTerm)
            selectedYear = selectedOption(doc, "xnd")
            selectedTerm = selectedOption(doc, "xqd")
        }

        if (selectedYear != targetYear || selectedTerm != targetTerm) {
            Log.w(
                "SyllabusApi",
                "课表选择未完全生效: target=$targetYear-$targetTerm actual=$selectedYear-$selectedTerm"
            )
        }
        return doc
    }

    private suspend fun postTermSelection(
        accessUrl: String,
        sourceDoc: Document,
        eventTarget: String,
        year: String,
        term: String
    ): Document {
        val state = htmlClient.parseViewState(sourceDoc.html())
        val formBody = htmlClient.buildFormBodyWithViewState(
            "__EVENTTARGET" to eventTarget,
            "__EVENTARGUMENT" to "",
            "xnd" to year,
            "xqd" to term,
            state = state
        )
        val result = htmlClient.post(accessUrl, formBody)
        val html = result.html()
        if (userApi.isSessionExpired(html)) {
            throw AlertException("会话已过期，请重新登录", true)
        }
        htmlClient.throwIfAlert(html)
        return result
    }

    private fun selectedOption(doc: Document, selectId: String): String {
        val options = htmlParser.parseSearchOptions(doc, "id=\"$selectId\"", "</select>")
        return options.options.getOrElse(options.selectedIndex) { "" }
    }

    private fun Syllabus.isEmpty(): Boolean {
        return courses.isEmpty() &&
            practiceCourses.isEmpty() &&
            internshipCourses.isEmpty() &&
            unscheduledCourses.isEmpty()
    }

    private fun Syllabus.hasPublishedContent(): Boolean {
        return courses.isNotEmpty() ||
            practiceCourses.isNotEmpty() ||
            internshipCourses.isNotEmpty() ||
            unscheduledCourses.isNotEmpty()
    }
}
