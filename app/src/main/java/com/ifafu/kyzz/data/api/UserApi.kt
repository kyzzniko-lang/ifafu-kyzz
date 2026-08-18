package com.ifafu.kyzz.data.api

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Log
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.model.User
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.ZFVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val RELOGIN_TOTAL_TIMEOUT_MS = 90_000L
    }

    data class LoginChallenge internal constructor(
        val bitmap: Bitmap,
        internal val sessionToken: String,
        internal val loginUrl: String,
        internal val viewState: String
    )

    suspend fun prepareLogin(host: String): LoginChallenge? = loginMutex.withLock {
        prepareLoginLocked(host, clearCookies = true)
    }

    private suspend fun prepareLoginLocked(host: String, clearCookies: Boolean): LoginChallenge? {
        return try {
            if (clearCookies) htmlClient.clearCookies()

            val page = htmlClient.getCancellableWithState(host)
            val loginUrl = page.url
            Log.d(TAG, "prepareLogin: host=$host, loginUrl=$loginUrl")

            val tokenMatch = Regex("\\((.*?)\\)/").find(loginUrl)
            if (tokenMatch == null) {
                Log.w(TAG, "prepareLogin: token not found in URL: $loginUrl")
                return null
            }
            val sessionToken = tokenMatch.groupValues[1]
            Log.d(TAG, "prepareLogin: sessionToken=$sessionToken")

            val captchaUrl = "${host}/(${sessionToken})/CheckCode.aspx"
            val bytes = htmlClient.getBytesCancellable(captchaUrl)
            val bitmap = if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            bitmap?.let { LoginChallenge(it, sessionToken, loginUrl, page.viewState) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e(TAG, "prepareLogin failed", e)
            null
        }
    }

    suspend fun login(
        account: String,
        password: String,
        captcha: String,
        user: User,
        challenge: LoginChallenge
    ): Response = loginMutex.withLock {
        loginLocked(account, password, captcha, user, challenge)
    }

    private suspend fun loginLocked(
        account: String,
        password: String,
        captcha: String,
        user: User,
        challenge: LoginChallenge
    ): Response {
        val formBody = htmlClient.buildFormBody(
            "__VIEWSTATE" to challenge.viewState,
            "txtUserName" to account,
            "TextBox2" to password,
            "txtSecretCode" to captcha,
            "RadioButtonList1" to "学生",
            "Button1" to "",
            "lbLanguage" to "",
            "hidPdrs" to "",
            "hidsc" to ""
        )

        val result = htmlClient.postWithFollowCancellable(challenge.loginUrl, formBody)
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
            user.token = challenge.sessionToken
            user.account = account
            user.isLogin = true
            return Response(true, 1, "新生")
        }

        if (finalUrl.contains("xs_main.aspx")) {
            user.token = challenge.sessionToken
        } else {
            val tokenFromUrl = Regex("\\((.*?)\\)/").find(finalUrl)
            user.token = tokenFromUrl?.groupValues?.get(1) ?: challenge.sessionToken
        }

        val authenticatedPage = SessionResponseClassifier.isAuthenticatedLoginResponse(finalUrl, postHtml)
        if (!authenticatedPage) {
            Log.w(TAG, "Login response did not contain an authenticated-page marker: $finalUrl")
            user.token = ""
            user.isLogin = false
            return Response(false, -1, "登录结果无法确认，请刷新验证码后重试")
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

        // Password changes normally return a JavaScript success alert. Read the
        // raw response so that a successful alert is not thrown as an exception.
        val postHtml = htmlClient.postStringRaw(accessUrl, formBody)
        val alert = htmlClient.checkAlert(postHtml)
        // 必须先判成功再判会话过期：改密成功后教务系统常使旧会话失效，响应里
        // 会同时出现成功提示和登录表单；若先判过期，成功会被误报成"会话已过期"，
        // 调用方因此不保存新密码，之后的自动重登永远失败（用户被锁死）。
        val success = alert?.message?.contains("成功") == true ||
            postHtml.contains("修改成功") ||
            postHtml.contains("密码修改成功")
        if (success) {
            return Response(true, 0, "修改成功")
        }
        if (isSessionExpired(postHtml)) {
            return Response(false, -1, "会话已过期，请重新登录")
        }
        if (alert != null) {
            return Response(false, 0, alert.message)
        }
        return Response(false, 0, "教务系统未返回成功结果")
    }

    suspend fun relogin(): Response = loginMutex.withLock {
        // 后台自动重登最多重试 5 次（每次最多 3 个请求 + 指数退避），教务系统挂起时
        // 单次请求要等满 15s 超时，锁可能被占用数分钟，期间前台 login() 一起被阻塞。
        // 总时长上限 90s：正常重登几秒内完成，不受影响。
        withTimeoutOrNull(RELOGIN_TOTAL_TIMEOUT_MS) {
            reloginLocked()
        } ?: Response(false, -1, "自动重新登录超时，请稍后重试").also {
            Log.w(TAG, "Relogin timed out after ${RELOGIN_TOTAL_TIMEOUT_MS}ms")
        }
    }

    private suspend fun reloginLocked(): Response {
        val snapshot = userRepository.getAuthSnapshot()
        val user = snapshot.user
        val password = snapshot.password
        Log.d(TAG, "Relogin: account=${user.account}, hasPassword=${password.isNotBlank()}, token=${user.token.take(10)}...")
        if (user.account.isBlank() || password.isBlank()) {
            Log.w(TAG, "Relogin failed: account or password is blank")
            return Response(false, -1, "未保存登录信息")
        }

        // 权重懒加载后无法在此预判，识别失败走 recognize 返回空串的重试路径
        val maxRetry = 5
        for (i in 1..maxRetry) {
            try {
                Log.d(TAG, "Relogin attempt $i/$maxRetry")
                val challenge = prepareLoginLocked(snapshot.host, clearCookies = true)
                if (challenge == null) {
                    Log.w(TAG, "Relogin attempt $i: prepareLogin returned null")
                    // 指数退避：避免验证码页面连续请求过快
                    delay(500L * (1 shl (i - 1)).coerceAtMost(8))
                    continue
                }

                val captcha = withContext(Dispatchers.Default) { zfVerify.recognize(challenge.bitmap) }
                if (captcha.isEmpty()) {
                    Log.w(TAG, "Relogin attempt $i: captcha recognition returned empty")
                    delay(500L * (1 shl (i - 1)).coerceAtMost(8))
                    continue
                }
                val freshUser = User(account = user.account, name = user.name)
                val response = try {
                    loginLocked(user.account, password, captcha, freshUser, challenge)
                } catch (e: com.ifafu.kyzz.data.network.AlertException) {
                    Response(false, -1, e.message ?: "登录失败")
                }

                if (response.success) {
                    freshUser.institute = user.institute
                    freshUser.clas = user.clas
                    freshUser.enrollment = user.enrollment
                    val saved = userRepository.saveReloginIfUnchanged(
                        user = freshUser,
                        password = password,
                        expectedAccount = user.account,
                        expectedGeneration = snapshot.generation
                    )
                    if (!saved) {
                        Log.w(TAG, "Relogin result discarded because the active account changed")
                        htmlClient.clearCookies()
                        return Response(false, -1, "账号已切换或已退出，本次自动登录已取消")
                    }
                    val savedUser = userRepository.getUser()
                    Log.d(TAG, "Relogin succeeded on attempt $i, savedToken=${savedUser.token.take(10)}...")
                    return response
                } else {
                    Log.w(TAG, "Relogin attempt $i: login failed - ${response.message}")
                    // 只有验证码相关失败才继续；账号/密码错误立即返回，避免无意义重试。
                    if (!isCaptchaErrorMessage(response.message)) {
                        return response
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e(TAG, "Relogin attempt $i: exception", e)
            }
        }

        Log.e(TAG, "Relogin failed after $maxRetry attempts")
        return Response(false, -1, "自动重新登录失败")
    }

    private fun isCaptchaErrorMessage(message: String?): Boolean {
        val normalized = message.orEmpty().lowercase()
        return listOf(
            "验证码", "驗證碼", "校验码", "校驗碼", "识别码", "識別碼",
            "captcha", "verification code", "check code", "code error"
        ).any(normalized::contains)
    }

    fun isSessionExpired(html: String): Boolean {
        return SessionResponseClassifier.isSessionExpired(html)
    }

    fun isTransientServerError(html: String): Boolean =
        SessionResponseClassifier.isTransientServerError(html)
}
