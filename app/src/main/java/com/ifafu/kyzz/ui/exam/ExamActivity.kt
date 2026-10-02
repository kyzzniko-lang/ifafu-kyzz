package com.ifafu.kyzz.ui.exam

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Exam
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.databinding.ActivityExamBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.UiState
import android.widget.FrameLayout
import dagger.hilt.android.AndroidEntryPoint
import com.ifafu.kyzz.data.util.ExamDates

@AndroidEntryPoint
class ExamActivity : BaseActivity<ActivityExamBinding>() {

    private val viewModel: ExamViewModel by viewModels()
    private var layoutManagerSet = false
    // 当前列表的冲突分组，点击卡片打开详情时据此展示"同场冲突"提示
    private var currentConflicts: List<List<Exam>> = emptyList()

    override fun createBinding(): ActivityExamBinding = ActivityExamBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.exam_title)
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            getColor(R.color.claude_bg_elevated)
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadExams(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadExams(forceRefresh = true) }

        viewModel.state.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> showExams(state.data.exams)
                is UiState.Cached -> showExams(state.data.exams)
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.loadExams()
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

    private fun showExams(exams: List<Exam>) {
        binding.petLoading.root.stopLoading()
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val conflicts = findConflicts(exams)
        currentConflicts = conflicts
        val allItems = mutableListOf<Any>()
        if (conflicts.isNotEmpty()) {
            allItems.add(ConflictWarning(conflicts))
        }
        allItems.addAll(exams)

        if (!layoutManagerSet) {
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            layoutManagerSet = true
        }
        binding.recyclerView.adapter = ExamAdapter(allItems)
    }

    private fun findConflicts(exams: List<Exam>): List<List<Exam>> =
        // 两两重叠判定 + 连通分量合并：跨天考试第二天、多时段第二场也能检出
        ExamDates.findConflictGroups(exams)

    data class ConflictWarning(val groups: List<List<Exam>>)

    inner class ExamAdapter(private val items: List<Any>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_WARNING = 0
        private val TYPE_EXAM = 1

        override fun getItemViewType(position: Int) = if (items[position] is ConflictWarning) TYPE_WARNING else TYPE_EXAM

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            if (viewType == TYPE_WARNING) {
                val card = MaterialCardView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10 }
                    radius = 14f; cardElevation = 0f
                    strokeColor = resources.getColor(R.color.claude_terracotta_200, null)
                    strokeWidth = 2
                    setCardBackgroundColor(resources.getColor(R.color.claude_terracotta_50, null))
                }
                val content = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16)
                }
                card.addView(content)
                return WarningVH(card, content)
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
            return ExamVH(card, content)
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            if (holder is WarningVH) {
                val warning = items[position] as ConflictWarning
                holder.content.removeAllViews()
                val title = TextView(this@ExamActivity).apply {
                    text = "考试时间冲突"
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta_700, null))
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                    textSize = 15f
                }
                holder.content.addView(title)
                for (group in warning.groups) {
                    val dateStr = group.first().datetime.split(" ", "(", "（").first()
                    val names = group.joinToString("、") { it.name }
                    val msg = TextView(this@ExamActivity).apply {
                        text = "${dateStr}: $names"
                        setTextAppearance(R.style.ClaudeCaption)
                        setPadding(0, 6, 0, 0)
                        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                    }
                    holder.content.addView(msg)
                }
            } else if (holder is ExamVH) {
                val exam = items[position] as Exam
                holder.content.removeAllViews()
                val name = TextView(this@ExamActivity).apply {
                    text = exam.name; setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                }
                val time = TextView(this@ExamActivity).apply {
                    text = "时间: ${exam.datetime}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                }
                val location = TextView(this@ExamActivity).apply {
                    text = "地点: ${exam.address}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                }
                val seat = TextView(this@ExamActivity).apply {
                    text = "座位号: ${exam.seatNumber}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                }
                val campus = TextView(this@ExamActivity).apply {
                    text = "校区: ${exam.campus}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                }
                holder.content.addView(name); holder.content.addView(time)
                holder.content.addView(location); holder.content.addView(seat); holder.content.addView(campus)

                // 点击卡片打开考试详情弹层
                holder.content.setOnClickListener {
                    val conflictWith = currentConflicts
                        .firstOrNull { group ->
                            group.any { it.id == exam.id && it.name == exam.name }
                        }
                        ?.filterNot { it.id == exam.id && it.name == exam.name }
                        ?.joinToString("、") { it.name }
                        .orEmpty()
                    ExamDetailBottomSheet.newInstance(exam, conflictWith)
                        .show(supportFragmentManager, "exam_detail")
                }
            }
        }

        override fun getItemCount() = items.size

        inner class WarningVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
        inner class ExamVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }
}
