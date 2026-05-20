package com.ifafu.kyzz.ui.score

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.databinding.FragmentScoreBinding
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScoreFragment : Fragment() {

    private var _binding: FragmentScoreBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScoreViewModel by viewModels()
    private var selectedYear: String? = null
    private var selectedTerm: String? = null
    private var spinnerReady = false
    private val activeAnimators = mutableListOf<ValueAnimator>()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selectedYear", selectedYear)
        outState.putString("selectedTerm", selectedTerm)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        selectedYear = savedInstanceState?.getString("selectedYear")
        selectedTerm = savedInstanceState?.getString("selectedTerm")
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            requireContext().getColor(R.color.claude_bg_elevated)
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadScores(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadScores(forceRefresh = true) }

        setupTermButtons()

        viewModel.state.observe(viewLifecycleOwner) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    binding.offlineBanner.root.visibility = View.GONE
                    if (!spinnerReady) {
                        setupYearSpinner()
                        spinnerReady = true
                    }
                    showScores(state.data)
                }
                is UiState.Cached -> {
                    binding.offlineBanner.root.visibility = View.VISIBLE
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

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
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

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
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

        // Animated counter for stats
        binding.gpaRingView.setGpa(gpa)
        animateCounter(binding.tvWeightedAvg, 0f, weightedAvg, "%.1f", 800)
        animateCounter(binding.tvTotalCredits, 0f, totalCredits, "%.1f", 800)
    }

    private fun animateCounter(textView: TextView, from: Float, to: Float, format: String, duration: Long) {
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { textView.text = String.format(format, it.animatedValue as Float) }
        activeAnimators.add(animator)
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                activeAnimators.remove(animator)
            }
        })
        animator.start()
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

    inner class ScoreAdapter(
        private val items: List<Any>,
        private val validScores: List<Score>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_TREND = 0
        private val TYPE_SCORE = 1

        override fun getItemViewType(position: Int) =
            if (items[position] is List<*>) TYPE_TREND else TYPE_SCORE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
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
                holder.content.addView(TextView(requireContext()).apply {
                    text = "GPA趋势"
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    textSize = 14f
                })
                val trendView = GpaTrendView(requireContext())
                trendView.setData(items[position] as List<GpaTrendView.Point>)
                holder.content.addView(trendView)
            } else if (holder is ScoreVH) {
                val score = items[position] as Score
                holder.content.removeAllViews()
                val topRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(TextView(requireContext()).apply {
                    text = score.courseName; setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_text_primary, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                topRow.addView(TextView(requireContext()).apply {
                    text = if (score.score > 0) "${score.score}" else "--"
                    typeface = resources.getFont(R.font.claude_serif); textSize = 22f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                })
                holder.content.addView(topRow)
                val infoParts = mutableListOf("${score.year} 第${score.term}学期", score.courseType, "学分: ${score.studyScore}")
                if (score.makeupScore > 0) infoParts.add("补考: ${score.makeupScore}")
                holder.content.addView(TextView(requireContext()).apply {
                    text = infoParts.joinToString(" | "); setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }
        }

        override fun getItemCount() = items.size

        inner class TrendVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
        inner class ScoreVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeAnimators.toList().forEach { it.cancel() }
        activeAnimators.clear()
        _binding = null
    }
}
