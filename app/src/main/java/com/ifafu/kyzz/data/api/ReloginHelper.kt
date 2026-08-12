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

    /** 上一次成功 relogin 的时间与完成后写入的 token，用于并发幂等去重。 */
    @Volatile private var lastReloginAt = 0L
    @Volatile private var lastReloginToken: String = ""
    @Volatile private var lastReloginAccount: String = ""

    companion object {
        private const val TAG = "ReloginHelper"
        /** 视为"近期已重登录"的窗口：若另一并发 caller 在此窗口内已成功重登录，则复用其结果。 */
        private const val RELOGIN_FRESH_WINDOW_MS = 10_000L
    }

    suspend fun relogin(): Response {
        // 进入锁前快照当前 token，用于拿到锁后判断是否已被并发 caller 刷新
        val userBefore = userRepository.getUser()
        val tokenBefore = userBefore.token
        val hostBefore = userRepository.host
        return mutex.withLock {
            // 并发幂等：若在等待锁期间已有 caller 完成重登录，则直接复用，避免 clearCookies 把新会话冲掉。
            // 判定条件——token 已变化，且该变化发生在近期的成功 relogin 窗口内。
            val userAfterLock = userRepository.getUser()
            if (userAfterLock.account != userBefore.account || userRepository.host != hostBefore) {
                Log.w(TAG, "Relogin cancelled because account/host changed while waiting")
                return@withLock Response(false, -1, "账号或教务地址已变更，本次自动登录已取消")
            }
            if (tokenBefore != userAfterLock.token &&
                userAfterLock.token == lastReloginToken &&
                userAfterLock.account == userBefore.account &&
                userAfterLock.account == lastReloginAccount &&
                lastReloginAt > 0L &&
                System.currentTimeMillis() - lastReloginAt < RELOGIN_FRESH_WINDOW_MS
            ) {
                Log.d(TAG, "Relogin skipped: a concurrent caller just relogged in within the fresh window")
                return@withLock Response(true, 0, "已复用并发重登录的会话")
            }
            val response = userApi.relogin()
            if (response.success) {
                val saved = userRepository.getUser()
                lastReloginToken = saved.token
                lastReloginAccount = saved.account
                lastReloginAt = System.currentTimeMillis()
            }
            response
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
        if (user.account != account || userRepository.host != host) {
            Log.w(TAG, "Relogin retry cancelled because the active account or host changed")
            return null
        }
        Log.d(TAG, "Relogin ok, retrying with token=${user.token.take(10)}...")

        // 用新 token 重试
        val retryHtml = action(userRepository.host, user.token, user.account)
        if (userApi.isSessionExpired(retryHtml)) {
            Log.w(TAG, "Session still expired after relogin")
            // 尝试探测主页面获取 alert
            try {
                htmlClient.getCancellable("${host}/(${user.token})/xs_main.aspx?xh=${user.account}")
                htmlClient.getCancellable("${host}/(${user.token})/xsleft.aspx?xh=${user.account}")
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
        if (user.account != account || userRepository.host != host) return null
        val retryHtml = action(userRepository.host, user.token, user.account)
        if (userApi.isSessionExpired(retryHtml)) return null

        try {
            htmlClient.throwIfAlert(retryHtml)
        } catch (e: AlertException) { throw e }

        return retryHtml
    }
}
