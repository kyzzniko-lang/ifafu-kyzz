package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ScoreParser
import com.ifafu.kyzz.data.repository.UserRepository
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
                val retryUrl = "${host}/(${user.token})/xscjcx_dq_fafu.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121605"
                return fetchAllScores(retryUrl, user.account, user.name)
            }

            fetchAllScores(accessUrl, number, name)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch scores", e)
            null
        }
    }

    private suspend fun fetchAllScores(accessUrl: String, number: String, name: String): List<Score>? {
        val doc = htmlClient.get(accessUrl)
        val html = doc.html()

        if (userApi.isSessionExpired(html)) return null

        if (html.contains("alert") && html.contains("教学质量评价")) {
            return null
        }

        val initialScores = scoreParser.parseScores(doc)

        val formBody = htmlClient.buildViewStateFormBody()
            .add("__EVENTTARGET", "")
            .add("__EVENTARGUMENT", "")
            .add("ddlxn", "全部")
            .add("ddlxq", "全部")
            .add("btnCx", "查  询")
            .build()

        val resultHtml = htmlClient.postString(accessUrl, formBody)
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
                val retryUrl = "${host}/(${user.token})/pyjh.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121607"
                doc = htmlClient.get(retryUrl)
                val retryHtml = doc.html()
                if (userApi.isSessionExpired(retryHtml)) return emptyMap()
            }

            scoreParser.parseElectiveTargetScore(doc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch elective target score", e)
            emptyMap()
        }
    }
}
