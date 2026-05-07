package com.ifafu.kyzz.ui.score

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.databinding.ActivityElectiveScoreBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ElectiveScoreActivity : BaseActivity<ActivityElectiveScoreBinding>() {

    private val viewModel: ElectiveScoreViewModel by viewModels()

    override fun createBinding(): ActivityElectiveScoreBinding =
        ActivityElectiveScoreBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.elective_score_title)

        viewModel.state.observe(this) { state ->
            when (state) {
                is ElectiveScoreViewModel.State.Loading -> showLoading()
                is ElectiveScoreViewModel.State.Success -> showScores(state.scores, state.totalCredits)
                is ElectiveScoreViewModel.State.Error -> showError(state.message)
                else -> {}
            }
        }
        viewModel.load()
    }

    private fun showLoading() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.loadingLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
    }

    private fun showScores(scores: List<Score>, totalCredits: Float) {
        binding.loadingLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE

        val allItems = mutableListOf<Any>()
        if (scores.isNotEmpty()) {
            allItems.add("已获选修学分: ${String.format("%.1f", totalCredits)}")
        }
        allItems.addAll(scores)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = Adapter(allItems)
    }

    inner class Adapter(private val items: List<Any>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        override fun getItemViewType(position: Int) = if (items[position] is String) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 12 }
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    textSize = 16f
                }
                return HeaderVH(tv)
            }
            val card = MaterialCardView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 14f; cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16)
            }
            card.addView(content)
            return ItemVH(card, content)
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderVH) {
                (holder.itemView as TextView).text = items[position] as String
            } else if (holder is ItemVH) {
                val score = items[position] as Score
                holder.content.removeAllViews()
                val topRow = LinearLayout(this@ElectiveScoreActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val name = TextView(this@ElectiveScoreActivity).apply {
                    text = score.courseName; setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_text_primary, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val scoreVal = TextView(this@ElectiveScoreActivity).apply {
                    text = if (score.score > 0) "${score.score}" else "--"
                    typeface = resources.getFont(R.font.claude_serif); textSize = 20f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                }
                topRow.addView(name); topRow.addView(scoreVal); holder.content.addView(topRow)
                val info = TextView(this@ElectiveScoreActivity).apply {
                    text = "${score.year} 第${score.term}学期 | 学分: ${score.studyScore}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }
                holder.content.addView(info)
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderVH(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
        inner class ItemVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }
}
