package com.ifafu.kyzz.di

import android.content.Context
import android.content.SharedPreferences
import com.ifafu.kyzz.data.util.ZFVerify
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): JavaNetCookieJar =
        JavaNetCookieJar.getInstance(context)

    @Provides
    @Singleton
    fun provideZFVerify(@ApplicationContext context: Context): ZFVerify = ZFVerify(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        cookieJar: JavaNetCookieJar
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

class JavaNetCookieJar private constructor(context: Context) : CookieJar {
    private val store = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ifafu_cookies", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_COOKIES = "cookies_json"

        @Volatile
        private var instance: JavaNetCookieJar? = null

        fun getInstance(context: Context): JavaNetCookieJar {
            return instance ?: synchronized(this) {
                instance ?: JavaNetCookieJar(context).also { instance = it }
            }
        }
    }

    init {
        loadPersistedCookies()
    }

    private fun loadPersistedCookies() {
        val json = prefs.getString(KEY_COOKIES, null) ?: return
        try {
            val arr = JSONArray(json)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uri = URI(obj.getString("uri"))
                val cookie = HttpCookie(obj.getString("name"), obj.getString("value"))
                cookie.domain = obj.optString("domain", "")
                cookie.path = obj.optString("path", "/")
                val expiresAt = obj.optLong("expiresAt", 0)
                if (expiresAt > 0 && expiresAt < now) continue
                if (expiresAt > 0) {
                    cookie.maxAge = (expiresAt - now) / 1000
                } else {
                    cookie.maxAge = Long.MAX_VALUE
                }
                store.cookieStore.add(uri, cookie)
            }
        } catch (_: Exception) {}
    }

    private fun persistCookies() {
        try {
            val arr = JSONArray()
            val now = System.currentTimeMillis()
            for ((uri, cookies) in savedCookies) {
                synchronized(cookies) {
                    for (cookie in cookies) {
                        val expiresAt = cookie.second
                        if (expiresAt in 1..<now) continue
                        val obj = org.json.JSONObject()
                        obj.put("uri", uri)
                        obj.put("name", cookie.first.name)
                        obj.put("value", cookie.first.value)
                        obj.put("domain", cookie.first.domain)
                        obj.put("path", cookie.first.path)
                        obj.put("expiresAt", expiresAt)
                        arr.put(obj)
                    }
                }
            }
            prefs.edit().putString(KEY_COOKIES, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private val savedCookies = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<HttpCookie, Long>>>()

    fun clear() {
        store.cookieStore.removeAll()
        savedCookies.clear()
        prefs.edit().remove(KEY_COOKIES).apply()
    }

    fun getCookieStore() = store.cookieStore

    fun getCookieString(url: String): String {
        val uri = URI(url)
        return store.cookieStore.get(uri).joinToString("; ") { "${it.name}=${it.value}" }
    }

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        val uri = url.toUri()
        val uriStr = uri.toString()
        val cookieList = savedCookies.getOrPut(uriStr) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(cookieList) {
            for (cookie in cookies) {
                val httpCookie = HttpCookie(cookie.name, cookie.value)
                httpCookie.domain = cookie.domain
                httpCookie.path = cookie.path
                httpCookie.secure = cookie.secure
                val expiresAt = if (cookie.expiresAt != Long.MAX_VALUE) cookie.expiresAt else 0L
                store.cookieStore.add(uri, httpCookie)
                cookieList.removeAll { it.first.name == cookie.name }
                cookieList.add(httpCookie to expiresAt)
            }
        }
        persistCookies()
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val uri = url.toUri()
        val now = System.currentTimeMillis()

        // Build lookup: name+domain+path -> expiresAt
        val expiresMap = mutableMapOf<String, Long>()
        for ((_, cookies) in savedCookies) {
            synchronized(cookies) {
                for ((cookie, expiresAt) in cookies) {
                    if (expiresAt in 1..now) continue
                    expiresMap["${cookie.name}|${cookie.domain}|${cookie.path ?: "/"}"] = expiresAt
                }
            }
        }

        return store.cookieStore.get(uri).mapNotNull { cookie ->
            val key = "${cookie.name}|${cookie.domain}|${cookie.path ?: "/"}"
            val expiresAt = expiresMap[key] ?: 0L
            if (expiresAt in 1..now) return@mapNotNull null
            okhttp3.Cookie.Builder()
                .name(cookie.name)
                .value(cookie.value)
                .domain(cookie.domain)
                .path(cookie.path ?: "/")
                .expiresAt(if (expiresAt > 0) expiresAt else Long.MAX_VALUE)
                .build()
        }
    }
}
