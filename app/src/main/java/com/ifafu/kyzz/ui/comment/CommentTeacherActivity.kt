package com.ifafu.kyzz.ui.comment

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityCommentTeacherBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CommentTeacherActivity : BaseActivity<ActivityCommentTeacherBinding>() {

    private val viewModel: CommentViewModel by viewModels()

    override fun createBinding(): ActivityCommentTeacherBinding = ActivityCommentTeacherBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.comment_title)
        binding.btnStart.setOnClickListener { viewModel.startComment() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is CommentViewModel.CommentState.Idle -> {
                    binding.tvStatus.text = "点击下方按钮开始评教"
                    binding.progressBar.visibility = View.GONE
                    binding.btnStart.isEnabled = true
                }
                is CommentViewModel.CommentState.Loading -> {
                    binding.tvStatus.text = getString(R.string.comment_running)
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnStart.isEnabled = false
                }
                is CommentViewModel.CommentState.Success -> {
                    binding.tvStatus.text = state.message
                    binding.progressBar.visibility = View.GONE
                    binding.btnStart.isEnabled = false
                }
                is CommentViewModel.CommentState.Error -> {
                    binding.tvStatus.text = state.message
                    binding.progressBar.visibility = View.GONE
                    binding.btnStart.isEnabled = true
                }
            }
        }
    }
}
