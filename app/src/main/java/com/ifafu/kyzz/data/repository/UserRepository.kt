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

    fun savePassword(password: String) {
        securePrefs.edit().putString("password", password).apply()
        prefs.edit().putString("password_backup", password).apply()
        saveAccountProfile(prefs.getString("account", "") ?: "")
    }

    fun getPassword(): String {
        val password = securePrefs.getString("password", "") ?: ""
        if (password.isNotEmpty()) return password
        return prefs.getString("password_backup", "") ?: ""
    }

    fun clearUser() {
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
        set(value) = prefs.edit().putString("host", value).apply()

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

    fun switchAccount(profile: AccountProfile) {
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

    fun removeAccount(account: String) {
        val profiles = getAccountProfiles().toMutableList()
        profiles.removeAll { it.account == account }
        securePrefs.edit().putString("saved_accounts", gson.toJson(profiles)).apply()
    }
}
