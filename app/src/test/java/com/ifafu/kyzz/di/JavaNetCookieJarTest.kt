package com.ifafu.kyzz.di

import android.app.Application
import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JavaNetCookieJarTest {
    private lateinit var jar: JavaNetCookieJar
    private var now = System.currentTimeMillis()
    private val url = "https://example.com/login".toHttpUrl()

    @Before
    fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("ifafu_cookies", Context.MODE_PRIVATE)
            .edit().clear().commit()
        jar = newJar()
    }

    private fun newJar() = JavaNetCookieJar(RuntimeEnvironment.getApplication()) { now }

    private fun cookie(value: String, path: String = "/") = Cookie.Builder()
        .name("session").value(value).hostOnlyDomain("example.com").path(path).build()

    @Test
    fun expiredCookieCannotReappearAsSessionCookie() {
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("session").value("old")
            .hostOnlyDomain("example.com").path("/").expiresAt(now + 1_000L).build()))
        assertEquals(1, jar.loadForRequest(url).size)
        now += 2_000L
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertTrue(newJar().loadForRequest(url).isEmpty())
        assertTrue(jar.getCookieStore().cookies.isEmpty())
    }

    @Test
    fun secureAndHostOnlyAttributesSurviveRestart() {
        val restricted = Cookie.Builder().name("session").value("secret")
            .hostOnlyDomain("example.com").path("/students").secure().httpOnly().build()
        jar.saveFromResponse(url, listOf(restricted))
        val restored = newJar()
        val valid = restored.loadForRequest("https://example.com/students/home".toHttpUrl()).single()
        assertTrue(valid.secure)
        assertTrue(valid.httpOnly)
        assertTrue(valid.hostOnly)
        assertFalse(valid.persistent)
        assertTrue(restored.loadForRequest("http://example.com/students/home".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("https://sub.example.com/students/home".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("https://example.com/other".toHttpUrl()).isEmpty())
    }

    @Test
    fun cookiesWithSameNameAndDifferentPathsRemainIndependent() {
        jar.saveFromResponse(url, listOf(cookie("root"), cookie("nested", "/students")))
        assertEquals(setOf("root", "nested"), jar.loadForRequest("https://example.com/students/home".toHttpUrl()).map { it.value }.toSet())
        assertEquals(listOf("root"), jar.loadForRequest(url).map { it.value })
        assertEquals(setOf("root", "nested"), newJar().loadForRequest("https://example.com/students/home".toHttpUrl()).map { it.value }.toSet())
    }

    @Test
    fun deletionCookieRemovesOnlyMatchingPath() {
        jar.saveFromResponse(url, listOf(cookie("root"), cookie("nested", "/students")))
        val deleted = Cookie.Builder().name("session").value("").hostOnlyDomain("example.com")
            .path("/students").expiresAt(now - 1L).build()
        jar.saveFromResponse(url, listOf(deleted))
        assertEquals(listOf("root"), jar.loadForRequest("https://example.com/students/home".toHttpUrl()).map { it.value })
    }

    @Test
    fun domainCookieStillMatchesSubdomain() {
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("domain").value("shared")
            .domain("example.com").path("/").build()))
        assertEquals("shared", newJar().loadForRequest("https://sub.example.com/home".toHttpUrl()).single().value)
    }

    @Test
    fun responseFromClearedSessionCannotRestoreCookies() {
        jar.saveFromResponse(url, listOf(cookie("previous")))
        val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor(jar.sessionGuard)
            .addInterceptor { chain ->
                jar.clear()
                jar.saveFromResponse(chain.request().url, listOf(cookie("lateResponse")))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertTrue(newJar().loadForRequest(url).isEmpty())
    }

    @Test
    fun requestQueuedBeforeClearCannotUseNewSessionCookies() {
        val previousTag = jar.requestSessionTag()
        jar.clear()
        jar.saveFromResponse(url, listOf(cookie("newSession")))
        var sent = emptyList<Cookie>()
        val client = OkHttpClient.Builder().cookieJar(jar).addInterceptor(jar.sessionGuard)
            .addInterceptor { chain ->
                sent = jar.loadForRequest(chain.request().url)
                jar.saveFromResponse(chain.request().url, listOf(cookie("staleResponse")))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("".toResponseBody()).build()
            }.build()
        client.newCall(Request.Builder().url(url).tag(JavaNetCookieJar.SessionTag::class.java, previousTag).build()).execute().close()
        assertTrue(sent.isEmpty())
        assertEquals("newSession", jar.loadForRequest(url).single().value)
    }
}
