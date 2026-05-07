package com.ifafu.kyzz.ui.web

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ifafu.kyzz.databinding.ActivityWebBinding
import com.ifafu.kyzz.di.JavaNetCookieJar
import com.ifafu.kyzz.ui.base.BaseActivity
import java.net.URI

class WebActivity : BaseActivity<ActivityWebBinding>() {

    override fun createBinding(): ActivityWebBinding = ActivityWebBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra("pageTitle") ?: ""
        val url = intent.getStringExtra("loadUrl") ?: ""

        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val redirectUrl = request?.url?.toString() ?: ""
                if (redirectUrl.contains("default.aspx") || redirectUrl.contains("default2.aspx")) {
                    view?.stopLoading()
                    android.widget.Toast.makeText(this@WebActivity, "会话已过期，请重新登录", android.widget.Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }
        }

        if (url.isNotEmpty()) {
            syncCookies(url)
            val referer = extractMainUrl(url)
            val headers = mapOf("Referer" to referer)
            binding.webView.loadUrl(url, headers)
        }
    }

    private fun extractMainUrl(url: String): String {
        return try {
            val uri = URI(url)
            val tokenMatch = Regex("\\((.+?)\\)/").find(url)
            val token = tokenMatch?.groupValues?.get(1) ?: ""
            "${uri.scheme}://${uri.host}/(${token})/xs_main.aspx?xh="
        } catch (e: Exception) {
            ""
        }
    }

    private fun syncCookies(url: String) {
        val webCookieManager = android.webkit.CookieManager.getInstance()
        webCookieManager.setAcceptCookie(true)
        webCookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        val host = try { URI(url).host ?: "" } catch (_: Exception) { "" }
        if (host.isEmpty()) return

        val cookieStore = JavaNetCookieJar.getInstance(this).getCookieStore()
        val httpUri = URI("http://$host")
        val httpsUri = URI("https://$host")

        val cookies = mutableListOf<java.net.HttpCookie>()
        cookies.addAll(cookieStore.get(httpUri))
        cookies.addAll(cookieStore.get(httpsUri))

        val cookieString = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        if (cookieString.isNotEmpty()) {
            webCookieManager.setCookie(url, cookieString)
            webCookieManager.setCookie("http://$host", cookieString)
        }
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.removeJavascriptInterface("android")
        binding.webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        binding.webView.clearHistory()
        (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }
}
