package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ExamParser
import com.ifafu.kyzz.data.parser.HtmlParser
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.TermResolver
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val examParser: ExamParser,
    private val htmlParser: HtmlParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    companion object {
        private const val TAG = "ExamApi"
    }

    suspend fun getExamTable(host: String, token: String, number: String, name: String): ExamTable? {
        return try {
            fetchExamTable(host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) {
            // 会话失效时正方可能以页面级 alert 拦截（"禁止使用此功能"等）而非
            // 登录页，网络层直接抛异常、isSessionExpired 预检走不到：重登一次
            // 重取（守卫同 StudentQueryApi），仍被拒才抛回原文。
            reloginHelper.reloginAndRetry(e, number, host) { user ->
                fetchExamTable(host, user.token, user.account, user.name)
            }
        }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch exam table", e)
            null
        }
    }

    private suspend fun fetchExamTable(host: String, token: String, number: String, name: String): ExamTable? {
        val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"

        val accessUrl = "${host}/(${token})/xskscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121604"
        val doc = htmlClient.getWithReferer(accessUrl, mainUrl)
        val html = doc.html()

        if (userApi.isSessionExpired(html)) {
            Log.d(TAG, "Session expired, attempting relogin...")
            val response = reloginHelper.relogin()
            if (!response.success) {
                Log.w(TAG, "Relogin failed: ${response.message}")
                return null
            }
            val user = userRepository.getUser()
            // 等待重登期间用户可能已切换账号：继续用旧 name 发请求会把 A 的
            // 查询参数混进 B 的会话，返回数据还会被缓存到 B 的 key 下（串台）。
            if (user.account != number || userRepository.host != host) {
                Log.w(TAG, "Relogin retry cancelled: active account changed (${user.account} != $number)")
                return null
            }
            Log.d(TAG, "Relogin ok, retrying with fresh token")
            val retryUrl = "${host}/(${user.token})/xskscx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121604"
            val retryDoc = htmlClient.getWithReferer(
                retryUrl,
                "${host}/(${user.token})/xs_main.aspx?xh=${user.account}"
            )
            val retryHtml = retryDoc.html()
            if (userApi.isSessionExpired(retryHtml)) {
                Log.w(TAG, "Session still expired after relogin, retryUrl=${com.ifafu.kyzz.core.crash.TraceSanitizer.sanitize(retryUrl)}")
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
            htmlClient.throwIfAlert(retryHtml)
            return postAndParseExamTable(retryUrl, retryDoc)
        }

        htmlClient.throwIfAlert(html)
        return postAndParseExamTable(accessUrl, doc)
    }

    private suspend fun postAndParseExamTable(accessUrl: String, getDoc: org.jsoup.nodes.Document): ExamTable {
        val yearResult = htmlParser.parseSearchOptions(getDoc, "id=\"xnd\"", "</select>")
        val termResult = htmlParser.parseSearchOptions(getDoc, "id=\"xqd\"", "</select>").excludeTerms("3")
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

        val examTable = if (targetYear == serverYear && targetTerm == serverTerm) {
            Log.d(TAG, "Target matches server default ($serverYear-$serverTerm), using GET response directly")
            examParser.parseExamTable(getDoc)
        } else {
            // 与 SyllabusApi.selectTerm 相同的两段式提交：正方的学年下拉框是
            // AutoPostBack，跨学年时第一次 POST 只更新学年，学期可能仍停留在旧值；
            // 不校验补发的话，拿到的考试表属于"(新学年, 旧学期)"，与推断学期不符。
            var postDoc = postTermSelection(accessUrl, getDoc, if (targetYear != serverYear) "xnd" else "xqd", targetYear, targetTerm)
            var actualYear = selectedOption(postDoc, "xnd")
            var actualTerm = selectedOption(postDoc, "xqd")
            if (actualYear != targetYear || actualTerm != targetTerm) {
                postDoc = postTermSelection(accessUrl, postDoc, "xqd", targetYear, targetTerm)
                actualYear = selectedOption(postDoc, "xnd")
                actualTerm = selectedOption(postDoc, "xqd")
            }
            if (actualYear != targetYear || actualTerm != targetTerm) {
                Log.w(TAG, "考试学期选择未完全生效: target=$targetYear-$targetTerm actual=$actualYear-$actualTerm")
                throw IllegalStateException("考试学期选择未生效，请刷新重试")
            }
            htmlClient.throwIfAlert(postDoc.html())
            examParser.parseExamTable(postDoc)
        }

        if (usedInferred && examTable.exams.isEmpty()) {
            // 当前学期暂无考试（大四实习/毕业学期等），返回空考试表而非抛异常
            Log.i(TAG, "当前学期（${inferred.display()}）暂无考试安排，返回空考试表")
        }

        return examTable
    }

    private suspend fun postTermSelection(
        accessUrl: String,
        sourceDoc: org.jsoup.nodes.Document,
        eventTarget: String,
        year: String,
        term: String
    ): org.jsoup.nodes.Document {
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

    private fun selectedOption(doc: org.jsoup.nodes.Document, selectId: String): String {
        val options = htmlParser.parseSearchOptions(doc, "id=\"$selectId\"", "</select>")
        return options.options.getOrElse(options.selectedIndex) { "" }
    }
}
