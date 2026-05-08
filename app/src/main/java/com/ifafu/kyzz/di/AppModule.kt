package com.ifafu.kyzz.di

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.ifafu.kyzz.data.util.ZFVerify
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.io.FileWriter
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
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
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        val logFile = File(externalDir, "http_capture_log.txt")
        logFile.writeText("=== iFAFU HTTP Capture Log ===\nStarted: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")

        val cookieJar1 = cookieJar
        val loggingInterceptor = Interceptor { chain ->
            val request = chain.request()
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

            val requestHeaders = request.headers.joinToString("\n") { (name, value) ->
                val displayValue = if (name.equals("Authorization", ignoreCase = true)) "[REDACTED]" else value
                "  $name: $displayValue"
            }
            val requestBody = try {
                val buf = okio.Buffer()
                request.body?.writeTo(buf)
                buf.readUtf8()
            } catch (_: Exception) { "(cannot read body)" }

            val sb = StringBuilder()
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("[$timestamp] >>> ${request.method} ${request.url}")
            sb.appendLine("REQUEST HEADERS:")
            sb.appendLine(requestHeaders)
            if (requestBody.isNotEmpty() && requestBody != "(cannot read body)") {
                sb.appendLine("REQUEST BODY:")
                sb.appendLine(requestBody.chunked(500).first())
            }

            val response = chain.proceed(request)

            val responseTimestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            sb.appendLine("[$responseTimestamp] <<< ${response.code} ${response.message}")
            sb.appendLine("FINAL URL: ${response.request.url}")
            sb.appendLine("RESPONSE HEADERS:")
            response.headers.forEach { (name, value) ->
                sb.appendLine("  $name: $value")
            }

            val responseBody = response.peekBody(512 * 1024).string()
            val preview = if (responseBody.length > 2000) responseBody.substring(0, 2000) + "\n... (truncated)" else responseBody
            sb.appendLine("RESPONSE BODY (first 2000 chars):")
            sb.appendLine(preview)
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine()

            synchronized(logFile) {
                val writer = FileWriter(logFile, true)
                writer.use { it.write(sb.toString()) }
            }

            response
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar1)
            .addInterceptor(loggingInterceptor)
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
            prefs.edit().putString(KEY_COOKIES, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private val savedCookies = mutableMapOf<String, MutableList<Pair<HttpCookie, Long>>>()

    fun getCookieStore() = store.cookieStore

    fun getCookieString(url: String): String {
        val uri = URI(url)
        return store.cookieStore.get(uri).joinToString("; ") { "${it.name}=${it.value}" }
    }

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        val uri = url.toUri()
        val uriStr = uri.toString()
        val cookieList = savedCookies.getOrPut(uriStr) { mutableListOf() }
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
        persistCookies()
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val uri = url.toUri()
        return store.cookieStore.get(uri).map { cookie ->
            okhttp3.Cookie.Builder()
                .name(cookie.name)
                .value(cookie.value)
                .domain(cookie.domain)
                .path(cookie.path ?: "/")
                .build()
        }
    }
}
