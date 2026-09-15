package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.network.AlertException
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
            fetchStudentInfo(host, token, number, name)
        } catch (e: CancellationException) { throw e }
        catch (e: AlertException) {
            // HtmlClient.get 对页面级 alert（如会话失效时正方返回的
            // "对不起,禁止使用此功能"拦截页）在网络层直接抛异常，下面的
            // isSessionExpired 预检走不到：统一重登一次重取，仍被拒才抛回。
            reloginHelper.reloginAndRetry(e, number, host) { user ->
                fetchStudentInfo(host, user.token, user.account, user.name)
            }
        }
        catch (e: Exception) {
            Log.e("StudentInfoApi", "Failed to fetch student info", e)
            null
        }
    }

    private suspend fun fetchStudentInfo(host: String, token: String, number: String, name: String): StudentInfo? {
        val accessUrl = "${host}/(${token})/xsgrxx.aspx?xh=${number}&xm=${URLEncoder.encode(name, "gbk")}&gnmkdm=N121501"
        val doc = htmlClient.get(accessUrl)
        val html = doc.html()

        if (userApi.isSessionExpired(html)) {
            val response = reloginHelper.relogin()
            if (!response.success) return null
            val user = userRepository.getUser()
            // 重登期间用户可能已切换账号/退出：以 B 的 token + A 的姓名发请求，
            // 轻则拿到空数据，重则把 A 的学籍信息挂进 B 的会话。
            if (user.account != number) return null
            val retryUrl = "${host}/(${user.token})/xsgrxx.aspx?xh=${user.account}&xm=${URLEncoder.encode(user.name, "gbk")}&gnmkdm=N121501"
            val retryDoc = htmlClient.get(retryUrl)
            val retryHtml = retryDoc.html()
            if (userApi.isSessionExpired(retryHtml)) return null
            htmlClient.throwIfAlert(retryHtml)
            return studentInfoParser.parseStudentInfo(retryDoc, user.account)
        }

        htmlClient.throwIfAlert(html)
        return studentInfoParser.parseStudentInfo(doc, number)
    }
}
