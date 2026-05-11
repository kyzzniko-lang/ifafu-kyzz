package com.ifafu.kyzz.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityAboutBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    private val viewModel: AboutViewModel by viewModels()

    override fun createBinding(): ActivityAboutBinding = ActivityAboutBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.about_title)
        viewModel.loadVersion(this)
        viewModel.versionName.observe(this) { version ->
            binding.tvVersion.text = getString(R.string.about_version, version)
        }

        binding.tvAboutDesc.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/kyzzniko-lang/ifafu-kyzz")))
        }
    }
}
