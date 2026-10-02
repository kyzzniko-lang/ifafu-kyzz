package com.ifafu.kyzz.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HtmlClientLifecycleTest {
    @Test
    fun cancellingOrdinaryGetCancelsCallAndReleasesRequestMutex() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/blocked") {
                activeCall.set(chain.call())
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("<html>ok</html>".toResponseBody()).build()
        }.build()
        val client = HtmlClient(http)
        val blocked = launch(Dispatchers.Default) { client.get("http://example.com/blocked") }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            withTimeout(3_000L) {
                blocked.cancelAndJoin()
                assertEquals("<html>ok</html>", client.getString("http://example.com/next"))
            }
            assertTrue(activeCall.get().isCanceled())
            assertEquals("http://example.com/next", client.lastUrl)
        } finally {
            release.countDown()
            blocked.cancelAndJoin()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun quotedCharsetHeaderPreservesChineseResponse() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Content-Type", "text/html; charset=\"UTF-8\"")
                .body("中文成绩".toByteArray(Charsets.UTF_8).toResponseBody()).build()
        }.build()
        assertEquals("中文成绩", HtmlClient(http).getString("http://example.com/scores"))
    }

    @Test
    fun implicitRefererNeverLeaksSessionToAnotherOrigin() = runBlocking {
        var seen: String? = "unset"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen = chain.request().header("Referer")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("ok".toResponseBody()).build()
        }.build()
        val client = HtmlClient(http)
        client.setReferer("https://old.example.com/(secret)/xs_main.aspx")
        client.getString("https://new.example.com/login")
        assertNull(seen)
    }
}
