package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReloginHelper @Inject constructor(
    private val userApi: UserApi,
    private val htmlClient: HtmlClient,
    private val userRepository: UserRepository
) {
    private val mutex = Mutex()

    companion object {
        private const val TAG = "ReloginHelper"
    }

    suspend fun relogin(): Response {
        return mutex.withLock {
            htmlClient.clearCookies()
            userApi.relogin()
        }
    }

    /**
     * 通用的"带重登录重试"包装方法。
     *
     * 执行 [action]，如果返回的 HTML 表示 session 过期，则自动重登录后用新 token 重试。
     * 如果重试后仍然过期，尝试探测主页面获取 alert 信息，然后返回 null。
     *
     * @param host 教务系统 host
     * @param token 当前 session token
     * @param account 学号
     * @param action 需要执行的操作，参数为 (host, token, account)，返回 HTML 字符串
     * @return 操作成功返回 HTML，失败返回 null
     */
    suspend fun withRelogin(
        host: String,
        token: String,
        account: String,
        action: suspend (host: String, token: String, account: String) -> String
    ): String? {
        val html = action(host, token, account)
        if (!userApi.isSessionExpired(html)) return html

        // Session 过期，尝试重登录
        Log.d(TAG, "Session expired, token=${token.take(10)}..., attempting relogin...")
        val response = relogin()
        if (!response.success) {
            Log.w(TAG, "Relogin failed: ${response.message}")
            return null
        }

        val user = userRepository.getUser()
        Log.d(TAG, "Relogin ok, retrying with token=${user.token.take(10)}...")

        // 用新 token 重试
        val retryHtml = action(host, user.token, user.account)
        if (userApi.isSessionExpired(retryHtml)) {
            Log.w(TAG, "Session still expired after relogin")
            // 尝试探测主页面获取 alert
            try {
                htmlClient.get("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                htmlClient.get("${host}/(${user.token})/xsleft.aspx?xh=${user.account}")
            } catch (e: AlertException) { throw e } catch (_: Exception) {}
            return null
        }

        try {
            htmlClient.throwIfAlert(retryHtml)
        } catch (e: AlertException) { throw e }

        return retryHtml
    }

    /**
     * 简化版重登录重试，不探测主页面，适用于非核心 API。
     * 重试后仍过期直接返回 null。
     */
    suspend fun withReloginSimple(
        host: String,
        token: String,
        account: String,
        action: suspend (host: String, token: String, account: String) -> String
    ): String? {
        val html = action(host, token, account)
        if (!userApi.isSessionExpired(html)) return html

        val response = relogin()
        if (!response.success) return null

        val user = userRepository.getUser()
        val retryHtml = action(host, user.token, user.account)
        if (userApi.isSessionExpired(retryHtml)) return null

        try {
            htmlClient.throwIfAlert(retryHtml)
        } catch (e: AlertException) { throw e }

        return retryHtml
    }
}
