package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ScoreParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoreApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val scoreParser: ScoreParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    companion object {
        private const val TAG = "ScoreApi"
    }

    suspend fun getAllScores(host: String, token: String, number: String, name: String): List<Score>? {
        return try {
            val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"
            htmlClient.setReferer(mainUrl)
            
            val accessUrl = "${host}/(${token})/xscjcx_dq_fafu.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121605"

            val doc = htmlClient.get(accessUrl)
            val html = doc.html()

            if (userApi.isSessionExpired(html)) {
                Log.d(TAG, "Session expired, attempting relogin...")
                val response = reloginHelper.relogin()
                if (!response.success) {
                    Log.w(TAG, "Relogin failed: ${response.message}")
                    return null
                }
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/xscjcx_dq_fafu.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121605"
                htmlClient.setReferer("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) {
                    // It still says system busy. The ZF system might be blocking us due to pending evaluation.
                    // Let's fetch the main pages to see if they contain the alert.
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
                return fetchAllScores(retryUrl, retryDoc)
            }

            fetchAllScores(accessUrl, doc)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch scores", e)
            null
        }
    }

    private suspend fun fetchAllScores(accessUrl: String, initialDoc: org.jsoup.nodes.Document): List<Score>? {
        val html = initialDoc.html()

        if (userApi.isSessionExpired(html)) return null

        htmlClient.throwIfAlert(html)

        val initialScores = scoreParser.parseScores(initialDoc)

        val viewState = htmlClient.parseViewState(html)
        val formBody = htmlClient.buildViewStateFormBody(viewState)
            .add("__EVENTTARGET", "")
            .add("__EVENTARGUMENT", "")
            .add("ddlxn", "全部")
            .add("ddlxq", "全部")
            .add("btnCx", "查  询")
            .build()

        val resultHtml = htmlClient.postString(accessUrl, formBody)
        htmlClient.throwIfAlert(resultHtml)
        val allScores = scoreParser.parseScores(resultHtml)

        return if (allScores.isNotEmpty()) allScores else initialScores
    }

    suspend fun getElectiveTargetScore(host: String, token: String, number: String, name: String): Map<String, Float> {
        return try {
            val accessUrl = "${host}/(${token})/pyjh.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121607"
            var doc = htmlClient.get(accessUrl)
            var html = doc.html()

            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return emptyMap()
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/pyjh.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121607"
                doc = htmlClient.get(retryUrl)
                val retryHtml = doc.html()
                if (userApi.isSessionExpired(retryHtml)) return emptyMap()
                htmlClient.throwIfAlert(retryHtml)
            } else {
                htmlClient.throwIfAlert(html)
            }

            scoreParser.parseElectiveTargetScore(doc)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch elective target score", e)
            emptyMap()
        }
    }
}
