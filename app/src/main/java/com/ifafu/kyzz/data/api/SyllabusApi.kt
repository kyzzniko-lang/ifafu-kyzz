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

            var accessUrl = "${host}/(${token})/xskbcx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
            var doc = htmlClient.getWithReferer(accessUrl, mainUrl)
            var html = doc.html()
            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                // 重登等待期间账号可能已切换：旧 name 混入新会话会把数据缓存串台
                if (user.account != number || userRepository.host != host) return null
                accessUrl = "${host}/(${user.token})/xskbcx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121603"
                doc = htmlClient.getWithReferer(
                    accessUrl,
                    "${host}/(${user.token})/xs_main.aspx?xh=${user.account}"
                )
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
                // 主流程的当前学期课表已经解析成功。假期探测（upcoming/previous
                // 学期的额外请求）只是优化项：探测请求超时/会话过期时不应把整次
                // getSyllabus 判为失败——那会让首页把已经拿到的课表也丢掉。
                try {
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
                        alignTermFirstDayToEstimated(upcoming)
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SyllabusApi", "假期课表探测失败，回退使用当前解析结果", e)
                }
            }

            if (usedInferred && syllabus.isEmpty()) {
                Log.i("SyllabusApi", "当前学期（${inferred.display()}）暂无课表数据，返回空课表")
            }

            if (usedInferred) {
                // 新学期直接命中（8月末开学周起不再经过假期提升路径）时，
                // termFirstDay 可能还停留在上一学期，先按校历惯例对齐，
                // 随后 saveTermFirstDayIfNeeded 再用服务器周次精确校正。
                alignTermFirstDayToEstimated(inferred)
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

            var accessUrl = "${host}/(${token})/xskbcx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121603"
            // 1. GET 获取 __VIEWSTATE
            var initialDoc = htmlClient.getWithReferer(accessUrl, mainUrl)
            var initialHtml = initialDoc.html()
            if (userApi.isSessionExpired(initialHtml)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                if (user.account != number || userRepository.host != host) return null
                accessUrl = "${host}/(${user.token})/xskbcx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121603"
                initialDoc = htmlClient.getWithReferer(
                    accessUrl,
                    "${host}/(${user.token})/xs_main.aspx?xh=${user.account}"
                )
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
                alignTermFirstDayToEstimated(inferred)
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
        // currentWeek 的解析正则较宽松,页面残留的"第18周教学检查"类横幅会被
        // 误读为当前周次,回推出的开学日整体偏移并永久污染周次基准。与按校历
        // 惯例估算的首日交叉验证:偏差超过两周时宁可放弃写入。
        TermResolver.estimatedFirstDay(TermResolver.inferCurrentTerm())?.let { estimated ->
            if (kotlin.math.abs(estimated.timeInMillis - cal.timeInMillis) > 14L * 24 * 60 * 60 * 1000) {
                Log.w("SyllabusApi",
                    "currentWeek=${syllabus.currentWeek} 回推的开学日($termFirstDay)偏离校历估算超过两周,放弃自动写入")
                return
            }
        }
        Log.d("SyllabusApi", "Calculated termFirstDay=$termFirstDay from currentWeek=${syllabus.currentWeek}")
        userRepository.termFirstDay = termFirstDay
    }

    /**
     * 把 termFirstDay 对齐到指定学期按校历惯例（9月1日/3月2日对齐周一）估算的首日。
     * 使用场景：学期刚切换（假期提升新课表、或 8 月末开学周起直接命中新学期）时，
     * termFirstDay 往往还停留在上一学期的开学日——周次会按旧学期计算（课表页显示
     * "第25周/课程已结束"），导出日历更是整学期错位。服务器对未开学的学期通常不标注
     * "第N教学周"，saveTermFirstDayIfNeeded 帮不上忙。仅在现有值明显不属于该学期
     * （误差超过 8 周，即还是旧学期日期）时才覆盖；之后 GitHub 配置或服务器周次
     * 标签仍可把它校正到真实日期。
     */
    private fun alignTermFirstDayToEstimated(term: TermResolver.Term) {
        if (userRepository.termFirstDayManual) return
        val estimated = TermResolver.estimatedFirstDay(term) ?: return
        val existing = userRepository.termFirstDay
        if (existing.isNotEmpty()) {
            try {
                val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(existing)
                if (parsed != null &&
                    kotlin.math.abs(parsed.time - estimated.timeInMillis) < 8L * 7 * 24 * 60 * 60 * 1000
                ) {
                    return
                }
            } catch (_: Exception) {
                // 现有值解析失败则直接覆盖
            }
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        userRepository.termFirstDay = sdf.format(estimated.time)
        Log.i("SyllabusApi", "termFirstDay 对齐到${term.display()}校历估算首日 ${sdf.format(estimated.time)}")
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
        val html = htmlClient.postStringWithReferer(accessUrl, formBody, accessUrl)
        if (userApi.isSessionExpired(html)) {
            throw AlertException("会话已过期，请重新登录", true)
        }
        htmlClient.throwIfAlert(html)
        return org.jsoup.Jsoup.parse(html)
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
