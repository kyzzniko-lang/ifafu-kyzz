package com.ifafu.kyzz.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.User
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheManager: CacheManager
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences =
        context.getSharedPreferences("ifafu_secure", Context.MODE_PRIVATE)

    private val gson = Gson()

    /**
     * Changes whenever the active credentials/account are replaced. A relogin
     * may only commit its result while this generation is still unchanged.
     */
    private var authGeneration: Long = prefs.getLong("auth_generation", 0L)

    data class AuthSnapshot(
        val user: User,
        val password: String,
        val host: String,
        val generation: Long
    )

    private fun bumpAuthGeneration() {
        authGeneration += 1
        prefs.edit().putLong("auth_generation", authGeneration).apply()
    }

    @Synchronized
    fun saveUser(user: User) {
        prefs.edit().apply {
            putString("account", user.account)
            putString("name", user.name)
            putString("institute", user.institute)
            putString("clas", user.clas)
            putInt("enrollment", user.enrollment)
            putBoolean("isLogin", user.isLogin)
            apply()
        }
        securePrefs.edit().putString("token", user.token).apply()
        saveAccountProfile(user.account)
        bumpAuthGeneration()
    }

    // 账号字段在 ifafu_user、token 在 ifafu_secure 两个文件里。写侧(saveUser/
    // clearUser)虽各自原子,但读侧不同步时可能读到"新 account 配旧/已删 token"
    // 的撕裂快照——与登出或切号并发的 API 调用会拿着错 token 去请求。
    @Synchronized
    fun getUser(): User {
        return User(
            account = prefs.getString("account", "") ?: "",
            name = prefs.getString("name", "") ?: "",
            token = securePrefs.getString("token", "") ?: "",
            institute = prefs.getString("institute", "") ?: "",
            clas = prefs.getString("clas", "") ?: "",
            enrollment = prefs.getInt("enrollment", 0),
            isLogin = prefs.getBoolean("isLogin", false)
        )
    }

    @Synchronized
    fun savePassword(password: String) {
        // 密码只存 securePrefs 一份：ifafu_user 明文冗余备份已废弃，
        // 老版本残留由 getPassword 的一次性迁移清理。
        securePrefs.edit().putString("password", password).apply()
        saveAccountProfile(prefs.getString("account", "") ?: "")
        bumpAuthGeneration()
    }

    @Synchronized
    fun getPassword(): String {
        val password = securePrefs.getString("password", "") ?: ""
        if (password.isNotEmpty()) return password
        // 一次性迁移老版本遗留在 ifafu_user 的明文备份：写回 securePrefs 后
        // 删除 backup 键，明文不再双份留存。
        val backup = prefs.getString("password_backup", "") ?: ""
        if (backup.isNotEmpty()) {
            securePrefs.edit().putString("password", backup).apply()
            prefs.edit().remove("password_backup").apply()
        }
        return backup
    }

    @Synchronized
    fun clearUser() {
        bumpAuthGeneration()
        com.ifafu.kyzz.di.JavaNetCookieJar.getInstance(context).clear()
        prefs.edit().apply {
            remove("account")
            remove("name")
            remove("institute")
            remove("clas")
            remove("enrollment")
            remove("isLogin")
            remove("password_backup")
            remove("token")
            apply()
        }
        securePrefs.edit().apply {
            remove("password")
            remove("token")
            // 保留 saved_accounts ：clearUser 是"退出当前登录"，不是"忘记所有账号"。
            // 双账号用户注销其一后，另一账号仍应出现在登录页的账号选择器里；
            // 彻底删除某个账号走 removeAccount()。
            apply()
        }
        // termFirstDay/termFirstDayManual 是按设备保存的全局配置：A 账号设置过
        // 手动开学日后登出，B 账号会继承 manual 标志，之后所有自动学期同步
        // （GitHub/服务器周次标签）永久失效。登出时一并重置，下次登录按校历
        // 或远程配置重建。
        prefs.edit().apply {
            remove("termFirstDay")
            remove("termFirstDayManual")
            apply()
        }
    }

    var host: String
        // 与 setter 同一把锁：无锁读可能撞上"putString 已提交、旧值还在"的
        // 过渡态，拿到与 cookie 清理动作错位的 host。
        get() = synchronized(this) {
            prefs.getString("host", "http://jwgl.fafu.edu.cn") ?: "http://jwgl.fafu.edu.cn"
        }
        set(value) {
            synchronized(this) {
                if (value == host) return@synchronized
                prefs.edit().putString("host", value).apply()
                com.ifafu.kyzz.di.JavaNetCookieJar.getInstance(context).clear()
                bumpAuthGeneration()
            }
        }

    var termFirstDay: String
        get() = prefs.getString("termFirstDay", "") ?: ""
        set(value) = prefs.edit().putString("termFirstDay", value).apply()

    var termFirstDayManual: Boolean
        get() = prefs.getBoolean("termFirstDayManual", false)
        set(value) = prefs.edit().putBoolean("termFirstDayManual", value).apply()

    data class AccountProfile(val account: String, val name: String, val password: String)

    private fun saveAccountProfile(account: String) {
        if (account.isEmpty()) return
        val name = prefs.getString("name", "") ?: ""
        val password = securePrefs.getString("password", "") ?: ""
        saveAccountProfileInternal(account, name, password)
    }

    private fun saveAccountProfileInternal(account: String, name: String, password: String) {
        if (account.isEmpty()) return
        val profiles = getAccountProfiles().toMutableList()
        val existing = profiles.indexOfFirst { it.account == account }
        val profile = AccountProfile(account, name, password)
        if (existing >= 0) profiles[existing] = profile else profiles.add(profile)
        securePrefs.edit().putString("saved_accounts", gson.toJson(profiles)).apply()
    }

    fun getAccountProfiles(): List<AccountProfile> {
        val json = securePrefs.getString("saved_accounts", "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<AccountProfile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun switchAccount(profile: AccountProfile) {
        bumpAuthGeneration()
        com.ifafu.kyzz.di.JavaNetCookieJar.getInstance(context).clear()
        prefs.edit().apply {
            putString("account", profile.account)
            putString("name", profile.name)
            putBoolean("isLogin", false)
            // 必须清掉上一账号的学籍快照：这些字段只在登录成功时写入，切号后若
            // 残留旧值，首页/成绩单会把 A 的学院班级安到 B 头上，且随后的后台
            // 自动重登会把脏数据固化进 B 的档案。
            remove("institute")
            remove("clas")
            remove("enrollment")
            // 密码随 profile 写入后，prefs 里的 password_backup 就是上一账号的
            // 明文残留；不清的话空密码 profile 会回退用它去登错账号。
            remove("password_backup")
            apply()
        }
        securePrefs.edit().apply {
            putString("password", profile.password)
            remove("token")
            apply()
        }
    }

    // 与 saveAccountProfileInternal 一样做"读-改-写"，必须同步：
    // 否则与后台自动重登的保存交错时，刚删除的账号可能被 resurrect。
    @Synchronized
    fun removeAccount(account: String) {
        val profiles = getAccountProfiles().toMutableList()
        profiles.removeAll { it.account == account }
        securePrefs.edit().putString("saved_accounts", gson.toJson(profiles)).apply()
        // 删除账号档案时同步清掉该账号的业务缓存：学籍（含身份证号等明文）、
        // 成绩、聊天记录不得残留。clearCache 只清该账号前缀的键，不影响在用账号。
        cacheManager.clearCache(account)
        cacheManager.clearChatHistory(account)
        cacheManager.clearScoreFirstSeen(account)
    }

    @Synchronized
    fun getAuthSnapshot(): AuthSnapshot = AuthSnapshot(
        user = getUser().copy(),
        password = getPassword(),
        host = host,
        generation = authGeneration
    )

    /** Atomically stores a foreground login and invalidates older relogin work. */
    @Synchronized
    fun saveAuthenticatedUser(user: User, password: String) {
        // 密码只写 securePrefs；不再向 ifafu_user 写 password_backup 明文副本
        prefs.edit().apply {
            putString("account", user.account)
            putString("name", user.name)
            putString("institute", user.institute)
            putString("clas", user.clas)
            putInt("enrollment", user.enrollment)
            putBoolean("isLogin", user.isLogin)
            apply()
        }
        securePrefs.edit().apply {
            putString("token", user.token)
            putString("password", password)
            apply()
        }
        saveAccountProfileInternal(user.account, user.name, password)
        bumpAuthGeneration()
    }

    /**
     * Prevents an in-flight relogin from resurrecting an account after logout
     * or overwriting an account selected while the network request was running.
     */
    @Synchronized
    fun saveReloginIfUnchanged(
        user: User,
        password: String,
        expectedAccount: String,
        expectedGeneration: Long
    ): Boolean {
        val currentAccount = prefs.getString("account", "") ?: ""
        if (authGeneration != expectedGeneration || currentAccount != expectedAccount) return false
        saveAuthenticatedUser(user, password)
        return true
    }
}
