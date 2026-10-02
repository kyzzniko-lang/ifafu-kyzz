package com.ifafu.kyzz.data.network

import com.ifafu.kyzz.data.model.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
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
        referer = ""
        viewState = ""
        viewStateGenerator = ""
        lastUrl = ""
    }

    // 已知限制：全局单 Mutex 把所有教务 HTTP 串行化（锁覆盖整个请求周期）。
    // 锁序全链路单向（ReloginHelper.mutex → UserApi.loginMutex → 本 mutex），无死锁面；
    // 但后台 ScoreCheckWorker 的重登链最长可持锁约 90s，期间前台数据请求只能排队，
    // 弱网下表现为"刷新卡住数秒到数十秒"。已评估读写分离方案，因 cookie/viewstate
    // 全局一致性风险与回归成本未实施——改动前必须完整回归登录/评教/选课流程。
    private val mutex = Mutex()

    private val gbkCharset: Charset = Charset.forName("GBK")

    private fun bytesToString(bytes: ByteArray, contentType: String? = null): String =
        String(bytes, resolveCharset(bytes, contentType))

    /**
     * 解析响应字符集：优先响应头 Content-Type 里的 charset；无则扫描响应体
     * 前 2KB 的 ASCII 范围找 <meta charset=...> 声明；都没有才默认 GBK
     * （正方教务是 GBK，但验证码/门户等附属接口可能是 UTF-8，强制 GBK 会乱码）。
     */
    private fun resolveCharset(bytes: ByteArray, contentType: String?): Charset =
        headerCharset(contentType) ?: metaCharset(bytes) ?: gbkCharset

    private fun headerCharset(contentType: String?): Charset? {
        if (contentType.isNullOrEmpty()) return null
        val match = Regex("""charset\s*=\s*["']?\s*([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(contentType) ?: return null
        return charsetOrNull(match.groupValues[1])
    }

    /** 只把 ASCII 可见字节拼成嗅探串：GBK 高位字节直接转字符可能撞出伪 charset 声明。 */
    private fun metaCharset(bytes: ByteArray): Charset? {
        val scanLen = minOf(bytes.size, 2048)
        val ascii = buildString {
            for (i in 0 until scanLen) {
                val b = bytes[i].toInt() and 0xFF
                if (b in 0x20..0x7E) append(b.toChar()) else append(' ')
            }
        }
        val match = Regex("""charset\s*=\s*["']?\s*([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(ascii) ?: return null
        return charsetOrNull(match.groupValues[1])
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }

    data class GetResult(val doc: Document, val viewState: String, val viewStateGenerator: String, val url: String)

    data class ViewStateData(val viewState: String, val viewStateGenerator: String)

    suspend fun get(url: String): Document = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            execute(request)
        }
    }

    /** Atomically applies the page referer and performs the GET. */
    suspend fun getWithReferer(url: String, refererUrl: String): Document =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                referer = refererUrl
                val request = buildRequest(url).get().build()
                execute(request)
            }
        }

    /** Cancelling the caller also cancels the underlying OkHttp request. */
    suspend fun getCancellable(url: String): Document = withContext(Dispatchers.IO) { mutex.withLock {
        val request = buildRequest(url).get().build()
        val result = executeRawCancellable(request)
        if (result.code !in 200..299) {
            throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
        }
        lastUrl = result.url
        referer = lastUrl
        val html = bytesToString(result.bytes, result.contentType)
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
        Jsoup.parse(html).also(::extractViewState)
    } }

    /** Cancellable GET with an atomic referer update, preventing cross-request races. */
    suspend fun getCancellableWithReferer(url: String, refererUrl: String): Document =
        withContext(Dispatchers.IO) { mutex.withLock {
            referer = refererUrl
            val request = buildRequest(url).get().build()
            val result = executeRawCancellable(request)
            if (result.code !in 200..299) {
                throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
            }
            lastUrl = result.url
            referer = lastUrl
            val html = bytesToString(result.bytes, result.contentType)
            checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
            Jsoup.parse(html).also(::extractViewState)
        } }

    /** Cancellable GET that returns request-local navigation state. */
    suspend fun getCancellableWithState(url: String): GetResult = withContext(Dispatchers.IO) { mutex.withLock {
        val request = buildRequest(url).get().build()
        val result = executeRawCancellable(request)
        if (result.code !in 200..299) {
            throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
        }
        lastUrl = result.url
        referer = lastUrl
        val html = bytesToString(result.bytes, result.contentType)
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
        val doc = Jsoup.parse(html)
        extractViewState(doc)
        GetResult(doc, viewState, viewStateGenerator, result.url)
    } }

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
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                lastUrl = response.url
                referer = lastUrl
                val html = bytesToString(bytes, response.contentType)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    data class StringResult(val html: String, val viewState: ViewStateData)

    suspend fun getStringWithViewState(url: String): StringResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                lastUrl = response.url
                referer = lastUrl
                val html = bytesToString(bytes, response.contentType)
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
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                lastUrl = response.url
                referer = lastUrl
                response.bytes
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
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                lastUrl = response.url
                referer = lastUrl
                val html = bytesToString(bytes, response.contentType)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                html
            }
        }
    }

    /** Cancelling the caller also cancels the captcha/image download. */
    suspend fun getBytesCancellable(url: String): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        val request = buildRequest(url).get().build()
        val result = executeRawCancellable(request)
        if (result.code !in 200..299) {
            throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
        }
        lastUrl = result.url
        referer = lastUrl
        result.bytes
    } }

    /** Cancellable POST variant for time-bounded background work. */
    suspend fun postStringCancellable(url: String, formBody: FormBody): String = withContext(Dispatchers.IO) { mutex.withLock {
        val request = buildRequest(url).post(formBody).build()
        val result = executeRawCancellable(request)
        if (result.code !in 200..299) {
            throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
        }
        lastUrl = result.url
        referer = lastUrl
        val html = bytesToString(result.bytes, result.contentType)
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
        html
    } }

    /** Cancellable POST with an atomic referer update. */
    suspend fun postStringCancellableWithReferer(
        url: String,
        formBody: FormBody,
        refererUrl: String
    ): String = withContext(Dispatchers.IO) { mutex.withLock {
        referer = refererUrl
        val request = buildRequest(url).post(formBody).build()
        val result = executeRawCancellable(request)
        if (result.code !in 200..299) {
            throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
        }
        lastUrl = result.url
        referer = lastUrl
        val html = bytesToString(result.bytes, result.contentType)
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
        html
    } }

    /**
     * Submit a form and return the raw response.
     *
     * Legacy pages use JavaScript alert() for both success and failure. Callers
     * that need to distinguish those messages must inspect the raw HTML instead
     * of letting the generic client throw on every alert.
     *
     * Raw 方法不做 HTTP 状态码检查：正方的错误页（ERROR - 出错啦！/系统正忙）
     * 常以非 200 状态返回，调用方需要读取响应体自行分类，这里抛异常会把
     * 可解析的中文错误原因变成裸的 "HTTP 500"。
     */
    suspend fun postStringRaw(url: String, formBody: FormBody): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).post(formBody).build()
            executeRawCancellable(request).let { response ->
                val bytes = response.bytes
                lastUrl = response.url
                referer = lastUrl
                bytesToString(bytes, response.contentType)
            }
        }
    }

    /**
     * Atomic: set referer + post form and return the raw response (no alert check).
     *
     * 与 [postStringRaw] 同语义并补齐原子 referer 更新（含 Origin 头，请求形状
     * 与 [postStringWithReferer] 一致）：评教等旧版页面对成功与失败都以顶层
     * alert 返回（如 alert('完成评价...')），走 [postStringWithReferer] 会在
     * 网络层抛 AlertException，调用方"alert 含成功文案即成功"的判定不可达，
     * 实际成功被兜底成失败。与其它 Raw 方法一致：不做 HTTP 状态码检查。
     */
    suspend fun postStringRawWithReferer(url: String, formBody: FormBody, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val origin = url.let { it.substring(0, it.indexOf('/', it.indexOf("://") + 3).let { idx -> if (idx < 0) it.length else idx }) }
            val request = buildRequest(url).post(formBody).addHeader("Origin", origin).build()
            executeRawCancellable(request).let { response ->
                val bytes = response.bytes
                lastUrl = response.url
                this@HtmlClient.referer = lastUrl
                bytesToString(bytes, response.contentType)
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
            (client.cookieJar as? com.ifafu.kyzz.di.JavaNetCookieJar)?.let { cookieJar ->
                tag(com.ifafu.kyzz.di.JavaNetCookieJar.SessionTag::class.java, cookieJar.requestSessionTag())
            }
            header("User-Agent", USER_AGENT)
            header("Accept", ACCEPT)
            header("Accept-Language", ACCEPT_LANGUAGE)
            header("Upgrade-Insecure-Requests", "1")
            val destination = url.toHttpUrlOrNull()
            val source = referer.toHttpUrlOrNull()
            if (source != null && destination != null && source.scheme == destination.scheme &&
                source.host == destination.host && source.port == destination.port) {
                header("Referer", referer)
            }
        }
    }

    /**
     * GET a URL and return the raw HTML without checking for alerts.
     * Used for navigation setup where alert scripts are expected and should be ignored.
     * 与其它 Raw 方法一致：不做状态码检查，由调用方解析错误页。
     */
    suspend fun getStringRaw(url: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            executeRawCancellable(request).let { response ->
                val bytes = response.bytes
                lastUrl = response.url
                referer = lastUrl
                bytesToString(bytes, response.contentType)
            }
        }
    }

    /** Atomic: set referer + getStringRaw under the same mutex lock */
    suspend fun getStringRawWithReferer(url: String, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val request = buildRequest(url).get().build()
            executeRawCancellable(request).let { response ->
                val bytes = response.bytes
                lastUrl = response.url
                this@HtmlClient.referer = lastUrl
                bytesToString(bytes, response.contentType)
            }
        }
    }

    /** Atomic: set referer + getString under the same mutex lock */
    suspend fun getStringWithReferer(url: String, refererUrl: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            referer = refererUrl
            val request = buildRequest(url).get().build()
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                lastUrl = response.url
                this@HtmlClient.referer = lastUrl
                val html = bytesToString(bytes, response.contentType)
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
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                lastUrl = response.url
                this@HtmlClient.referer = lastUrl
                val html = bytesToString(bytes, response.contentType)
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
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                lastUrl = response.url
                this@HtmlClient.referer = lastUrl
                val html = bytesToString(bytes, response.contentType)
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
            executeRawCancellable(request).let { response ->
                val bytes = response.bytes
                val code = response.code
                val finalUrl = response.url
                // finalUrl 内嵌会话 token（/(token)/...），脱敏后再入日志
                android.util.Log.d("HtmlClient", "POST raw: url=${com.ifafu.kyzz.core.crash.TraceSanitizer.sanitize(finalUrl)}, code=$code, bodyLen=${bytes.size}, hasRedirect=${url != finalUrl}")
                lastUrl = finalUrl
                this@HtmlClient.referer = finalUrl
                bytesToString(bytes, response.contentType)
            }
        }
    }

    private suspend fun execute(request: Request): Document {
        executeRawCancellable(request).let { response ->
            if (response.code !in 200..299) {
                throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
            }
            val bytes = response.bytes
            val html = bytesToString(bytes, response.contentType)
            lastUrl = response.url
            referer = lastUrl
            checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
            val doc = Jsoup.parse(html)
            extractViewState(doc)
            return doc
        }
    }

    private data class RawHttpResult(
        val bytes: ByteArray,
        val code: Int,
        val message: String,
        val url: String,
        val contentType: String? = null
    )

    private suspend fun executeRawCancellable(request: Request): RawHttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    try {
                        response.use {
                            val result = RawHttpResult(
                                bytes = it.body?.bytes() ?: ByteArray(0),
                                code = it.code,
                                message = it.message,
                                url = it.request.url.toString(),
                                contentType = it.header("Content-Type")
                            )
                            val sessionTag = request.tag(com.ifafu.kyzz.di.JavaNetCookieJar.SessionTag::class.java)
                            val cookieJar = client.cookieJar as? com.ifafu.kyzz.di.JavaNetCookieJar
                            if (continuation.isActive) {
                                if (sessionTag != null && cookieJar?.isSessionCurrent(sessionTag) == false) {
                                    continuation.resumeWith(Result.failure(CancellationException("会话已变更，请重新请求")))
                                } else {
                                    continuation.resumeWith(Result.success(result))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }
                }
            })
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
        // 先在 raw HTML 中搜索（兼容非标准 script 标签或 script 内容解析差异）。
        // 必须遍历所有 alert：只取第一个的话，前面函数内的校验提示会挡住
        // 后面真正的页面级提示。
        for (match in regex.findAll(html)) {
            if (!isAlertInsideFunction(html, match.range.first)) {
                // 留痕教务返回的页面级 alert 原文：拦截类文案随部署而变
                //（如"对不起,禁止使用此功能"），出问题时可凭 logcat 定位实际文案
                android.util.Log.i("HtmlClient", "页面级 alert: ${match.groupValues[2]}")
                return Response(false, -1, match.groupValues[2])
            }
        }
        // 再在 Jsoup 解析的 script 标签内容中搜索（兼容 CDATA 等包装）
        val doc = Jsoup.parse(html)
        for (script in doc.select("script")) {
            val content = script.html()
            for (match in regex.findAll(content)) {
                // 需要在原始 HTML 中定位此 script 内的 alert；定位失败（Jsoup
                // 序列化与原文不一致）时无法排除"函数内"可能，该 alert 一律按
                // 函数内处理跳过，避免校验函数的提示被误判成页面级错误。
                val scriptIdx = html.indexOf(content)
                if (scriptIdx < 0) continue
                val absPos = scriptIdx + match.range.first
                if (!isAlertInsideFunction(html, absPos)) {
                    return Response(false, -1, match.groupValues[2])
                }
            }
        }
        return null
    }

    /** 检查 alert 是否在 function 定义内部 (如 function Keykz() { alert(...) }) */
    private fun isAlertInsideFunction(html: String, alertPos: Int): Boolean {
        if (alertPos < 0) return false
        // 只检查当前 script。个人信息等老页面的单个校验函数可能超过 16KB，
        // 不能使用固定长度回看窗口，否则函数尾部的 alert 会被误判为顶层拦截。
        val scriptStart = html.lastIndexOf("<script", alertPos, ignoreCase = true)
        if (scriptStart < 0) return false
        val scriptBodyStart = html.indexOf('>', scriptStart).takeIf { it in 0 until alertPos }
            ?: return false
        val scriptEnd = html.indexOf("</script", scriptBodyStart, ignoreCase = true)
        if (scriptEnd in 0 until alertPos) return false

        // Count lexical scopes, not braces in strings/comments/regular expressions.
        // A finished nested function must not hide the still-open outer function.
        val scopes = mutableListOf<Boolean>()
        val pendingFunctions = mutableListOf<Pair<Int, Boolean>>()
        var parentheses = 0
        var previous = '\u0000'
        var index = scriptBodyStart + 1
        while (index < alertPos) {
            val char = html[index]
            val next = html.getOrNull(index + 1)
            if (char == '\'' || char == '"' || char == '`') {
                val quote = char
                var closed = false
                index += 1
                while (index < alertPos) {
                    if (html[index] == '\\') index += 2
                    else if (html[index++] == quote) { closed = true; break }
                }
                // The regex matched text inside an unfinished string, not executable alert().
                if (!closed) return true
                previous = quote
                continue
            }
            if (char == '/' && (next == '/' || next == '*')) {
                val end = if (next == '/') html.indexOf('\n', index + 2)
                    else html.indexOf("*/", index + 2).let { if (it < 0) it else it + 2 }
                if (end < 0 || end > alertPos) return true
                index = end
                continue
            }
            if (char == '/' && previous in "=(:,[!&|?;{}") {
                index += 1
                var characterClass = false
                var closed = false
                while (index < alertPos) {
                    val token = html[index++]
                    if (token == '\\') index += 1
                    else if (token == '[') characterClass = true
                    else if (token == ']') characterClass = false
                    else if (token == '/' && !characterClass) { closed = true; break }
                }
                if (!closed) return true
                previous = '/'
                continue
            }
            if (html.regionMatches(index, "function", 0, 8) &&
                html.getOrNull(index - 1)?.let { !it.isLetterOrDigit() && it != '_' && it != '$' } != false &&
                html.getOrNull(index + 8)?.let { !it.isLetterOrDigit() && it != '_' && it != '$' } != false) {
                pendingFunctions.add(parentheses to false)
                index += 8
                previous = 'f'
                continue
            }
            if (char == '=' && next == '>') {
                pendingFunctions.add(parentheses to true)
                index += 2
                previous = '>'
                continue
            }
            when (char) {
                '(' -> parentheses += 1
                ')' -> parentheses -= 1
                '{' -> {
                    val declaration = pendingFunctions.indexOfLast { it.first == parentheses }
                    scopes.add(declaration >= 0)
                    if (declaration >= 0) pendingFunctions.removeAt(declaration)
                }
                '}' -> if (scopes.isNotEmpty()) scopes.removeAt(scopes.lastIndex)
                ';' -> pendingFunctions.removeAll { it.second }
            }
            if (!char.isWhitespace()) previous = char
            index += 1
        }
        return scopes.any { it } || pendingFunctions.any { it.second }
    }

    fun throwIfAlert(html: String) {
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
    }

    data class PostResult(val html: String, val url: String)

    suspend fun postWithFollow(url: String, formBody: FormBody): PostResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).post(formBody).build()
            executeRawCancellable(request).let { response ->
                if (response.code !in 200..299) {
                    throw HttpException(response.code, "HTTP ${response.code}: ${response.message}")
                }
                val bytes = response.bytes
                val html = bytesToString(bytes, response.contentType)
                checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
                val finalUrl = response.url
                lastUrl = finalUrl
                referer = finalUrl
                extractViewState(html)
                PostResult(html, finalUrl)
            }
        }
    }


    /** Cancellable login form submission with the final redirect URL. */
    suspend fun postWithFollowCancellable(url: String, formBody: FormBody, refererUrl: String? = null): PostResult = withContext(Dispatchers.IO) { mutex.withLock {
        if (refererUrl != null) referer = refererUrl
        val request = buildRequest(url).post(formBody).build()
        val result = executeRawCancellable(request)
        if (result.code !in 200..299) {
            throw HttpException(result.code, "HTTP ${result.code}: ${result.message}")
        }
        val html = bytesToString(result.bytes, result.contentType)
        checkAlert(html)?.let { throw AlertException.fromAlert(it.message) }
        lastUrl = result.url
        referer = result.url
        extractViewState(html)
        PostResult(html, result.url)
    } }
}
