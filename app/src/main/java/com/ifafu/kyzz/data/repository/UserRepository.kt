package com.ifafu.kyzz.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.model.User
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "UserRepository"
        private const val SECURE_PREFS_FILE = "ifafu_secure"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE)

    private val gson = Gson()

    private var _securePrefs: SharedPreferences? = null
    private var securePrefsFailed = false

    private val securePrefs: SharedPreferences
        get() {
            _securePrefs?.let { return it }
            throw GeneralSecurityException("EncryptedSharedPreferences 不可用")
        }

    private fun initSecurePrefs(): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "EncryptedSharedPreferences 创建失败，尝试重建", e)
            tryRecreateSecurePrefs()
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences 创建异常", e)
            tryRecreateSecurePrefs()
        }
    }

    private fun tryRecreateSecurePrefs(): SharedPreferences? {
        return try {
            context.deleteSharedPreferences(SECURE_PREFS_FILE)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "重建 EncryptedSharedPreferences 也失败，降级到普通模式", e)
            securePrefsFailed = true
            null
        }
    }

    private fun secureGetString(key: String, default: String = ""): String {
        return try {
            securePrefs.getString(key, default) ?: default
        } catch (e: Exception) {
            Log.e(TAG, "读取加密存储失败: $key", e)
            default
        }
    }

    private fun securePutString(key: String, value: String) {
        try {
            securePrefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "写入加密存储失败: $key", e)
        }
    }

    private fun secureRemove(vararg keys: String) {
        try {
            val editor = securePrefs.edit()
            keys.forEach { editor.remove(it) }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "删除加密存储失败", e)
        }
    }

    private fun secureContains(key: String): Boolean {
        return try {
            securePrefs.contains(key)
        } catch (e: Exception) {
            false
        }
    }

    // 一次性迁移：将老版本 prefs 中的 token 移入 securePrefs
    private fun migrateTokenIfNeeded() {
        if (secureContains("token_migrated")) return
        val oldToken = prefs.getString("token", null)
        if (!oldToken.isNullOrEmpty()) {
            securePutString("token", oldToken)
            prefs.edit().remove("token").apply()
        }
        securePutString("token_migrated", "true")
    }

    init {
        _securePrefs = initSecurePrefs()
        if (_securePrefs != null) {
            migrateTokenIfNeeded()
        } else {
            Log.w(TAG, "EncryptedSharedPreferences 完全不可用，密码将以非加密方式存储")
        }
    }

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
        securePutString("token", user.token)
        saveAccountProfile(user.account)
    }

    fun getUser(): User {
        return User(
            account = prefs.getString("account", "") ?: "",
            name = prefs.getString("name", "") ?: "",
            token = secureGetString("token"),
            institute = prefs.getString("institute", "") ?: "",
            clas = prefs.getString("clas", "") ?: "",
            enrollment = prefs.getInt("enrollment", 0),
            isLogin = prefs.getBoolean("isLogin", false)
        )
    }

    fun savePassword(password: String) {
        securePutString("password", password)
        // 备份密码到普通 prefs，EncryptedSharedPreferences 损坏时可用于 relogin
        prefs.edit().putString("password_backup", password).apply()
        saveAccountProfile(prefs.getString("account", "") ?: "")
    }

    fun getPassword(): String {
        val password = secureGetString("password")
        if (password.isNotEmpty()) return password
        // 加密存储读取失败时，尝试从备份读取
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
        secureRemove("password", "token", "token_migrated")
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
        val password = secureGetString("password")
        saveAccountProfileInternal(account, name, password)
    }

    private fun saveAccountProfileInternal(account: String, name: String, password: String) {
        if (account.isEmpty()) return
        val profiles = getAccountProfiles().toMutableList()
        val existing = profiles.indexOfFirst { it.account == account }
        val profile = AccountProfile(account, name, password)
        if (existing >= 0) profiles[existing] = profile else profiles.add(profile)
        securePutString("saved_accounts", gson.toJson(profiles))
    }

    fun getAccountProfiles(): List<AccountProfile> {
        val json = secureGetString("saved_accounts").ifEmpty { return emptyList() }
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
        securePutString("password", profile.password)
        secureRemove("token")
    }

    fun removeAccount(account: String) {
        val profiles = getAccountProfiles().toMutableList()
        profiles.removeAll { it.account == account }
        securePutString("saved_accounts", gson.toJson(profiles))
    }
}
