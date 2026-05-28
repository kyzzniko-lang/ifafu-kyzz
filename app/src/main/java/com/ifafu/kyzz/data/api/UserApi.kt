package com.ifafu.kyzz.data.api

import android.graphics.BitmapFactory
import android.util.Log
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.ZFVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserApi @Inject constructor(
    private val htmlClient: HtmlClient,
    private val userRepository: UserRepository,
    private val zfVerify: ZFVerify
) {
    private val loginMutex = Mutex()

    companion object {
        private const val TAG = "UserApi"
    }

    @Volatile private var sessionToken: String = ""
    @Volatile private var loginUrl: String = ""

    suspend fun prepareLogin(host: String): android.graphics.Bitmap? {
        return try {
            sessionToken = ""
            loginUrl = ""

            htmlClient.get(host)
            loginUrl = htmlClient.lastUrl
            Log.d(TAG, "prepareLogin: host=$host, loginUrl=$loginUrl")

            val tokenMatch = Regex("\\((.*?)\\)/").find(loginUrl)
            if (tokenMatch == null) {
                Log.w(TAG, "prepareLogin: token not found in URL: $loginUrl")
                return null
            }
            sessionToken = tokenMatch.groupValues[1]
            Log.d(TAG, "prepareLogin: sessionToken=$sessionToken")

            val captchaUrl = "${host}/(${sessionToken})/CheckCode.aspx"
            val bytes = htmlClient.getBytes(captchaUrl)
            if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "prepareLogin failed", e)
            null
        }
    }

    suspend fun login(account: String, password: String, captcha: String, user: User): Response {
        loginMutex.withLock {
            if (sessionToken.isEmpty() || loginUrl.isEmpty()) {
                val bitmap = prepareLogin(userRepository.host)
                if (bitmap == null || sessionToken.isEmpty() || loginUrl.isEmpty()) {
                    return Response(false, -1, "无法连接教务系统，请重试")
                }
            }
        }

        val formBody = htmlClient.buildFormBody(
            "__VIEWSTATE" to htmlClient.viewState,
            "txtUserName" to account,
            "TextBox2" to password,
            "txtSecretCode" to captcha,
            "RadioButtonList1" to "学生",
            "Button1" to "",
            "lbLanguage" to "",
            "hidPdrs" to "",
            "hidsc" to ""
        )

        val result = htmlClient.postWithFollow(loginUrl, formBody)
        val postHtml = result.html
        val finalUrl = result.url

        val alertResponse = htmlClient.checkAlert(postHtml)
        if (alertResponse != null) {
            return alertResponse
        }

        if (postHtml.contains("出错啦") || postHtml.contains("系统正忙")) {
            return Response(false, -1, "教务系统繁忙，请稍后再试")
        }

        if (postHtml.contains("输入新密码")) {
            user.token = sessionToken
            user.account = account
            user.isLogin = true
            return Response(true, 1, "新生")
        }

        if (finalUrl.contains("xs_main.aspx")) {
            user.token = sessionToken
        } else {
            val tokenFromUrl = Regex("\\((.*?)\\)/").find(finalUrl)
            user.token = tokenFromUrl?.groupValues?.get(1) ?: sessionToken
        }

        val nameMatch = Regex("""xhxm["']?>?\s*(.+?)\s*同学""").find(postHtml)
            ?: Regex("""xhxm["']?>?\s*(.+?)\s*</""").find(postHtml)
        if (nameMatch != null) {
            user.name = nameMatch.groupValues[1].trim()
        } else {
            Log.w(TAG, "Name extraction failed, html snippet: ${postHtml.take(1000)}")
        }

        if (user.token.isEmpty()) {
            return Response(false, -1, "登录失败，请重试")
        }

        user.account = account
        user.isLogin = true
        return Response(true, 0, user.name.ifEmpty { "登录成功" })
    }

    suspend fun modifyPassword(host: String, token: String, account: String, oldPwd: String, newPwd: String): Response {
        val accessUrl = "${host}/(${token})/mmxg.aspx?xh=${account}&rmm=true"

        val getResult = htmlClient.getStringWithViewState(accessUrl)

        val formBody = htmlClient.buildFormBodyWithViewState(
            "TextBox2" to oldPwd,
            "TextBox3" to newPwd,
            "TextBox4" to newPwd,
            "Button1" to "修  改",
            state = getResult.viewState
        )

        val postHtml = htmlClient.postString(accessUrl, formBody)
        val alert = htmlClient.checkAlert(postHtml)
        if (alert != null && !alert.message.contains("修改成功")) {
            return Response(false, 0, alert.message)
        }
        return Response(true, 0, "修改成功")
    }

    suspend fun relogin(): Response {
        val user = userRepository.getUser()
        val password = userRepository.getPassword()
        Log.d(TAG, "Relogin: account=${user.account}, hasPassword=${password.isNotBlank()}, token=${user.token.take(10)}...")
        if (user.account.isBlank() || password.isBlank()) {
            Log.w(TAG, "Relogin failed: account or password is blank")
            return Response(false, -1, "未保存登录信息")
        }

        if (!zfVerify.initialized) {
            Log.w(TAG, "Relogin failed: ZFVerify not initialized")
            return Response(false, -1, "验证码识别模块未初始化")
        }

        val maxRetry = 5
        for (i in 1..maxRetry) {
            try {
                Log.d(TAG, "Relogin attempt $i/$maxRetry")
                val captchaBitmap = prepareLogin(userRepository.host)
                if (captchaBitmap == null) {
                    Log.w(TAG, "Relogin attempt $i: prepareLogin returned null, sessionToken=$sessionToken, loginUrl=$loginUrl")
                    continue
                }

                val captcha = zfVerify.recognize(captchaBitmap)
                if (captcha.isEmpty()) {
                    Log.w(TAG, "Relogin attempt $i: captcha recognition returned empty")
                    continue
                }
                val freshUser = User(account = user.account, name = user.name)
                val response = login(user.account, password, captcha, freshUser)

                if (response.success) {
                    freshUser.institute = user.institute
                    freshUser.clas = user.clas
                    freshUser.enrollment = user.enrollment
                    userRepository.saveUser(freshUser)
                    userRepository.savePassword(password)
                    val savedUser = userRepository.getUser()
                    Log.d(TAG, "Relogin succeeded on attempt $i, savedToken=${savedUser.token.take(10)}...")
                    return response
                } else {
                    Log.w(TAG, "Relogin attempt $i: login failed - ${response.message}")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Relogin attempt $i: exception", e)
            }
        }

        Log.e(TAG, "Relogin failed after $maxRetry attempts")
        return Response(false, -1, "自动重新登录失败")
    }

    fun isSessionExpired(html: String): Boolean {
        // Check for actual login form elements (not just text mentions)
        if (html.contains("id=\"txtUserName\"") && html.contains("id=\"TextBox2\"")) return true
        // Check for JS redirect to login page
        if (Regex("""location\s*[=.]\s*["'][^"']{0,30}default[2]?\.aspx""").containsMatchIn(html)) return true
        if (Regex("""window\.location\s*=\s*["'][^"']{0,30}default[2]?\.aspx""").containsMatchIn(html)) return true
        return false
    }
}
