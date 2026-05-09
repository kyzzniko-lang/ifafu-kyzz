package com.ifafu.kyzz.ui.toolbox

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityTrainingPlanBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OnlineRegistrationActivity : BaseActivity<ActivityTrainingPlanBinding>() {

    private val viewModel: OnlineRegistrationViewModel by viewModels()

    override fun createBinding() = ActivityTrainingPlanBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.title = "网上报名"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is OnlineRegistrationViewModel.State.Loading -> showLoading()
                is OnlineRegistrationViewModel.State.Success -> showContent(state.html)
                is OnlineRegistrationViewModel.State.Error -> showError(state.message)
            }
        }

        viewModel.load()
    }

    private fun showLoading() {
        binding.petLoading.root.startLoading()
        binding.scrollContent.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.petLoading.root.stopLoading()
        binding.scrollContent.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showContent(html: String) {
        binding.petLoading.root.stopLoading()
        binding.scrollContent.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.contentLayout.removeAllViews()

        val yearMatch = Regex("报名学年[:\\s]*([^<\\s]+)").find(html)
        val termMatch = Regex("报名学期[:\\s]*(\\d+)").find(html)

        if (yearMatch != null) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                radius = 14f
                cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }

            val info = TextView(this).apply {
                text = "报名学年: ${yearMatch.groupValues[1]} 第${termMatch?.groupValues?.get(1) ?: ""}学期"
                setTextAppearance(R.style.ClaudeBody)
            }
            content.addView(info)

            val idMatch = Regex("idcard_source[^>]*>([^<]+)").find(html)
            if (idMatch != null) {
                val idCard = TextView(this).apply {
                    text = "身份证号: ${idMatch.groupValues[1].trim()}"
                    setTextAppearance(R.style.ClaudeCaption)
                    setPadding(0, 8, 0, 0)
                }
                content.addView(idCard)
            }

            card.addView(content)
            binding.contentLayout.addView(card)
        }

        val projectMatches = Regex("<option[^>]*value=\"([^\"]+)\"[^>]*>([^<]+)</option>").findAll(html)
        val projects = projectMatches.map { it.groupValues[2].trim() }.filter { it.isNotEmpty() && it != "请选择" }.toList()

        if (projects.isNotEmpty()) {
            val title = TextView(this).apply {
                text = "报名项目"
                setTextAppearance(R.style.ClaudeSubtitle)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                setPadding(0, 16, 0, 8)
            }
            binding.contentLayout.addView(title)

            for (project in projects) {
                val card = MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10 }
                    radius = 14f
                    cardElevation = 0f
                    strokeColor = resources.getColor(R.color.claude_border, null)
                    strokeWidth = 1
                    setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                }

                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 14, 20, 14)
                }

                val name = TextView(this).apply {
                    text = project
                    setTextAppearance(R.style.ClaudeBody)
                }
                content.addView(name)
                card.addView(content)
                binding.contentLayout.addView(card)
            }
        }

        if (yearMatch == null && projects.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无报名项目"
                setTextAppearance(R.style.ClaudeBody)
                setPadding(0, 48, 0, 0)
            }
            binding.contentLayout.addView(empty)
        }
    }
}
