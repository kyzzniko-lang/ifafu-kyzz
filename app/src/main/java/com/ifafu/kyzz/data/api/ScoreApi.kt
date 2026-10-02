package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ScoreParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val SCORE_REQUEST_TIMEOUT_MS = 12_000L
        private const val SCORE_POST_TIMEOUT_MS = 8_000L
    }

    suspend fun getAllScores(host: String, token: String, number: String, name: String): List<Score>? {
        return try {
            fetchScores(host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) {
            // 会话失效时正方可能以页面级 alert 拦截（"禁止使用此功能"等）而非
            // 登录页，网络层直接抛异常、isSessionExpired 预检走不到：重登一次
            // 重取（守卫同 StudentQueryApi），仍被拒才抛回原文。
            reloginHelper.reloginAndRetry(e, number, host) { user ->
                fetchScores(host, user.token, user.account, user.name)
            }
        }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch scores", e)
            null
        }
    }

    private suspend fun fetchScores(host: String, token: String, number: String, name: String): List<Score>? {
        val mainUrl = "${host}/(${token})/xs_main.aspx?xh=${number}"

        val accessUrl = "${host}/(${token})/xscjcx_dq_fafu.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121605"

        val doc = withTimeoutOrNull(SCORE_REQUEST_TIMEOUT_MS) {
            htmlClient.getCancellableWithReferer(accessUrl, mainUrl)
        } ?: return null
        val html = doc.html()

        if (userApi.isSessionExpired(html)) {
            Log.d(TAG, "Session expired, attempting relogin...")
            val response = reloginHelper.relogin()
            if (!response.success) {
                Log.w(TAG, "Relogin failed: ${response.message}")
                return null
            }
            val user = userRepository.getUser()
            if (user.account != number || userRepository.host != host) {
                Log.w(TAG, "Relogin retry cancelled because account or host changed")
                return null
            }
            val retryUrl = "${host}/(${user.token})/xscjcx_dq_fafu.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121605"
            val retryDoc = withTimeoutOrNull(SCORE_REQUEST_TIMEOUT_MS) {
                htmlClient.getCancellableWithReferer(
                    retryUrl,
                    "${host}/(${user.token})/xs_main.aspx?xh=${user.account}"
                )
            } ?: return null
            val retryHtml = retryDoc.html()
            if (userApi.isSessionExpired(retryHtml)) {
                // It still says system busy. The ZF system might be blocking us due to pending evaluation.
                // Let's fetch the main pages to see if they contain the alert.
                try {
                    htmlClient.getCancellable("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                    htmlClient.getCancellable("${host}/(${user.token})/xsleft.aspx?xh=${user.account}")
                } catch (e: AlertException) {
                    throw e
                } catch (e: Exception) {
                    // ignore
                }
                return null
            }
            return fetchAllScores(retryUrl, retryDoc)
        }

        return fetchAllScores(accessUrl, doc)
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

        val resultHtml = withTimeoutOrNull(SCORE_POST_TIMEOUT_MS) {
            htmlClient.postStringCancellableWithReferer(accessUrl, formBody, accessUrl)
        } ?: return null
        if (userApi.isSessionExpired(resultHtml)) {
            throw AlertException("会话已过期，请重新登录", true)
        }
        htmlClient.throwIfAlert(resultHtml)
        val allScores = scoreParser.parseScores(resultHtml)

        // 默认页可能只含一个学期，不能以它冒充全量查询成功并覆盖完整缓存。
        if (allScores.isEmpty() && initialScores.isNotEmpty()) return null
        val finalScores = allScores
        // 两轮解析都为空时必须确认页面确实是业务页:分类器漏判的登录页/中间页
        // 会以"空数据成功"的形式穿透到 Worker 并被当作监控基线入库,污染是永久的。
        // 业务特征全缺失则按拉取失败处理(null),Worker 不写缓存只重试。
        if (finalScores.isEmpty() &&
            !SessionResponseClassifier.looksLikeBusinessPage(html) &&
            !SessionResponseClassifier.looksLikeBusinessPage(resultHtml)
        ) {
            Log.w(TAG, "成绩页解析结果为空且缺失业务页特征,疑似异常页面,按失败处理")
            return null
        }
        return finalScores
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
                // 重登等待期间账号/主机可能已切换(守卫同 getAllScores)
                if (user.account != number || userRepository.host != host) {
                    Log.w(TAG, "Relogin retry cancelled because account or host changed")
                    return emptyMap()
                }
                val retryUrl = "${host}/(${user.token})/pyjh.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121607"
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
