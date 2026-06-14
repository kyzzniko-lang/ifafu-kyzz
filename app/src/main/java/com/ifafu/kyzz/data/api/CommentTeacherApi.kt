package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentTeacherApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    /** 评分等级，值为 GBK 编码后的中文 */
    enum class ScoreLevel(val label: String, val gbkValue: String) {
        EXCELLENT("优秀", "优秀"),
        GOOD("良好", "良好"),
        MEDIUM("中等", "中等"),
        PASS("合格", "合格"),
        FAIL("不及格", "不及格");

        companion object {
            fun fromIndex(index: Int): ScoreLevel = entries[index.coerceIn(0, 4)]
        }
    }

    /** 评价模式 */
    sealed class EvalMode {
        /** 全自动：随机 优秀/良好/中等 */
        object FullAuto : EvalMode()
        /** 半自动：在 [min] ~ [max] 范围内随机 */
        data class SemiAuto(val min: ScoreLevel, val max: ScoreLevel) : EvalMode()
        /** 手动：由用户逐项选择 */
        object Manual : EvalMode()
    }

    data class EvalItem(val dataGridName: String = "DataGrid1", val ctlIndex: Int, val description: String) {
        /** 唯一键：用于在评分 map 中区分不同表的同名 ctlIndex（如 DataGrid1:2 和 dgPjc:2） */
        val fieldKey: String get() = "$dataGridName:$ctlIndex"
    }

    data class EvalProgress(val current: Int, val total: Int, val courseName: String)

    // ── 获取课程列表（供手动模式使用） ──

    data class CourseLink(val path: String, val label: String)

    data class CourseItemsResult(val items: List<EvalItem>, val teacherName: String = "")

    suspend fun fetchCourseLinks(
        host: String, token: String, number: String, name: String
    ): Response {
        try {
            val result = fetchMainPage(host, token, number, name)
            if (result.error != null) return result.error
            val html = result.html!!
            val links = parseCourseLinks(html)
            if (links.isNotEmpty()) {
                val data = links.joinToString(",") { "[\"${it.path}\",\"${it.label}\"]" }
                return Response(true, 0, "[$data]")
            }
            // 没有 open() 时，检查是否是直接进入评价页（含 JS1 评价项）
            if (html.contains("JS1") || html.contains("DataGrid1:_ctl")) {
                android.util.Log.d("CommentApi", "Direct eval page, parsing all courses from pjkc dropdown")
                val pjkcDoc = Jsoup.parse(html)
                val pjkcSelect = pjkcDoc.select("select[name=pjkc]").firstOrNull()
                val basePath = "xsjxpj.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121401"
                val teacherName = parseTeacherName(html)
                if (pjkcSelect != null) {
                    val options = mutableListOf<String>()
                    for (option in pjkcSelect.select("option")) {
                        val valStr = option.attr("value")
                        val labelStr = option.text().trim()
                        val isSelected = option.hasAttr("selected")
                        val displayLabel = labelStr
                        val path = if (isSelected) basePath else "$basePath&_pjkc=$valStr"
                        options.add("""["$path","$displayLabel"]""")
                    }
                    if (options.isNotEmpty()) {
                        val data = options.joinToString(",")
                        return Response(true, 0, "[$data]")
                    }
                }
                // fallback: 只有一个课程
                val courseName = pjkcSelect?.select("option[selected]")?.first()?.text()?.trim() ?: "课程评价"
                return Response(true, 0, "[[\"$basePath\",\"$courseName\"]]")
            }
            return Response(false, -3, "没有需要评价的课程")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(false, -1, e.message ?: "网络异常")
        }
    }

    /** 获取单个课程的评价项列表及教师名 */
    suspend fun fetchCourseItems(
        host: String, token: String, coursePath: String, number: String, name: String
    ): CourseItemsResult {
        val mainUrl = buildMainUrl(host, token, number, name)
        val html: String
        if (coursePath.contains("_pjkc=")) {
            val accessUrl = "${host}/(${token})/${coursePath.substringBefore("&_pjkc=")}"
            val pjkcValue = coursePath.substringAfter("_pjkc=")
            android.util.Log.d("CommentApi", "Switching course: pjkc='$pjkcValue' accessUrl=$accessUrl")
            val pageHtml = htmlClient.getStringWithReferer(accessUrl, mainUrl)
            if (pageHtml.isBlank()) return CourseItemsResult(emptyList())
            val vs = htmlClient.parseViewState(pageHtml)
            android.util.Log.d("CommentApi", "Switch: VS len=${vs.viewState.length}")
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTTARGET" to "pjkc",
                "__EVENTARGUMENT" to "",
                "pjkc" to pjkcValue,
                state = vs
            )
            html = htmlClient.postStringWithReferer(accessUrl, formBody, accessUrl)
            android.util.Log.d("CommentApi", "Switch: response len=${html.length}")
        } else {
            val url = "${host}/(${token})/${coursePath}"
            html = htmlClient.getStringWithReferer(url, mainUrl)
        }
        if (html.isBlank()) return CourseItemsResult(emptyList())
        val items = parseEvalItems(html)
        val teacherName = parseTeacherName(html)
        android.util.Log.d("CommentApi", "fetchCourseItems teacher='$teacherName' items=${items.size} pjkc=${coursePath.contains("_pjkc=")}")
        if (items.isNotEmpty()) {
            val indices = items.joinToString { "${it.dataGridName}:ctl${it.ctlIndex}" }
            android.util.Log.d("CommentApi", "Parsed items: $indices")
        }
        // 调试：检查 HTML 中是否有评教材相关内容
        val hasPingJiaoCai = html.contains("评教材") || html.contains("教材")
        if (hasPingJiaoCai) {
            val keyword = if (html.contains("评教材")) "评教材" else "教材"
            val idx = html.indexOf(keyword)
            val start = maxOf(0, idx - 100)
            val end = minOf(html.length, idx + 500)
            val context = html.substring(start, end)
            android.util.Log.d("CommentApi", "教材 context: $context")
            // 检查附近是否有 DataGrid2、ctl94、JS1 等
            val nearby = html.substring(maxOf(0, idx - 20), minOf(html.length, idx + 200))
            android.util.Log.d("CommentApi", "DataGrid2 near 教材? ${nearby.contains("DataGrid2")} ctl94? ${nearby.contains("ctl94")} JS1? ${nearby.contains("JS1")}")
        }
        return CourseItemsResult(items, teacherName)
    }

    /** 手动模式全部评完后，提交总表 */
    suspend fun submitFinalTable(
        host: String, token: String, number: String, name: String
    ): Response {
        try {
            val mainUrl = buildMainUrl(host, token, number, name)
            // 使用 getStringRawWithReferer 避免 "所有评价已完成" alert 拦截
            val html = htmlClient.getStringRawWithReferer(mainUrl, mainUrl)
            if (html.isBlank()) return Response(false, -1, "获取主页失败")
            val vs = htmlClient.parseViewState(html)
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTARGUMENT" to "",
                "__EVENTTARGET" to "",
                "Button2" to "提  交",
                state = vs
            )
            val postHtml = htmlClient.postStringWithReferer(mainUrl, formBody, mainUrl)
            val m = Regex("alert\\('(.*?)'\\)").find(postHtml)
            return if (m != null && m.groupValues[1].contains("完成评价")) {
                Response(true, 0, "评教完成")
            } else {
                val alertMsg = htmlClient.checkAlert(postHtml)
                Response(false, -1, alertMsg?.message ?: (m?.groupValues?.get(1) ?: "提交失败"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(false, -1, e.message ?: "网络异常")
        }
    }

    /** 手动模式：提交用户选择的评分 */
    suspend fun submitManualCourse(
        host: String, token: String, coursePath: String, number: String, name: String,
        selections: Map<String, ScoreLevel>, commentText: String = ""
    ): Response {
        try {
            val mainUrl = buildMainUrl(host, token, number, name)
            val html: String
            val postUrl: String
            if (coursePath.contains("_pjkc=")) {
                val baseUrl = "${host}/(${token})/${coursePath.substringBefore("&_pjkc=")}"
                val pjkcValue = coursePath.substringAfter("_pjkc=").substringBefore("&")
                val pageHtml = htmlClient.getStringWithReferer(baseUrl, mainUrl)
                if (pageHtml.isBlank()) return Response(false, -1, "获取评教页面失败")
                val vs = htmlClient.parseViewState(pageHtml)
                val formBody = htmlClient.buildFormBodyWithViewState(
                    "__EVENTTARGET" to "pjkc",
                    "__EVENTARGUMENT" to "",
                    "pjkc" to pjkcValue,
                    state = vs
                )
                html = htmlClient.postStringWithReferer(baseUrl, formBody, baseUrl)
                postUrl = baseUrl
            } else {
                postUrl = "${host}/(${token})/${coursePath}"
                html = htmlClient.getStringWithReferer(postUrl, mainUrl)
            }
            if (html.isBlank()) return Response(false, -1, "获取评教页面失败")

            val vs = htmlClient.parseViewState(html)
            val fieldNames = parseEvalFieldNames(html)
            val pjkcMatch = Regex("""<option[^>]*?selected[^>]*?value="([^"]*)""").find(html)
            val pjkcValue = pjkcMatch?.groupValues?.get(1) ?: ""
            android.util.Log.d("CommentApi", "Save: VS len=${vs.viewState.length}, pjkc='$pjkcValue', fields=${fieldNames.size}, commentLen=${commentText.length}")

            // 构建 POST body 字符串，字段名和值都 URL 编码以匹配浏览器
            fun enc(s: String) = java.net.URLEncoder.encode(s, "GBK")
            val body = buildString {
                append("__EVENTTARGET=")
                append("&__EVENTARGUMENT=")
                append("&__VIEWSTATE="); append(enc(vs.viewState))
                if (pjkcValue.isNotEmpty()) { append("&pjkc="); append(enc(pjkcValue)) }
                for (fieldName in fieldNames) {
                    val tableName = fieldName.substringBefore(":_ctl")
                    val ctlNum = Regex("""ctl(\d+)""").find(fieldName)?.groupValues?.get(1) ?: ""
                    val fieldKey = "$tableName:$ctlNum"
                    val level = selections[fieldKey] ?: ScoreLevel.GOOD
                    append("&"); append(enc(fieldName)); append("="); append(enc(level.gbkValue))
                    val txtSuffix = if (fieldName.endsWith(":JS1")) "txtjs1" else if (fieldName.endsWith(":jc1")) "txtjc1" else null
                    if (txtSuffix != null) {
                        val txtField = fieldName.substringBeforeLast(":") + ":" + txtSuffix
                        append("&"); append(enc(txtField)); append("=")
                    }
                }
                append("&pjxx="); append(enc(commentText))
                append("&txt1=")
                append("&TextBox1=0")
                append("&Button1="); append(enc("保" + "  " + "存"))
            }
            // 用 HtmlClient 直接发 raw POST 字节（携带正确 Cookie）
            val rawBytes = body.toByteArray(charset("GBK"))
            android.util.Log.d("CommentApi", "Save POST body: rawLen=${rawBytes.size}, first80=...${body.take(80).replace('\n',' ')}")
            val postHtml = htmlClient.postRawBytesWithReferer(postUrl, rawBytes, postUrl)
            android.util.Log.d("CommentApi", "Save POST response: length=${postHtml.length}")
            // 教务系统保存成功后返回相同页面,无 alert。页面正常返回即视为保存成功
            val alertMsg = htmlClient.checkAlert(postHtml)
            val isPageOk = postHtml.contains("现代教学管理信息系统") || postHtml.contains("DataGrid1") || postHtml.contains("DataGrid2")
            val isKeyword = postHtml.contains("保存成功") || postHtml.contains("提交成功") || postHtml.contains("评价成功")
            val isBlocked = alertMsg != null && !alertMsg.message.contains("禁止")
            android.util.Log.d("CommentApi", "Save result: keyword=$isKeyword pageOk=$isPageOk blocked=$isBlocked alert=${alertMsg?.message}")
            return if (isPageOk && !isBlocked) {
                android.util.Log.d("CommentApi", "Save appears successful")
                Response(true, 0, "保存成功")
            } else {
                android.util.Log.w("CommentApi", "Save failed: blocked=$isBlocked pageOk=$isPageOk")
                Response(false, -1, alertMsg?.message ?: "保存失败")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(false, -1, e.message ?: "网络异常")
        }
    }

    // ── 自动/半自动批量评教 ──

    suspend fun commentAllTeachers(
        host: String, token: String, number: String, name: String,
        mode: EvalMode = EvalMode.FullAuto,
        commentText: String = "",
        onProgress: ((EvalProgress) -> Unit)? = null
    ): Response {
        try {
            val result = fetchMainPage(host, token, number, name)
            if (result.error != null) return result.error
            return doComment(host, token, number, name, result.html!!, mode, commentText, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(false, -1, e.message ?: "网络异常")
        }
    }

    // ── 内部方法 ──

    private data class FetchResult(val html: String?, val error: Response?)

    /**
     * Simulate the full ZF browser navigation flow to bypass server-side anti-hotlinking:
     * 1. GET xs_main.aspx — establishes the frameset session context
     * 2. GET xsleft.aspx  — registers the left-menu navigation in the server session
     * 3. GET xsjxpj.aspx  — now the server knows we navigated here legitimately
     */
    private suspend fun fetchMainPage(
        host: String, token: String, number: String, name: String
    ): FetchResult {
        return fetchMainPageInternal(host, token, number, name, allowRelogin = true)
    }

    private suspend fun fetchMainPageInternal(
        host: String, token: String, number: String, name: String,
        allowRelogin: Boolean
    ): FetchResult {
        // 与其他 API（ScoreApi、ExamApi、SyllabusApi 等）保持一致的一步到位模式：
        // 设置 Referer = xs_main.aspx 后直接访问目标页面
        val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"
        val accessUrl = buildMainUrl(host, token, number, name)
        android.util.Log.d("CommentApi", "Access URL: $accessUrl")

        var html: String
        try {
            html = htmlClient.getStringRawWithReferer(accessUrl, mainUrl)
            android.util.Log.d("CommentApi", "Response: length=${html.length}, first300=${html.take(300)}")
        } catch (e: Exception) {
            android.util.Log.e("CommentApi", "Request failed: ${e.message}")
            return FetchResult(null, Response(false, -1, e.message ?: "网络异常"))
        }

        // Check for alerts (function-local alerts like Keykz tab-block are excluded by checkAlert globally)
        val alertResp = htmlClient.checkAlert(html)
        if (alertResp != null) {
            android.util.Log.w("CommentApi", "Alert detected: ${alertResp.message}")
            if (allowRelogin && alertResp.message.contains("过期")) {
                val reloginResp = reloginHelper.relogin()
                if (!reloginResp.success) return FetchResult(null, Response(false, -1, reloginResp.message))
                val user = userRepository.getUser()
                return fetchMainPageInternal(host, user.token, user.account, user.name, allowRelogin = false)
            }
            return FetchResult(null, alertResp)
        }

        // Check for error pages
        val errorResp = htmlClient.checkErrorPage(html)
        if (errorResp != null) {
            android.util.Log.w("CommentApi", "Error page: ${errorResp.message}")
            return FetchResult(null, errorResp)
        }

        if (userApi.isSessionExpired(html)) {
            android.util.Log.w("CommentApi", "Session expired")
            if (!allowRelogin) return FetchResult(null, Response(false, -1, "会话已过期，请重新登录"))
            val reloginResp = reloginHelper.relogin()
            if (!reloginResp.success) return FetchResult(null, Response(false, -1, reloginResp.message))
            val user = userRepository.getUser()
            return fetchMainPageInternal(host, user.token, user.account, user.name, allowRelogin = false)
        }

        return FetchResult(html, null)
    }

    private fun buildMainUrl(host: String, token: String, number: String, name: String): String {
        return "${host}/(${token})/xsjxpj.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121401"
    }

    private suspend fun doComment(
        host: String, token: String, number: String, name: String,
        mainHtml: String, mode: EvalMode, commentText: String, onProgress: ((EvalProgress) -> Unit)?
    ): Response {
        val courseLinks = parseCourseLinks(mainHtml)
        if (courseLinks.isEmpty()) return fallbackComment(host, token, number, name, mainHtml)

        var successCount = 0
        var failCount = 0
        var lastErrMsg = ""
        val mainUrl = buildMainUrl(host, token, number, name)

        for ((index, link) in courseLinks.withIndex()) {
            onProgress?.invoke(EvalProgress(index + 1, courseLinks.size, link.label))
            delay(500)

            val result = evalCourse(host, token, link, mode, commentText, mainUrl)
            if (result.success) successCount++ else { failCount++; lastErrMsg = result.message }
        }

        // 提交总表
        val freshHtml = htmlClient.getStringRawWithReferer(mainUrl, mainUrl)
        if (freshHtml.isNotBlank()) {
            val vs = htmlClient.parseViewState(freshHtml)
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTARGUMENT" to "",
                "__EVENTTARGET" to "",
                "Button2" to "提  交",
                state = vs
            )
            val postHtml = htmlClient.postStringWithReferer(mainUrl, formBody, mainUrl)
            val m = Regex("alert\\('(.*?)'\\)").find(postHtml)
            if (m != null && m.groupValues[1].contains("完成评价")) {
                return Response(true, 0, "评教成功，共评价 ${successCount} 门课程")
            }
        }

        return if (successCount > 0) {
            Response(true, 0, "已评价 ${successCount} 门课程" +
                    if (failCount > 0) "，${failCount} 门失败：$lastErrMsg" else "")
        } else {
            Response(false, -3, lastErrMsg.ifEmpty { "评教失败" })
        }
    }

    private suspend fun fallbackComment(
        host: String, token: String, number: String, name: String, html: String
    ): Response {
        try {
            val mainUrl = buildMainUrl(host, token, number, name)
            val vs = htmlClient.parseViewState(html)
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTARGUMENT" to "",
                "__EVENTTARGET" to "",
                "Button2" to "提  交",
                state = vs
            )
            val postHtml = htmlClient.postStringWithReferer(mainUrl, formBody, mainUrl)
            val m = Regex("alert\\('(.*?)'\\)").find(postHtml)
            return if (m != null && m.groupValues[1].contains("完成评价")) {
                Response(true, 0, "评教成功")
            } else {
                Response(false, -3, m?.groupValues?.get(1) ?: "无需评教或评教已完成")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(false, -1, e.message ?: "网络异常")
        }
    }

    private fun parseCourseLinks(html: String): List<CourseLink> {
        val results = mutableListOf<CourseLink>()
        val regex = Regex("""open\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]*?)['"]\)""")
        regex.findAll(html).forEach { match ->
            results.add(CourseLink(match.groupValues[1], match.groupValues[2]))
        }
        if (results.isEmpty()) {
            val fallback = Regex("""open\(['"]([^'"]+)['"]\s*,""")
            fallback.findAll(html).forEach { match ->
                results.add(CourseLink(match.groupValues[1], ""))
            }
        }
        return results
    }

    /** 解析评价项名称（用于手动模式 UI） */
    private fun parseEvalItems(html: String): List<EvalItem> {
        val items = mutableListOf<EvalItem>()
        val doc = Jsoup.parse(html)
        // 从所有评教相关的表格中提取评价项（DataGrid1 是评教师，dgPjc 是评教材）
        val tables = doc.select("table[id*=DataGrid], table[id*=dgPjc]")
        android.util.Log.d("CommentApi", "parseEvalItems: found ${tables.size} eval tables")
        for (t in tables) {
            android.util.Log.d("CommentApi", "  table id='${t.attr("id")}' rows=${t.select("tr").size}")
        }
        val rows = doc.select("table[id*=DataGrid] tr, table[id*=dgPjc] tr")
        for (row in rows) {
            val cells = row.select("td")
            if (cells.size < 3) continue
            val htmlStr = row.html()
            val table = row.closest("table[id*=DataGrid], table[id*=dgPjc]")
            val dataGridName = table?.attr("id")?.trim() ?: "DataGrid1"
            if (!htmlStr.contains("JS1") && !htmlStr.contains(":jc1")) {
                // 记录没有 JS1/jc1 的行
                val firstCell = cells.first()?.text()?.trim()?.take(20) ?: ""
                if (firstCell.isNotEmpty()) android.util.Log.d("CommentApi", "  row [no JS1/jc1] in $dataGridName: '$firstCell' cells=${cells.size}")
                continue
            }
            val ctlMatch = Regex("""ctl(\d+)""").find(htmlStr) ?: continue
            val ctlIndex = ctlMatch.groupValues[1].toInt()
            // 评价内容在倒数第二列
            val desc = cells.getOrNull(cells.size - 2)?.text()?.trim() ?: ""
            android.util.Log.d("CommentApi", "  row found: $dataGridName ctl=$ctlIndex desc='${desc.take(30)}' cells=${cells.size}")
            if (desc.isNotEmpty() && desc != "评价内容") {
                items.add(EvalItem(dataGridName, ctlIndex, desc))
            }
        }
        // 兜底：如果解析失败，返回 17 个默认项
        if (items.isEmpty()) {
            for (i in 2..18) {
                items.add(EvalItem("DataGrid1", i, "评价项 $i"))
            }
        }
        return items
    }

    /** 解析所有评价控件的完整字段名（如 DataGrid1:_ctl2:JS1, dgPjc:_ctl2:jc1） */
    private fun parseEvalFieldNames(html: String): List<String> {
        val pattern = Regex("""([\w]+)[_:]+ctl(\d+)[_:]+(JS1|jc1)""")
        return pattern.findAll(html).map { it.groupValues[0] }.distinct().sorted().toList()
    }

    /** 解析所有 ctl 索引及 DataGrid 名（向后兼容，仅用于旧代码路径） */
    private fun parseEvalCtlIndices(html: String): List<Pair<String, Int>> {
        val pattern = Regex("""(DataGrid\d+)[_:]+ctl(\d+)[_:]+JS1""")
        return pattern.findAll(html).map {
            it.groupValues[1] to it.groupValues[2].toInt()
        }.distinct().sortedBy { it.second }.toList()
    }

    /** 从评教页面 HTML 中提取教师姓名 */
    private fun parseTeacherName(html: String): String {
        // 方法1：在 DataGrid 中找表头行，列名包含"教师"的后面跟着的姓名
        val doc = Jsoup.parse(html)
        val tables = doc.select("table#DataGrid1, table[id*=DataGrid]")
        android.util.Log.d("CommentApi", "parseTeacherName: found ${tables.size} DataGrid tables")
        for (table in tables) {
            val firstRow = table.select("tr").firstOrNull()
            if (firstRow != null) {
                val ths = firstRow.select("th, td")
                android.util.Log.d("CommentApi", "DataGrid header row: ${ths.joinToString(" | ") { it.text().trim() }}")
                // 找包含"评教师"或"教师"的列
                for (i in ths.indices) {
                    val text = ths[i].text().trim()
                    if (text.contains("评教师") || text.contains("教师")) {
                        // 检查同列中是否有姓名
                        val name = text.replace(Regex("""[评教师\s]"""), "").trim()
                        if (name.length in 2..4) return name
                        // 看下一列
                        if (i + 1 < ths.size) {
                            val nextText = ths[i + 1].text().trim()
                            if (nextText.length in 2..4 && !nextText.contains("评价") && !nextText.contains("内容")) {
                                return nextText
                            }
                        }
                    }
                }
                // 方法1b: 最后几列可能是教师名
                for (i in ths.size - 1 downTo maxOf(0, ths.size - 3)) {
                    val t = ths[i].text().trim()
                    if (t.length in 2..4 && t.matches(Regex("""[一-龥]{2,4}"""))) {
                        android.util.Log.d("CommentApi", "Teacher candidate at col $i: '$t'")
                        return t
                    }
                }
            }
        }
        // 方法2：在 HTML 原始文本中搜索 "评教师"
        val idx = html.indexOf("评教师")
        if (idx >= 0) {
            val around = html.substring(maxOf(0, idx - 30), minOf(html.length, idx + 200))
            android.util.Log.d("CommentApi", "评教师 raw context: $around")
        }
        return ""
    }

    /** 自动/半自动评单个课程 */
    private suspend fun evalCourse(host: String, token: String, link: CourseLink, mode: EvalMode, commentText: String, mainUrl: String): Response {
        try {
            val url = "${host}/(${token})/${link.path}"
            val html = htmlClient.getStringWithReferer(url, mainUrl)
            if (html.isBlank()) return Response(false, -1, "获取评教页面失败")

            val vs = htmlClient.parseViewState(html)
            val random = Random()
            val fieldNames = parseEvalFieldNames(html)
            val formBuilder = okhttp3.FormBody.Builder(charset("GBK"))

            if (fieldNames.isNotEmpty()) {
                for (fieldName in fieldNames) {
                    val level = pickScoreLevel(mode, random)
                    formBuilder.add(fieldName, level.gbkValue)
                    // 对应的文本域
                    val txtSuffix = if (fieldName.endsWith(":JS1")) "txtjs1" else if (fieldName.endsWith(":jc1")) "txtjc1" else null
                    if (txtSuffix != null) {
                        val txtField = fieldName.substringBeforeLast(":") + ":" + txtSuffix
                        formBuilder.add(txtField, "")
                    }
                }
            } else {
                // 兼容旧格式
                val radioPattern = Regex("""table id="Datagrid1__(.*?)_rb"""")
                radioPattern.findAll(html).forEach { match ->
                    val value = if (random.nextInt(100) > 10) "94" else "82"
                    formBuilder.add("Datagrid1\$${match.groupValues[1]}\$_rb", value)
                }
            }

            formBuilder.add("__EVENTTARGET", "")
            formBuilder.add("__EVENTARGUMENT", "")
            formBuilder.add("__VIEWSTATE", vs.viewState)
            formBuilder.add("__VIEWSTATEGENERATOR", vs.viewStateGenerator)
            // 当前评价的课程 ID
            val pjkcMatch = Regex("""<option[^>]*?selected[^>]*?value="([^"]*)""").find(html)
            if (pjkcMatch != null) formBuilder.add("pjkc", pjkcMatch.groupValues[1])
            formBuilder.add("pjxx", commentText)
            formBuilder.add("Button1", "保  存")

            val postHtml = htmlClient.postStringWithReferer(url, formBuilder.build(), url)

            // postStringWithReferer already checks AlertException internally,
            // but we also need the success keywords check
            return if (postHtml.contains("保存成功") || postHtml.contains("提交成功") || postHtml.contains("评价成功")) {
                Response(true, 0, link.label.ifEmpty { "评教成功" })
            } else {
                val alertMsg = htmlClient.checkAlert(postHtml)
                Response(false, -1, alertMsg?.message ?: "提交失败")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Response(false, -1, e.message ?: "网络异常")
        }
    }

    private fun pickScoreLevel(mode: EvalMode, random: Random): ScoreLevel {
        return when (mode) {
            is EvalMode.FullAuto -> {
                // 随机 优秀/良好/中等
                val idx = random.nextInt(3) // 0,1,2 → 优秀,良好,中等
                ScoreLevel.fromIndex(idx)
            }
            is EvalMode.SemiAuto -> {
                val minIdx = mode.min.ordinal
                val maxIdx = mode.max.ordinal
                val range = maxIdx - minIdx + 1
                ScoreLevel.fromIndex(minIdx + random.nextInt(range))
            }
            is EvalMode.Manual -> ScoreLevel.GOOD // 不会走到这里
        }
    }
}
