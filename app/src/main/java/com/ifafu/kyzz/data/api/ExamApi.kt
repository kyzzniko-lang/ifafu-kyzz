package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.parser.ExamParser
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExamApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val examParser: ExamParser,
    private val userApi: UserApi,
    private val reloginHelper: ReloginHelper,
    private val userRepository: UserRepository
) {

    companion object {
        private const val TAG = "ExamApi"
    }

    suspend fun getExamTable(host: String, token: String, number: String, name: String): ExamTable? {
        return try {
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
                val retryDoc = htmlClient.get(retryUrl)
                val retryHtml = retryDoc.html()
                if (userApi.isSessionExpired(retryHtml)) {
                    Log.w(TAG, "Session still expired after relogin, retryUrl=$retryUrl")
                    Log.w(TAG, "Retry HTML snippet: ${retryHtml.take(500)}")
                    return null
                }
                return examParser.parseExamTable(retryDoc)
            }

            examParser.parseExamTable(doc)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "Failed to fetch exam table", e)
            null
        }
    }
}
