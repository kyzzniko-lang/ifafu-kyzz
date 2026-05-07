package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.model.TrainingPlan
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.TrainingPlanParser
import com.ifafu.kyzz.data.repository.UserRepository
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
            val url = "${host}/(${token})/pyjh.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121607"
            val doc = htmlClient.get(url)
            val html = doc.html()
            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/pyjh.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121607"
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) return null
                return parser.parse(retryDoc)
            }
            parser.parse(doc)
        } catch (e: Exception) {
            Log.e("TrainingPlanApi", "Failed to fetch training plan", e)
            null
        }
    }

    suspend fun getGradeExams(host: String, token: String, number: String, name: String): List<GradeExam> {
        return try {
            val url = "${host}/(${token})/xsdjkscx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121606"
            val doc = htmlClient.get(url)
            val html = doc.html()
            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return emptyList()
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/xsdjkscx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121606"
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) return emptyList()
                return parser.parseGradeExams(retryDoc)
            }
            parser.parseGradeExams(doc)
        } catch (e: Exception) {
            Log.e("TrainingPlanApi", "Failed to fetch grade exams", e)
            emptyList()
        }
    }
}
