package com.ifafu.kyzz.ui.about

import android.os.Bundle
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
    }
}
