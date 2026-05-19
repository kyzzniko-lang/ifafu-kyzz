package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.StudentInfoParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StudentInfoApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val studentInfoParser: StudentInfoParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    suspend fun getStudentInfo(host: String, token: String, number: String, name: String): StudentInfo? {
        return try {
            val accessUrl = "${host}/(${token})/xsgrxx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121501"
            val doc = htmlClient.get(accessUrl)
            val html = doc.html()

            if (userApi.isSessionExpired(html)) {
                val response = reloginHelper.relogin()
                if (!response.success) return null
                val user = userRepository.getUser()
                val retryUrl = "${host}/(${user.token})/xsgrxx.aspx?xh=${user.account}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121501"
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) return null
                return studentInfoParser.parseStudentInfo(retryDoc, user.account)
            }

            studentInfoParser.parseStudentInfo(doc, number)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("StudentInfoApi", "Failed to fetch student info", e)
            null
        }
    }
}
