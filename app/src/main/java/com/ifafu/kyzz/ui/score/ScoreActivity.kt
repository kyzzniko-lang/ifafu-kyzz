package com.ifafu.kyzz.ui.score

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.databinding.ActivityScoreBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScoreActivity : BaseActivity<ActivityScoreBinding>() {

    private val viewModel: ScoreViewModel by viewModels()
    private var selectedYear: String? = null
    private var selectedTerm: String? = null
    private var spinnerReady = false
    private var layoutManagerSet = false

    override fun createBinding(): ActivityScoreBinding = ActivityScoreBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.score_title)
        binding.toolbar.inflateMenu(R.menu.menu_score)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share_score -> { shareScoreCard(); true }
                else -> false
            }
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            getColor(R.color.claude_bg_elevated)
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadScores(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadScores(forceRefresh = true) }

        setupTermButtons()

        viewModel.state.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    if (!spinnerReady) {
                        setupYearSpinner()
                        spinnerReady = true
                    }
                    showScores(state.data)
                }
                is UiState.Cached -> {
                    if (!spinnerReady) {
                        setupYearSpinner()
                        spinnerReady = true
                    }
                    showScores(state.data)
                }
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.loadScores()
    }

    private fun setupYearSpinner() {
        val years = viewModel.getAvailableYears()
        val options = mutableListOf("全部")
        options.addAll(years)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerYear.adapter = adapter

        // 默认选中最新学期
        val latestTerm = viewModel.getLatestTerm()
        if (selectedYear == null && latestTerm != null) {
            selectedYear = latestTerm.first
            selectedTerm = latestTerm.second
        }
        val restoreIndex = if (selectedYear != null) options.indexOf(selectedYear).coerceAtLeast(0) else 0
        binding.spinnerYear.setSelection(restoreIndex)

        binding.spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedYear = if (position == 0) null else options[position]
                viewModel.filter(selectedYear, selectedTerm)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTermButtons() {
        binding.btnTerm1.setOnClickListener {
            selectedTerm = "1"
            viewModel.filter(selectedYear, selectedTerm)
        }
        binding.btnTerm2.setOnClickListener {
            selectedTerm = "2"
            viewModel.filter(selectedYear, selectedTerm)
        }
    }

    private fun showLoading() {
        binding.petLoading.root.startLoading()
        binding.recyclerView.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.petLoading.root.stopLoading()
        binding.recyclerView.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showScores(scores: List<Score>) {
        binding.petLoading.root.stopLoading()
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val validScores = scores.filter { it.score > 0f && it.studyScore > 0f }
        updateStats(validScores)

        val trendData = computeTrendData(validScores)
        val items = mutableListOf<Any>()
        if (trendData != null) items.add(trendData)
        items.addAll(scores)

        if (!layoutManagerSet) {
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            layoutManagerSet = true
        }
        binding.recyclerView.adapter = ScoreAdapter(items, validScores)
    }

    private fun updateStats(scores: List<Score>) {
        if (scores.isEmpty()) {
            binding.statsCard.visibility = View.GONE
            return
        }
        binding.statsCard.visibility = View.VISIBLE

        val totalCredits = scores.sumOf { it.studyScore.toDouble() }.toFloat()
        val weightedSum = scores.sumOf { (it.score * it.studyScore).toDouble() }.toFloat()
        val weightedAvg = if (totalCredits > 0) weightedSum / totalCredits else 0f

        val gpa = if (scores.any { it.scorePoint > 0f }) {
            val gpSum = scores.sumOf { (it.scorePoint * it.studyScore).toDouble() }.toFloat()
            if (totalCredits > 0) gpSum / totalCredits else 0f
        } else {
            if (totalCredits > 0) weightedSum / totalCredits / 25f else 0f
        }

        binding.tvGPA.text = String.format("%.2f", gpa)
        binding.tvWeightedAvg.text = String.format("%.1f", weightedAvg)
        binding.tvTotalCredits.text = String.format("%.1f", totalCredits)
    }

    private fun computeTrendData(scores: List<Score>): List<GpaTrendView.Point>? {
        val grouped = scores.groupBy { "${it.year}\n第${it.term}学期" }
            .toSortedMap()
            .map { (label, list) ->
                val credits = list.sumOf { it.studyScore.toDouble() }.toFloat()
                val gpa = if (list.any { it.scorePoint > 0f }) {
                    val gpSum = list.sumOf { (it.scorePoint * it.studyScore).toDouble() }.toFloat()
                    if (credits > 0) gpSum / credits else 0f
                } else {
                    val wSum = list.sumOf { (it.score * it.studyScore).toDouble() }.toFloat()
                    if (credits > 0) wSum / credits / 25f else 0f
                }
                GpaTrendView.Point(label, gpa)
            }
        return if (grouped.size >= 2) grouped else null
    }

    private fun shareScoreCard() {
        try {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(resources.getColor(R.color.claude_bg, null))
                setPadding(40, 40, 40, 40)
            }
            val title = TextView(this).apply {
                text = "我的成绩单"
                textSize = 22f
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
                setPadding(0, 0, 0, 20)
            }
            container.addView(title)

            val statsText = TextView(this).apply {
                text = "GPA: ${binding.tvGPA.text}  |  加权均分: ${binding.tvWeightedAvg.text}  |  总学分: ${binding.tvTotalCredits.text}"
                textSize = 16f
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                setPadding(0, 0, 0, 30)
            }
            container.addView(statsText)

            val scores = (viewModel.state.value as? UiState.Success)?.data ?: emptyList()
            for (score in scores.filter { it.score > 0 }.take(20)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 6, 0, 6)
                }
                val name = TextView(this).apply {
                    text = score.courseName
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.claude_text_primary, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val scoreVal = TextView(this).apply {
                    text = "${score.score}"
                    textSize = 14f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                }
                row.addView(name); row.addView(scoreVal)
                container.addView(row)
            }

            val w = 800
            val spec = android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.UNSPECIFIED)
            container.measure(spec, android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            container.layout(0, 0, container.measuredWidth, container.measuredHeight)
            val bitmap = android.graphics.Bitmap.createBitmap(container.measuredWidth, container.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
            container.draw(android.graphics.Canvas(bitmap))

            val file = java.io.File(cacheDir, "score_share.png")
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "分享成绩单"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    inner class ScoreAdapter(
        private val items: List<Any>,
        private val validScores: List<Score>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_TREND = 0
        private val TYPE_SCORE = 1

        override fun getItemViewType(position: Int) =
            if (items[position] is List<*>) TYPE_TREND else TYPE_SCORE

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            if (viewType == TYPE_TREND) {
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
                    orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16)
                }
                card.addView(content)
                return TrendVH(card, content)
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
            return ScoreVH(card, content)
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            if (holder is TrendVH) {
                holder.content.removeAllViews()
                val title = TextView(this@ScoreActivity).apply {
                    text = "GPA趋势"
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    textSize = 14f
                }
                holder.content.addView(title)
                val trendView = GpaTrendView(this@ScoreActivity)
                trendView.setData(items[position] as List<GpaTrendView.Point>)
                holder.content.addView(trendView)
            } else if (holder is ScoreVH) {
                val score = items[position] as Score
                holder.content.removeAllViews()
                val topRow = LinearLayout(this@ScoreActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = TextView(this@ScoreActivity).apply {
                    text = score.courseName; setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_text_primary, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val scoreValue = TextView(this@ScoreActivity).apply {
                    text = if (score.score > 0) "${score.score}" else "--"
                    typeface = resources.getFont(R.font.claude_serif); textSize = 22f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                }
                topRow.addView(name); topRow.addView(scoreValue); holder.content.addView(topRow)
                val infoParts = mutableListOf("${score.year} 第${score.term}学期", score.courseType, "学分: ${score.studyScore}")
                if (score.makeupScore > 0) infoParts.add("补考: ${score.makeupScore}")
                val info = TextView(this@ScoreActivity).apply {
                    text = infoParts.joinToString(" | "); setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }
                holder.content.addView(info)
            }
        }

        override fun getItemCount() = items.size

        inner class TrendVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
        inner class ScoreVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }
}
