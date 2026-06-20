package com.ifafu.kyzz.data.network

import com.ifafu.kyzz.data.model.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HtmlClient @Inject constructor(
    private val client: OkHttpClient
) {
    @Volatile var viewState: String = ""
        private set
    @Volatile var viewStateGenerator: String = ""
        private set
    @Volatile var lastUrl: String = ""
        private set

    @Volatile private var referer: String = ""

    fun clearCookies() {
        (client.cookieJar as? com.ifafu.kyzz.di.JavaNetCookieJar)?.clear()
    }

    private val mutex = Mutex()

    private val gbkCharset: Charset = Charset.forName("GBK")

    private fun bytesToString(bytes: ByteArray): String = String(bytes, gbkCharset)

    data class GetResult(val doc: Document, val viewState: String, val viewStateGenerator: String, val url: String)

    data class ViewStateData(val viewState: String, val viewStateGenerator: String)

    suspend fun get(url: String): Document = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            execute(request)
        }
    }

    suspend fun getWithState(url: String): GetResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            val doc = execute(request)
            GetResult(doc, viewState, viewStateGenerator, lastUrl)
        }
    }

    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                referer = lastUrl
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    data class StringResult(val html: String, val viewState: ViewStateData)

    suspend fun getStringWithViewState(url: String): StringResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                referer = lastUrl
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                val doc = Jsoup.parse(html)
                val vs = doc.select("input[name=__VIEWSTATE]").firstOrNull()
                val vsg = doc.select("input[name=__VIEWSTATEGENERATOR]").firstOrNull()
                val state = ViewStateData(
                    viewState = vs?.attr("value")?.trim() ?: "",
                    viewStateGenerator = vsg?.attr("value")?.trim() ?: ""
                )
                extractViewState(doc) // keep global state in sync for legacy callers
                StringResult(html, state)
            }
        }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                lastUrl = response.request.url.toString()
                referer = lastUrl
                response.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    suspend fun post(url: String, formBody: FormBody): Document = withContext(Dispatchers.IO) {
        mutex.withLock {
            val origin = url.let { it.substring(0, it.indexOf('/', it.indexOf("://") + 3).let { idx -> if (idx < 0) it.length else idx }) }
            val request = buildRequest(url).post(formBody).addHeader("Origin", origin).build()
            execute(request)
        }
    }

    suspend fun postString(url: String, formBody: FormBody): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).post(formBody).build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                referer = lastUrl
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    fun buildFormBodyWithViewState(vararg pairs: Pair<String, String>, state: ViewStateData): FormBody {
        val builder = FormBody.Builder(gbkCharset)
            .add("__VIEWSTATE", state.viewState)
        if (state.viewStateGenerator.isNotEmpty()) {
            builder.add("__VIEWSTATEGENERATOR", state.viewStateGenerator)
        }
        for ((key, value) in pairs) {
            builder.add(key, value)
        }
        return builder.build()
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en-US;q=0.6,en;q=0.5"
    }

    private fun buildRequest(url: String): Request.Builder {
        return Request.Builder().url(url).apply {
            header("User-Agent", USER_AGENT)
            header("Accept", ACCEPT)
            header("Accept-Language", ACCEPT_LANGUAGE)
            header("Upgrade-Insecure-Requests", "1")
            if (referer.isNotEmpty()) {
                header("Referer", referer)
            }
        }
    }

    /**
     * GET a URL and return the raw HTML without checking for alerts.
     * Used for navigation setup where alert scripts are expected and should be ignored.
     */
    suspend fun getStringRaw(url: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                referer = lastUrl
                bytesToString(bytes)
            }
        }
    }

    /** Atomic: set referer + getStringRaw under the same mutex lock */
    suspend fun getStringRawWithReferer(url: String, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                this@HtmlClient.referer = lastUrl
                bytesToString(bytes)
            }
        }
    }

    /** Atomic: set referer + getString under the same mutex lock */
    suspend fun getStringWithReferer(url: String, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                this@HtmlClient.referer = lastUrl
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    /** Atomic: set referer + postString under the same mutex lock */
    suspend fun postStringWithReferer(url: String, formBody: FormBody, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val origin = url.let { it.substring(0, it.indexOf('/', it.indexOf("://") + 3).let { idx -> if (idx < 0) it.length else idx }) }
            val request = buildRequest(url).post(formBody).addHeader("Origin", origin).build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                this@HtmlClient.referer = lastUrl
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    /** Atomic: set referer + post raw RequestBody under the same mutex lock */
    suspend fun postStringWithReferer(url: String, body: okhttp3.RequestBody, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val origin = url.let { it.substring(0, it.indexOf('/', it.indexOf("://") + 3).let { idx -> if (idx < 0) it.length else idx }) }
            val request = buildRequest(url).post(body).addHeader("Origin", origin).build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                lastUrl = response.request.url.toString()
                this@HtmlClient.referer = lastUrl
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    /** Atomic: set referer + post raw bytes under the same mutex lock, no alert check */
    suspend fun postRawBytesWithReferer(url: String, rawBytes: ByteArray, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val origin = url.let { it.substring(0, it.indexOf('/', it.indexOf("://") + 3).let { idx -> if (idx < 0) it.length else idx }) }
            val body = rawBytes.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = buildRequest(url).post(body).addHeader("Origin", origin).build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val code = response.code
                val finalUrl = response.request.url.toString()
                android.util.Log.d("HtmlClient", "POST raw: url=$finalUrl, code=$code, bodyLen=${bytes.size}, hasRedirect=${url != finalUrl}")
                lastUrl = finalUrl
                this@HtmlClient.referer = finalUrl
                bytesToString(bytes)
            }
        }
    }

    private fun execute(request: Request): Document {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val html = bytesToString(bytes)
            lastUrl = response.request.url.toString()
            referer = lastUrl
            checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
            val doc = Jsoup.parse(html)
            extractViewState(doc)
            return doc
        }
    }

    class HttpException(val statusCode: Int, override val message: String) : Exception(message)

    fun extractViewState(html: String) {
        extractViewState(Jsoup.parse(html))
    }

    private fun extractViewState(doc: Document) {
        val vs = doc.select("input[name=__VIEWSTATE]").firstOrNull()
        val vsg = doc.select("input[name=__VIEWSTATEGENERATOR]").firstOrNull()
        viewState = vs?.attr("value")?.trim() ?: ""
        viewStateGenerator = vsg?.attr("value")?.trim() ?: ""
    }

    fun parseViewState(html: String): ViewStateData {
        val doc = Jsoup.parse(html)
        val vs = doc.select("input[name=__VIEWSTATE]").firstOrNull()
        val vsg = doc.select("input[name=__VIEWSTATEGENERATOR]").firstOrNull()
        return ViewStateData(
            viewState = vs?.attr("value")?.trim() ?: "",
            viewStateGenerator = vsg?.attr("value")?.trim() ?: ""
        )
    }

    fun buildViewStateFormBody(state: ViewStateData): FormBody.Builder {
        val builder = FormBody.Builder(gbkCharset)
            .add("__VIEWSTATE", state.viewState)
        if (state.viewStateGenerator.isNotEmpty()) {
            builder.add("__VIEWSTATEGENERATOR", state.viewStateGenerator)
        }
        return builder
    }

    fun buildFormBody(vararg pairs: Pair<String, String>): FormBody {
        val builder = FormBody.Builder(gbkCharset)
        for ((key, value) in pairs) {
            builder.add(key, value)
        }
        return builder.build()
    }

    fun buildViewStateFormBody(): FormBody.Builder {
        val builder = FormBody.Builder(gbkCharset)
            .add("__VIEWSTATE", viewState)
        if (viewStateGenerator.isNotEmpty()) {
            builder.add("__VIEWSTATEGENERATOR", viewStateGenerator)
        }
        return builder
    }

    data class SelectOptions(val options: List<String>, val selectedValue: String?)

    fun parseSelectOptions(html: String, selectId: String): SelectOptions {
        val doc = Jsoup.parse(html)
        val select = doc.select("select[id=$selectId]").firstOrNull()
            ?: doc.select("select[name=$selectId]").firstOrNull()
            ?: return SelectOptions(emptyList(), null)
        val options = select.select("option")
        val values = options.map { it.attr("value") }
        val selected = options.firstOrNull { it.hasAttr("selected") }?.attr("value")
        return SelectOptions(values, selected)
    }

    fun setReferer(url: String) {
        referer = url
    }

    fun checkErrorPage(html: String): Response? {
        if (html.contains("<title>ERROR - 出错啦！</title>") || html.contains("ERROR - 出错啦！")) {
            val regex = Regex("""错误原因：(.*?)</""")
            val match = regex.find(html)
            if (match != null) {
                return Response(false, -1, match.groupValues[1].trim())
            }
            return Response(false, -1, "教务系统异常：出错啦！")
        }
        return null
    }

    fun checkAlert(html: String): Response? {
        val regex = Regex("""(?is)(?:window\.)?alert\s*\(\s*(['"])(.*?)\1\s*\)""")
        // 先在 raw HTML 中搜索（兼容非标准 script 标签或 script 内容解析差异）
        val rawMatch = regex.find(html)
        if (rawMatch != null && !isAlertInsideFunction(html, rawMatch.range.first)) {
            return Response(false, -1, rawMatch.groupValues[2])
        }
        // 再在 Jsoup 解析的 script 标签内容中搜索（兼容 CDATA 等包装）
        val doc = Jsoup.parse(html)
        for (script in doc.select("script")) {
            val content = script.html()
            val match = regex.find(content)
            if (match != null) {
                // 需要在原始 HTML 中定位此 script 内的 alert
                val scriptIdx = html.indexOf(content)
                if (scriptIdx >= 0) {
                    val absPos = scriptIdx + match.range.first
                    if (isAlertInsideFunction(html, absPos)) continue
                }
                return Response(false, -1, match.groupValues[2])
            }
        }
        return null
    }

    /** 检查 alert 是否在 function 定义内部 (如 function Keykz() { alert(...) }) */
    private fun isAlertInsideFunction(html: String, alertPos: Int): Boolean {
        if (alertPos < 0) return false
        val start = maxOf(0, alertPos - 400)
        val before = html.substring(start, alertPos)
        // 从 alert 位置向前找 function 关键字，确保没有被 } 闭合
        val lastBrace = before.lastIndexOf('}')
        val lastFunction = before.lastIndexOf("function")
        return lastFunction >= 0 && lastFunction > lastBrace
    }

    fun throwIfAlert(html: String) {
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
    }

    data class PostResult(val html: String, val url: String)

    suspend fun postWithFollow(url: String, formBody: FormBody): PostResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).post(formBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val html = bytesToString(bytes)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                val finalUrl = response.request.url.toString()
                lastUrl = finalUrl
                referer = finalUrl
                extractViewState(html)
                PostResult(html, finalUrl)
            }
        }
    }
}
