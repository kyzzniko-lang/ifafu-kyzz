package com.ifafu.kyzz.ui.web

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ifafu.kyzz.databinding.ActivityWebBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WebActivity : BaseActivity<ActivityWebBinding>() {

    override fun createBinding(): ActivityWebBinding = ActivityWebBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra("pageTitle") ?: ""
        val url = intent.getStringExtra("loadUrl") ?: ""

        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.webViewClient = WebViewClient()
        if (url.isNotEmpty()) {
            binding.webView.loadUrl(url)
        }
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
