package com.ifafu.kyzz.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.model.User
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext private val context: Context
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
        securePrefs.edit().putString("password", password).apply()
        prefs.edit().putString("password_backup", password).apply()
        saveAccountProfile(prefs.getString("account", "") ?: "")
        bumpAuthGeneration()
    }

    fun getPassword(): String {
        val password = securePrefs.getString("password", "") ?: ""
        if (password.isNotEmpty()) return password
        return prefs.getString("password_backup", "") ?: ""
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
            remove("saved_accounts")
            apply()
        }
    }

    var host: String
        get() = prefs.getString("host", "http://jwgl.fafu.edu.cn") ?: "http://jwgl.fafu.edu.cn"
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
        prefs.edit().apply {
            putString("account", user.account)
            putString("name", user.name)
            putString("institute", user.institute)
            putString("clas", user.clas)
            putInt("enrollment", user.enrollment)
            putBoolean("isLogin", user.isLogin)
            putString("password_backup", password)
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
