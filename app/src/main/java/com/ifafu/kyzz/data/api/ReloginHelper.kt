package com.ifafu.kyzz.data.api

import android.util.Log
import com.ifafu.kyzz.data.model.Response
import com.ifafu.kyzz.data.network.AlertException
import com.ifafu.kyzz.data.network.HtmlClient
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.ui.event.GlobalEvent
import com.ifafu.kyzz.ui.event.GlobalEventBus
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

    /**
     * 失败消息是否表明"保存的账号/密码已失效"（用户在教务改过密码、账号被冻结等）。
     * 这类失败重试永远无效：继续留在"僵尸登录态"会让每个操作都白跑一轮
     * prepareLogin+识别+login（3 个请求）还拿不到数据。此时发出 SessionExpired
     * 让 BaseActivity 统一跳登录页，用户重新输入密码后自愈。
     * 网络超时/教务繁忙等瞬时失败不在此列，避免弱网误踢用户。
     */
    private fun isCredentialError(message: String?): Boolean {
        val m = message.orEmpty()
        if (m.isEmpty()) return false
        return listOf("密码不正确", "密码错误", "用户名或密码", "帐号或密码", "账号或密码", "修改初始密码", "用户名不存在", "不存在或密码").any(m::contains)
    }

    private fun handleReloginFailure(response: Response) {
        if (response.success) return
        if (isCredentialError(response.message)) {
            Log.w(TAG, "Relogin failed due to credential error, directing user to login screen")
            GlobalEventBus.emit(GlobalEvent.SessionExpired)
        }
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
            // 并发幂等：若在等待锁期间会话已被刷新（并发 caller 重登录成功，
            // 或用户在登录页手动登录成功），直接复用新会话。判定只看 token 是否
            // 变化：不能比对 lastReloginToken——前台手动登录不经过本类，若只认
            // 自己刷出的 token，排队的后台重登会 clearCookies 把前台刚建立的
            // 会话冲掉，用户下一个请求又"会话过期"。
            if (tokenBefore != userAfterLock.token && userAfterLock.token.isNotEmpty()) {
                Log.d(TAG, "Relogin skipped: session was refreshed while waiting for the lock")
                return@withLock Response(true, 0, "已复用刚刚建立的新会话")
            }
            val response = userApi.relogin()
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
        Log.d(TAG, "Session expired, attempting relogin...")
        val response = relogin()
        if (!response.success) {
            Log.w(TAG, "Relogin failed: ${response.message}")
            handleReloginFailure(response)
            return null
        }

        val user = userRepository.getUser()
        if (user.account != account || userRepository.host != host) {
            Log.w(TAG, "Relogin retry cancelled because the active account or host changed")
            return null
        }
        Log.d(TAG, "Relogin ok, retrying with fresh token")

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
     * "AlertException 型会话失效"的统一自救入口。
     *
     * HtmlClient.get/getString 在网络层对页面级 alert 直接抛 AlertException，
     * API 层的 isSessionExpired 预检（只认登录页特征）根本没有机会执行——
     * 正方对失效 session 直连功能页往往不返回登录页，而是顶层 alert
     * （如"对不起,禁止使用此功能"），且文案随部署而变、无法穷举。
     * 因此这里不依赖文案判断：catch 到 alert 后一律重登一次并重取。
     *
     * - 重登成功且账号/host 未变 → 用新会话重取，调用方无感恢复；
     * - 重登失败或重取仍抛 → 抛回 [original]（或重取的新异常），由上层按
     *   原文展示；若学校真禁用了该功能，原文即真相。
     */
    suspend fun <T> reloginAndRetry(
        original: AlertException,
        expectedAccount: String,
        expectedHost: String,
        refetch: suspend (com.ifafu.kyzz.data.model.User) -> T
    ): T {
        Log.w(TAG, "Page-level alert '${original.message}', retrying once after relogin")
        val response = relogin()
        if (!response.success) {
            handleReloginFailure(response)
            throw original
        }
        val user = userRepository.getUser()
        // 重登等待期间账号/教务地址可能已切换：新会话配旧参数会跨账号串数据
        if (user.account != expectedAccount || userRepository.host != expectedHost) {
            Log.w(TAG, "Relogin retry cancelled because the active account or host changed")
            throw original
        }
        return try {
            refetch(user)
        } catch (e: AlertException) {
            // 重登成功后仍被拦截:会话是全新的,不再是会话问题——大概率是教务
            // 对该功能模块(gnmkdm)本身停止了授权(学校侧下架/禁用)。明确告知
            // 而非误导用户"会话过期,请重新登录"(重新登录也解决不了)。
            throw AlertException(
                "重登后教务仍拦截该功能,可能已被学校停用(教务提示:${e.message})"
            )
        }
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
        if (!response.success) {
            handleReloginFailure(response)
            return null
        }

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
