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
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
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
            .addInterceptor(cookieJar.sessionGuard)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // 教务登录是多次 302 的重定向链，readTimeout 只约束单次读，
            // 不设总时长上限时弱网下整条链可以无限拖住全局互斥锁
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

class JavaNetCookieJar internal constructor(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : CookieJar {
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

    private data class CookieKey(val name: String, val domain: String, val path: String)
    private data class StoredCookie(val url: HttpUrl, val cookie: Cookie)

    private val stateLock = Any()
    private val savedCookies = linkedMapOf<CookieKey, StoredCookie>()
    private var generation = 0L
    private val requestGeneration = ThreadLocal<Long>()

    /** One generation spans the entire redirect chain, including its cookie callbacks. */
    internal data class SessionTag(val generation: Long)

    internal fun requestSessionTag(): SessionTag = synchronized(stateLock) { SessionTag(generation) }

    internal fun isSessionCurrent(tag: SessionTag): Boolean = synchronized(stateLock) { tag.generation == generation }

    internal val sessionGuard = Interceptor { chain ->
        val previous = requestGeneration.get()
        val requestTag = chain.request().tag(SessionTag::class.java) ?: requestSessionTag()
        requestGeneration.set(requestTag.generation)
        try {
            chain.proceed(chain.request())
        } finally {
            if (previous == null) requestGeneration.remove() else requestGeneration.set(previous)
        }
    }

    init {
        loadPersistedCookies()
    }

    private fun loadPersistedCookies() {
        val json = prefs.getString(KEY_COOKIES, null) ?: return
        try {
            val arr = JSONArray(json)
            val now = nowMillis()
            for (i in 0 until arr.length()) {
                // A single malformed entry must not hide the remaining valid cookies.
                try {
                    val obj = arr.getJSONObject(i)
                    val url = obj.getString("uri").toHttpUrlOrNull() ?: continue
                    val expiresAt = obj.optLong("expiresAt", 0L)
                    if (expiresAt > 0L && expiresAt <= now) continue
                    val builder = Cookie.Builder()
                        .name(obj.getString("name"))
                        .value(obj.getString("value"))
                        .path(obj.optString("path", "/"))
                    val domain = obj.optString("domain", url.host).ifBlank { url.host }
                    // Old cache records omitted security attributes. Tighten their scope;
                    // a later Set-Cookie restores the server's precise attributes.
                    if (obj.optBoolean("hostOnly", true)) builder.hostOnlyDomain(domain)
                    else builder.domain(domain)
                    if (obj.optBoolean("secure", url.isHttps)) builder.secure()
                    if (obj.optBoolean("httpOnly", false)) builder.httpOnly()
                    if (obj.optBoolean("persistent", expiresAt > 0L) && expiresAt > 0L) {
                        builder.expiresAt(expiresAt)
                    }
                    val cookie = builder.build()
                    savedCookies[key(cookie)] = StoredCookie(url, cookie)
                } catch (_: Exception) {
                    // Ignore only this corrupted record.
                }
            }
        } catch (_: Exception) {}
    }

    private fun key(cookie: Cookie) = CookieKey(cookie.name, cookie.domain, cookie.path)

    private fun pruneExpired(now: Long) {
        savedCookies.entries.removeAll { it.value.cookie.expiresAt <= now }
    }

    private fun persistCookies() {
        try {
            synchronized(stateLock) {
                val arr = JSONArray()
                val now = nowMillis()
                pruneExpired(now)
                for ((url, cookie) in savedCookies.values) {
                    val obj = org.json.JSONObject()
                    obj.put("uri", url.toString())
                    obj.put("name", cookie.name)
                    obj.put("value", cookie.value)
                    obj.put("domain", cookie.domain)
                    obj.put("path", cookie.path)
                    obj.put("expiresAt", if (cookie.persistent) cookie.expiresAt else 0L)
                    obj.put("persistent", cookie.persistent)
                    obj.put("secure", cookie.secure)
                    obj.put("httpOnly", cookie.httpOnly)
                    obj.put("hostOnly", cookie.hostOnly)
                    arr.put(obj)
                }
                prefs.edit().putString(KEY_COOKIES, arr.toString()).apply()
            }
        } catch (_: Exception) {}
    }

    fun clear() {
        synchronized(stateLock) {
            generation += 1L
            savedCookies.clear()
            prefs.edit().remove(KEY_COOKIES).apply()
        }
    }

    /** Compatibility snapshot for legacy WebView callers. Network requests use Cookie.matches. */
    fun getCookieStore(): java.net.CookieStore = synchronized(stateLock) {
        val store = CookieManager(null, CookiePolicy.ACCEPT_ALL).cookieStore
        val now = nowMillis()
        pruneExpired(now)
        for ((url, cookie) in savedCookies.values) {
            val legacy = HttpCookie(cookie.name, cookie.value).apply {
                domain = cookie.domain
                path = cookie.path
                secure = cookie.secure
                isHttpOnly = cookie.httpOnly
                version = 0
                if (cookie.persistent) maxAge = ((cookie.expiresAt - now + 999L) / 1_000L).coerceAtLeast(1L)
            }
            store.add(url.toUri(), legacy)
        }
        store
    }

    fun getCookieString(url: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
    }

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        synchronized(stateLock) {
            // clear() can run while a response is in flight. Its obsolete Set-Cookie
            // must not resurrect the previous account after logout or switching.
            val requestSession = requestGeneration.get()
            if (requestSession != null && requestSession != generation) return
            val now = nowMillis()
            for (cookie in cookies) {
                if (cookie.expiresAt <= now) savedCookies.remove(key(cookie))
                else savedCookies[key(cookie)] = StoredCookie(url, cookie)
            }
            persistCookies()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(stateLock) {
        val requestSession = requestGeneration.get()
        if (requestSession != null && requestSession != generation) {
            return@synchronized emptyList()
        }
        pruneExpired(nowMillis())
        savedCookies.values.map { it.cookie }.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }
}
