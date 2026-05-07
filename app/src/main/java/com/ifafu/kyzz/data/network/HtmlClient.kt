package com.ifafu.kyzz.data.network

import com.ifafu.kyzz.data.model.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    private val mutex = Mutex()

    data class GetResult(val doc: Document, val viewState: String, val viewStateGenerator: String, val url: String)

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
                String(bytes, charset("GBK"))
            }
        }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).get().build()
            client.newCall(request).execute().use { response ->
                response.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    suspend fun post(url: String, formBody: FormBody): Document = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).post(formBody).build()
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
                String(bytes, charset("GBK"))
            }
        }
    }

    private fun buildRequest(url: String): Request.Builder {
        return Request.Builder().url(url).apply {
            if (referer.isNotEmpty()) {
                header("Referer", referer)
            }
        }
    }

    private fun execute(request: Request): Document {
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val html = String(bytes, charset("GBK"))
            lastUrl = response.request.url.toString()
            referer = lastUrl
            extractViewState(html)
            return Jsoup.parse(html)
        }
    }

    fun extractViewState(html: String) {
        val doc = Jsoup.parse(html)
        val vs = doc.select("input[name=__VIEWSTATE]").first()
        val vsg = doc.select("input[name=__VIEWSTATEGENERATOR]").first()
        viewState = vs?.attr("value")?.replace(" ", "")?.replace("\n", "") ?: ""
        viewStateGenerator = vsg?.attr("value") ?: ""
    }

    fun buildFormBody(vararg pairs: Pair<String, String>): FormBody {
        val builder = FormBody.Builder(charset("GBK"))
        for ((key, value) in pairs) {
            builder.add(key, value)
        }
        return builder.build()
    }

    fun buildViewStateFormBody(): FormBody.Builder {
        return FormBody.Builder(charset("GBK"))
            .add("__VIEWSTATE", viewState)
            .add("__VIEWSTATEGENERATOR", viewStateGenerator)
    }

    fun checkAlert(html: String): Response? {
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script")
        for (script in scripts) {
            val content = script.html()
            val regex = Regex("alert\\('(.*?)'\\)")
            val match = regex.find(content)
            if (match != null) {
                return Response(false, -1, match.groupValues[1])
            }
        }
        return null
    }

    data class PostResult(val html: String, val url: String)

    suspend fun postWithFollow(url: String, formBody: FormBody): PostResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val request = buildRequest(url).post(formBody).build()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val html = String(bytes, charset("GBK"))
                val finalUrl = response.request.url.toString()
                lastUrl = finalUrl
                referer = finalUrl
                extractViewState(html)
                PostResult(html, finalUrl)
            }
        }
    }
}
