package com.ifafu.kyzz.ui.score

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.databinding.ActivityScoreBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import android.widget.FrameLayout
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScoreActivity : BaseActivity<ActivityScoreBinding>() {

    private val viewModel: ScoreViewModel by viewModels()

    override fun createBinding(): ActivityScoreBinding = ActivityScoreBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.score_title)
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadScores() }
        binding.btnRetry.setOnClickListener { viewModel.loadScores() }

        viewModel.state.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is ScoreViewModel.ScoreState.Idle -> {}
                is ScoreViewModel.ScoreState.Loading -> showLoading()
                is ScoreViewModel.ScoreState.Success -> showScores(state.scoreTable.scores)
                is ScoreViewModel.ScoreState.Error -> showError(state.message)
            }
        }

        viewModel.loadScores()
    }

    private fun showLoading() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showScores(scores: List<Score>) {
        binding.loadingLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val adapter = ScoreAdapter(scores)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    inner class ScoreAdapter(private val scores: List<Score>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<ScoreAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 14f
                cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }

            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }

            card.addView(content)
            return ViewHolder(card, content)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val score = scores[position]
            holder.content.removeAllViews()

            val topRow = LinearLayout(this@ScoreActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val name = TextView(this@ScoreActivity).apply {
                text = score.courseName
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val scoreValue = TextView(this@ScoreActivity).apply {
                text = if (score.score > 0) "${score.score}" else "--"
                typeface = resources.getFont(R.font.claude_serif)
                textSize = 22f
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
            }

            topRow.addView(name)
            topRow.addView(scoreValue)
            holder.content.addView(topRow)

            val info = TextView(this@ScoreActivity).apply {
                text = "${score.year} ${score.term}  |  ${score.courseType}  |  学分: ${score.studyScore}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }
            holder.content.addView(info)
        }

        override fun getItemCount() = scores.size

        inner class ViewHolder(
            itemView: android.view.View,
            val content: LinearLayout
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }
}
