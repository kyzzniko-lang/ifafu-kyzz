package com.ifafu.kyzz.ui.score

import android.os.Bundle
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityElectiveScoreBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ElectiveScoreActivity : BaseActivity<ActivityElectiveScoreBinding>() {

    override fun createBinding(): ActivityElectiveScoreBinding = ActivityElectiveScoreBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.elective_score_title)
    }
}
