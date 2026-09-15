package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.model.TrainingPlan
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.TrainingPlanParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingPlanApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val parser: TrainingPlanParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    suspend fun getTrainingPlan(host: String, token: String, number: String, name: String): TrainingPlan? {
        return try {
            fetchTrainingPlan(host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) {
            // 会话失效时正方可能以"禁止使用此功能"类 alert 拦截而非登录页，
            // 网络层直接抛异常、isSessionExpired 预检走不到：重登一次重取。
            reloginHelper.reloginAndRetry(e, number, host) { user ->
                fetchTrainingPlan(host, user.token, user.account, user.name)
            }
        }
        catch (e: Exception) {
            Log.e("TrainingPlanApi", "Failed to fetch training plan", e)
            null
        }
    }

    private suspend fun fetchTrainingPlan(host: String, token: String, number: String, name: String): TrainingPlan? {
        val url = "${host}/(${token})/pyjh.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121607"
        val doc = htmlClient.get(url)
        val html = doc.html()
        if (userApi.isSessionExpired(html)) {
            val response = reloginHelper.relogin()
            if (!response.success) return null
            val user = userRepository.getUser()
            // 重登期间账号可能已切换：新 token + 旧姓名参数会把 A 的培养计划
            // 挂进 B 的会话，账号不一致直接放弃。
            if (user.account != number) return null
            val retryUrl = "${host}/(${user.token})/pyjh.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121607"
            val retryDoc = htmlClient.get(retryUrl)
            val retryHtml = retryDoc.html()
            if (userApi.isSessionExpired(retryHtml)) return null
            htmlClient.throwIfAlert(retryHtml)
            return parser.parse(retryDoc)
        }
        htmlClient.throwIfAlert(html)
        return parser.parse(doc)
    }

    suspend fun getGradeExams(host: String, token: String, number: String, name: String): List<GradeExam> {
        return try {
            fetchGradeExams(host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) {
            reloginHelper.reloginAndRetry(e, number, host) { user ->
                fetchGradeExams(host, user.token, user.account, user.name)
            }
        }
        catch (e: Exception) {
            Log.e("TrainingPlanApi", "Failed to fetch grade exams", e)
            emptyList()
        }
    }

    private suspend fun fetchGradeExams(host: String, token: String, number: String, name: String): List<GradeExam> {
        val url = "${host}/(${token})/xsdjkscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121606"
        val doc = htmlClient.get(url)
        val html = doc.html()
        if (userApi.isSessionExpired(html)) {
            val response = reloginHelper.relogin()
            if (!response.success) return emptyList()
            val user = userRepository.getUser()
            // 同 fetchTrainingPlan：账号已切换时放弃，避免跨账号数据串台。
            if (user.account != number) return emptyList()
            val retryUrl = "${host}/(${user.token})/xsdjkscx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121606"
            val retryDoc = htmlClient.get(retryUrl)
            val retryHtml = retryDoc.html()
            if (userApi.isSessionExpired(retryHtml)) return emptyList()
            htmlClient.throwIfAlert(retryHtml)
            return parser.parseGradeExams(retryDoc)
        }
        htmlClient.throwIfAlert(html)
        return parser.parseGradeExams(doc)
    }
}
