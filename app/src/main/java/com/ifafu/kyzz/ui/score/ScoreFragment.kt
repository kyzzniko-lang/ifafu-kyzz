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
import com.ifafu.kyzz.data.util.GpaCalculator
import com.ifafu.kyzz.databinding.FragmentScoreBinding
import com.ifafu.kyzz.ui.base.UiState
import com.ifafu.kyzz.data.model.PetState
import com.ifafu.kyzz.data.repository.PetRepository
import com.ifafu.kyzz.ui.pet.PetLottieManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ScoreFragment : Fragment() {

    @Inject lateinit var petRepository: PetRepository

    private var _binding: FragmentScoreBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScoreViewModel by viewModels()
    private var selectedYear: String? = null
    private var selectedTerm: String? = null
    private var spinnerReady = false
    private val activeAnimators = mutableListOf<ValueAnimator>()
    private var layoutManagerSet = false

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
                    binding.offlineBanner.tvOfflineMessage.text = state.staleMessage
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
                updateSemesterLabel()
                viewModel.filter(selectedYear, selectedTerm)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTermButtons() {
        binding.btnTerm1.setOnClickListener {
            selectedTerm = "1"
            updateSemesterLabel()
            viewModel.filter(selectedYear, selectedTerm)
        }
        binding.btnTerm2.setOnClickListener {
            selectedTerm = "2"
            updateSemesterLabel()
            viewModel.filter(selectedYear, selectedTerm)
        }
    }

    private fun updateSemesterLabel() {
        binding.tvSemesterLabel.visibility = View.VISIBLE
        binding.tvSemesterLabel.text = when {
            selectedYear != null && selectedTerm != null -> "$selectedYear · 第${selectedTerm}学期"
            selectedYear != null -> "$selectedYear · 全部学期"
            else -> "全部学年"
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
        
        val pet = petRepository.loadPet()
        PetLottieManager.applyAnimation(
            requireContext(),
            binding.ivErrorPet,
            PetState.SAD,
            pet.petType
        )
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
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
            layoutManagerSet = true
        }
        binding.recyclerView.adapter = ScoreAdapter(items)
    }

    private fun updateStats(scores: List<Score>) {
        if (scores.isEmpty()) {
            binding.statsCard.visibility = View.GONE
            return
        }
        binding.statsCard.visibility = View.VISIBLE

        val totalCredits = scores.sumOf { it.studyScore.toDouble() }.toFloat()
        val weightedAvg = GpaCalculator.computeWeightedAvg(scores)

        // 算术平均分（不考虑学分权重）与已出成绩科目数
        val avgScore = GpaCalculator.computeAvgScore(scores)
        val subjectCount = scores.size

        val gpa = GpaCalculator.computeGpa(scores)

        // Animated counter for stats
        binding.gpaRingView.setGpa(gpa)
        animateCounter(binding.tvWeightedAvg, 0f, weightedAvg, "%.1f", 800)
        animateCounter(binding.tvTotalCredits, 0f, totalCredits, "%.1f", 800)
        animateCounter(binding.tvAvgScore, 0f, avgScore, "%.1f", 800)
        binding.tvSubjectCount.text = subjectCount.toString()
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
                GpaTrendView.Point(label, GpaCalculator.computeGpa(list))
            }
        return if (grouped.size >= 2) grouped else null
    }

    inner class ScoreAdapter(
        private val items: List<Any>
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
                @Suppress("UNCHECKED_CAST")
                val points = items[position] as List<GpaTrendView.Point>
                trendView.setData(points)
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
                // NEW 标签：成绩首次见到时间在最近 48 小时内时显示，常驻不因刷新丢失。
                if (score.isNewWithin()) {
                    val dp = resources.displayMetrics.density
                    topRow.addView(TextView(requireContext()).apply {
                        text = "NEW"
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(resources.getColor(R.color.claude_terracotta, null))
                        setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (10 * dp).toInt() }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = 10f * dp
                            setColor(0x1AD4724A.toInt()) // terracotta 半透明底
                            setStroke((1 * dp).toInt(), resources.getColor(R.color.claude_terracotta, null))
                        }
                    })
                }
                topRow.addView(TextView(requireContext()).apply {
                    text = if (score.score > 0) "${score.score}" else "--"
                    typeface = resources.getFont(R.font.claude_serif); textSize = 22f
                    // 分数着色：及格（>=60）绿色，不及格（<60）红色，未出分灰色
                    val scoreColor = when {
                        score.score <= 0 -> R.color.claude_text_hint
                        score.score < 60 -> R.color.claude_error
                        else -> R.color.claude_success
                    }
                    setTextColor(resources.getColor(scoreColor, null))
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
