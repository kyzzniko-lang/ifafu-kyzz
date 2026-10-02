package com.ifafu.kyzz.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BugFix1（net-1）回归：评教总表提交的成功响应本身就是顶层
 * alert("完成评价")。postStringWithReferer 在网络层对页面级 alert 抛
 * AlertException，调用方"alert 含'完成评价'即成功"的判定分支不可达，
 * 评教实际成功被兜底成失败（CommentTeacherApi.submitFinalTable）。
 * postStringRawWithReferer 必须取回原文供调用方自行判定，且携带 Referer。
 *
 * 传输层说明：AGP 单元测试的编译 bootclasspath 是 mockable android.jar，
 * 不含 com.sun.net.httpserver（HttpServer 不可用，最初版本因此编译失败），
 * 也不引入 mockwebserver 新依赖。这里用 OkHttp 应用拦截器在本机内短路
 * 请求并合成响应——与真实 HTTP 往返一样完整经过 HtmlClient 的请求构造
 * （Referer/Origin 头）与响应解码（charset 解析、alert 检查）路径。
 */
class BugFix1_PostStringRawWithRefererTest {

    // 不实际联网：请求在应用拦截器处短路，URL 只要合法即可。
    private val baseUrl = "http://127.0.0.1:1"
    private val body = FormBody.Builder().add("Button2", "提  交").build()

    /** 合成 200 响应（UTF-8 text/html），并可选捕获出站请求的 Referer 头。 */
    private fun clientReturning(
        responseBody: String,
        captureReferer: ((String?) -> Unit)? = null
    ): HtmlClient {
        val interceptor = Interceptor { chain ->
            captureReferer?.invoke(chain.request().header("Referer"))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                // 显式设置响应头：Response.Builder.body() 不会把 body 的
                // contentType 同步到 headers，HtmlClient.bytesToString 读的
                // 是 response.header("Content-Type")，缺失时会落到默认 GBK，
                // UTF-8 中文会被解码成乱码（原 HttpServer 版本也是显式设头）。
                .header("Content-Type", "text/html; charset=utf-8")
                .body(responseBody.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        return HtmlClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
    }

    @Test
    fun withRefererVariantThrowsOnAlertResponse() {
        val client = clientReturning("""<html><script>alert('完成评价！')</script></html>""")
        try {
            runBlocking {
                client.postStringWithReferer("$baseUrl/xsjxpj.aspx", body, "$baseUrl/xs_main.aspx")
            }
            fail("postStringWithReferer 应对页面级 alert 抛 AlertException（死代码根因复现）")
        } catch (e: AlertException) {
            assertTrue(e.message!!.contains("完成评价"))
        }
    }

    @Test
    fun rawWithRefererVariantReturnsAlertBodyUntouched() {
        val alertHtml = """<html><script>alert('完成评价！')</script></html>"""
        val client = clientReturning(alertHtml)
        val html = runBlocking {
            client.postStringRawWithReferer("$baseUrl/xsjxpj.aspx", body, "$baseUrl/xs_main.aspx")
        }
        assertEquals(alertHtml, html)
        // submitFinalTable 的成功判定：alert 文案必须能从原文中匹配出来
        val m = Regex("alert\\('(.*?)'\\)").find(html)
        assertTrue(m != null && m.groupValues[1].contains("完成评价"))
    }

    @Test
    fun rawWithRefererVariantSendsRefererHeader() {
        val refererSeen = arrayOfNulls<String>(1)
        val client = clientReturning("<html></html>") { refererSeen[0] = it }
        runBlocking {
            client.postStringRawWithReferer("$baseUrl/xsjxpj.aspx", body, "$baseUrl/xs_main.aspx?xh=123")
        }
        assertEquals("$baseUrl/xs_main.aspx?xh=123", refererSeen[0])
    }

    @Test
    fun rawWithRefererVariantReturnsPlainPage() {
        val client = clientReturning("<html><body>现代教学管理信息系统</body></html>")
        val html = runBlocking {
            client.postStringRawWithReferer("$baseUrl/xsjxpj.aspx", body, "$baseUrl/xs_main.aspx")
        }
        assertTrue(html.contains("现代教学管理信息系统"))
    }
}
