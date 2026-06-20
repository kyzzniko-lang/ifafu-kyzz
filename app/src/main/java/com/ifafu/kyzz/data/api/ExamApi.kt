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
            val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"
            htmlClient.setReferer(mainUrl)

            val accessUrl = "${host}/(${token})/xskscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121604"
            val doc = htmlClient.get(accessUrl)
            val html = doc.html()

            if (userApi.isSessionExpired(html)) {
                Log.d(TAG, "Session expired, token=${token.take(10)}..., attempting relogin...")
                val response = reloginHelper.relogin()
                if (!response.success) {
                    Log.w(TAG, "Relogin failed: ${response.message}")
                    return null
                }
                val user = userRepository.getUser()
                Log.d(TAG, "Relogin ok, retrying with token=${user.token.take(10)}..., account=${user.account}, name=$name")
                val retryUrl = "${host}/(${user.token})/xskscx.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121604"
                htmlClient.setReferer("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) {
                    Log.w(TAG, "Session still expired after relogin, retryUrl=$retryUrl")
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
            postAndParseExamTable(accessUrl, doc)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch exam table", e)
            null
        }
    }

    private suspend fun postAndParseExamTable(accessUrl: String, getDoc: org.jsoup.nodes.Document): ExamTable {
        val getHtml = getDoc.html()
        val state = htmlClient.parseViewState(getHtml)

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
            val needYearChange = targetYear != serverYear
            val eventTarget = if (needYearChange) "xnd" else "xqd"

            Log.d(
                TAG,
                "POST xnd=$targetYear, xqd=$targetTerm (inferred=${inferred.year}-${inferred.term}, server=$serverYear-$serverTerm, usedInferred=$usedInferred, needYearChange=$needYearChange, eventTarget=$eventTarget)"
            )
            val formBody = htmlClient.buildFormBodyWithViewState(
                "__EVENTTARGET" to eventTarget,
                "__EVENTARGUMENT" to "",
                "xnd" to targetYear,
                "xqd" to targetTerm,
                state = state
            )

            val postDoc = htmlClient.post(accessUrl, formBody)
            htmlClient.throwIfAlert(postDoc.html())
            examParser.parseExamTable(postDoc)
        }

        if (usedInferred && examTable.exams.isEmpty()) {
            // 当前学期暂无考试（大四实习/毕业学期等），返回空考试表而非抛异常
            Log.i(TAG, "当前学期（${inferred.display()}）暂无考试安排，返回空考试表")
        }

        return examTable
    }
}
