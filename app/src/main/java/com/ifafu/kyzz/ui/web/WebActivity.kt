package com.ifafu.kyzz.ui.web

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ifafu.kyzz.databinding.ActivityWebBinding
import com.ifafu.kyzz.di.JavaNetCookieJar
import com.ifafu.kyzz.ui.base.BaseActivity
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WebActivity : BaseActivity<ActivityWebBinding>() {

    companion object {
        private val ALLOWED_HOSTS = setOf(
            "jwgl.fafu.edu.cn", "fafu.edu.cn",
            "gh-proxy.com", "github.com"
        )
    }

    override fun createBinding(): ActivityWebBinding = ActivityWebBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra("pageTitle") ?: ""
        val url = intent.getStringExtra("loadUrl") ?: ""

        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (url.isNotEmpty() && isUrlAllowed(url)) {
            binding.webView.settings.javaScriptEnabled = true
            binding.webView.settings.domStorageEnabled = true
            binding.webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val redirectUrl = request?.url?.toString() ?: ""
                    if (!isUrlAllowed(redirectUrl)) return true
                    if (redirectUrl.contains("default.aspx") || redirectUrl.contains("default2.aspx")) {
                        view?.stopLoading()
                        android.widget.Toast.makeText(this@WebActivity, "会话已过期，请重新登录", android.widget.Toast.LENGTH_SHORT).show()
                        return true
                    }
                    return false
                }
            }

            syncCookies(url)
            val referer = extractMainUrl(url)
            val headers = mapOf("Referer" to referer)
            binding.webView.loadUrl(url, headers)
        } else if (url.isNotEmpty()) {
            android.widget.Toast.makeText(this, "不支持加载该链接", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun isUrlAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return ALLOWED_HOSTS.any { allowed -> parsed.host == allowed || parsed.host.endsWith(".$allowed") }
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
        webCookieManager.setAcceptThirdPartyCookies(binding.webView, false)

        val parsed = url.toHttpUrlOrNull() ?: return
        val cookies = JavaNetCookieJar.getInstance(this).loadForRequest(parsed)
        // setCookie accepts one Set-Cookie header, not a Cookie request header.
        // Sending all name/value pairs together used to install only the first cookie.
        cookies.forEach { webCookieManager.setCookie(url, it.toString()) }
        webCookieManager.flush()
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
