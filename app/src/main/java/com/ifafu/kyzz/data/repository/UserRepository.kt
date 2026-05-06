package com.ifafu.kyzz.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ifafu.kyzz.data.model.User
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)

    fun saveUser(user: User) {
        prefs.edit().apply {
            putString("account", user.account)
            putString("name", user.name)
            putString("token", user.token)
            putString("institute", user.institute)
            putString("clas", user.clas)
            putInt("enrollment", user.enrollment)
            putBoolean("isLogin", user.isLogin)
            commit()
        }
    }

    fun getUser(): User {
        return User(
            account = prefs.getString("account", "") ?: "",
            name = prefs.getString("name", "") ?: "",
            token = prefs.getString("token", "") ?: "",
            institute = prefs.getString("institute", "") ?: "",
            clas = prefs.getString("clas", "") ?: "",
            enrollment = prefs.getInt("enrollment", 0),
            isLogin = prefs.getBoolean("isLogin", false)
        )
    }

    fun savePassword(password: String) {
        prefs.edit().putString("password", password).apply()
    }

    fun getPassword(): String = prefs.getString("password", "") ?: ""

    fun clearUser() {
        prefs.edit().clear().apply()
    }

    var host: String
        get() = prefs.getString("host", "http://jwgl.fafu.edu.cn") ?: "http://jwgl.fafu.edu.cn"
        set(value) = prefs.edit().putString("host", value).apply()
}
