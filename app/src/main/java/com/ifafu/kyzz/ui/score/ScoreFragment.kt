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
    // Adapter 跨状态发射复用：每次 new 会让后台刷新完成时列表跳回顶部
    private var scoreAdapter: ScoreAdapter? = null

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
        // 先注册 listener 再 setSelection：反过来的话初始选中不触发 onItemSelected，
        // 页面会显示"最新学年"但列表仍是未过滤的全量，点任一学期按钮后列表骤减。
        // 恢复的学年已不在选项里(缓存被清/换账号)时回退"全部"，避免 spinner 显示
        // 与实际过滤状态脱节。
        val restoreIndex = if (selectedYear != null) options.indexOf(selectedYear) else 0
        if (restoreIndex < 0) selectedYear = null

        binding.spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedYear = if (position == 0) null else options[position]
                updateSemesterLabel()
                viewModel.filter(selectedYear, selectedTerm)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spinnerYear.setSelection(restoreIndex.coerceAtLeast(0))
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
            // 只选了学期未选学年：不能笼统显示"全部学年"，要把学期信息带上
            selectedTerm != null -> "全部学年 · 第${selectedTerm}学期"
            else -> "全部学年"
        }
        // 两个学期按钮的选中态：按钮在 MaterialButtonToggleGroup(singleSelection)内，
        // isChecked 由组渲染选中样式；未选学期时两个都不勾
        binding.btnTerm1.isChecked = selectedTerm == "1"
        binding.btnTerm2.isChecked = selectedTerm == "2"
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

        // 统计口径必须复用 GpaCalculator.valid：自建 `score > 0` 过滤会把
        // "0 分挂科+补考及格"的课在进计算器前删掉，与成绩单页（直接交原始
        // 列表）的总学分/GPA/科目数互相矛盾。
        val validScores = GpaCalculator.valid(scores)
        updateStats(validScores)

        // 趋势图用全量成绩计算：它本质是跨学期视图，若跟随筛选器，默认选中
        // "最新学期"时会先渲染后消失（闪一下就不见），用户必须手动选"全部"才能看到。
        val trendData = computeTrendData(GpaCalculator.valid(viewModel.getAllScores()))
        val items = mutableListOf<Any>()
        if (trendData != null) items.add(trendData)
        items.addAll(scores)

        if (!layoutManagerSet) {
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
            layoutManagerSet = true
        }
        // 复用同一 Adapter 实例（LayoutManager 已复用），数据更新走 updateData，
        // notifyDataSetChanged 不重置 RecyclerView 的滚动位置
        val adapter = scoreAdapter ?: ScoreAdapter(emptyList()).also { scoreAdapter = it }
        if (binding.recyclerView.adapter !== adapter) {
            binding.recyclerView.adapter = adapter
        }
        adapter.updateData(items)
    }

    private fun updateStats(scores: List<Score>) {
        if (scores.isEmpty()) {
            binding.statsCard.visibility = View.GONE
            return
        }
        binding.statsCard.visibility = View.VISIBLE

        // 总学分走 GpaCalculator 同口径（重修去重、剔除免修/缺考）：直接对
        // validScores 求和会把挂科+重修两行学分双计，与同卡片的 GPA/平均分矛盾。
        val totalCredits = GpaCalculator.computeTotalCredits(scores)
        val weightedAvg = GpaCalculator.computeWeightedAvg(scores)

        // 算术平均分（不考虑学分权重）与已出成绩科目数
        val avgScore = GpaCalculator.computeAvgScore(scores)
        val subjectCount = scores.size

        val gpa = GpaCalculator.computeGpa(scores)

        // Animated counter for stats：从上次显示值（读 TextView 当前文本）起步，
        // 后台刷新/切换筛选时不再从 0 重播整段动画
        binding.gpaRingView.setGpa(gpa)
        animateCounter(binding.tvWeightedAvg, currentCounterValue(binding.tvWeightedAvg), weightedAvg, "%.1f", 800)
        animateCounter(binding.tvTotalCredits, currentCounterValue(binding.tvTotalCredits), totalCredits, "%.1f", 800)
        animateCounter(binding.tvAvgScore, currentCounterValue(binding.tvAvgScore), avgScore, "%.1f", 800)
        binding.tvSubjectCount.text = subjectCount.toString()
    }

    /** 统计数字动画起点：TextView 当前文本解析失败（空/占位符）时按 0 处理。 */
    private fun currentCounterValue(textView: TextView): Float =
        textView.text.toString().toFloatOrNull() ?: 0f

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
        items: List<Any>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val items: MutableList<Any> = items.toMutableList()

        private val TYPE_TREND = 0
        private val TYPE_SCORE = 1

        /** 原地替换数据：保持 Adapter 实例与 RecyclerView 滚动位置不变。 */
        fun updateData(newItems: List<Any>) {
            this.items.clear()
            this.items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) =
            if (items[position] is List<*>) TYPE_TREND else TYPE_SCORE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            if (viewType == TYPE_TREND) {
                val card = MaterialCardView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpi(10f) }
                    radius = dpi(14f).toFloat(); cardElevation = 0f
                    strokeColor = resources.getColor(R.color.claude_border_light, null)
                    strokeWidth = dpi(1f)
                    setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                }
                val content = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
                }
                card.addView(content)
                return TrendVH(card, content)
            }
            val card = MaterialCardView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpi(10f) }
                radius = dpi(14f).toFloat(); cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border_light, null)
                strokeWidth = dpi(1f)
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
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
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
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
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
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
                    text = if (score.score > 0) formatScore(score.score) else "--"
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_display); textSize = 24f
                    fontFeatureSettings = "lnum"
                    // 分数着色只强调两端：优秀（>=90）绿色、不及格（<60）红色，其余用正文墨色，
                    // 避免整列满屏绿色抢走课程名的注意力；未出分灰色
                    val scoreColor = when {
                        score.score <= 0 -> R.color.claude_text_hint
                        score.score < 60 -> R.color.claude_error
                        score.score >= 90 -> R.color.claude_success
                        else -> R.color.claude_text_primary
                    }
                    setTextColor(resources.getColor(scoreColor, null))
                })
                holder.content.addView(topRow)
                val infoParts = mutableListOf(score.courseType, "${formatScore(score.studyScore)} 学分", "${score.year} 第${score.term}学期")
                    .filter { it.isNotBlank() }.toMutableList()
                if (score.makeupScore > 0) infoParts.add("补考 ${formatScore(score.makeupScore)}")
                holder.content.addView(TextView(requireContext()).apply {
                    text = infoParts.joinToString("  ·  "); setTextAppearance(R.style.ClaudeCaption)
                    setPadding(0, dpi(4f), 0, 0)
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                })

                holder.itemView.isClickable = true
                holder.itemView.isFocusable = true
                holder.itemView.contentDescription = "查看${score.courseName}的成绩详情"
                holder.itemView.setOnClickListener {
                    ScoreDetailBottomSheet.newInstance(score)
                        .show(parentFragmentManager, "score_detail")
                }
            }
        }

        override fun getItemCount() = items.size

        inner class TrendVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
        inner class ScoreVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }

    private fun dpi(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /** 95.0 → "95"，88.5 → "88.5"：整数分不再拖一个 ".0" */
    private fun formatScore(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    override fun onDestroyView() {
        super.onDestroyView()
        activeAnimators.toList().forEach { it.cancel() }
        activeAnimators.clear()
        _binding = null
    }
}
