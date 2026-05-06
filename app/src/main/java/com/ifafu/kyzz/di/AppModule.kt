package com.ifafu.kyzz.di

import android.content.Context
import android.os.Environment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileWriter
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val logFile = File(
            context.getExternalFilesDir(null),
            "http_capture_log.txt"
        )
        logFile.writeText("=== iFAFU HTTP Capture Log ===\nStarted: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")

        val cookieJar = JavaNetCookieJar()
        val loggingInterceptor = Interceptor { chain ->
            val request = chain.request()
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())

            val requestHeaders = request.headers.joinToString("\n") { "  ${it.first}: ${it.second}" }
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
            .cookieJar(cookieJar)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

class JavaNetCookieJar : CookieJar {
    private val store = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        val uri = url.toUri()
        cookies.forEach { cookie ->
            val httpCookie = HttpCookie(cookie.name, cookie.value)
            httpCookie.domain = cookie.domain
            httpCookie.path = cookie.path
            httpCookie.secure = cookie.secure
            if (cookie.expiresAt != Long.MAX_VALUE) {
                httpCookie.maxAge = (cookie.expiresAt - System.currentTimeMillis()) / 1000
            }
            store.cookieStore.add(uri, httpCookie)
        }
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
