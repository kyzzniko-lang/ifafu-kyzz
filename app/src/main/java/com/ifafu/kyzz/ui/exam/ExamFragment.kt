package com.ifafu.kyzz.ui.exam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.databinding.FragmentExamBinding
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExamFragment : Fragment() {

    private var _binding: FragmentExamBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExamViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadExams(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadExams(forceRefresh = true) }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    binding.offlineBanner.root.visibility = View.GONE
                    showExams(state.data.exams)
                }
                is UiState.Cached -> {
                    binding.offlineBanner.root.visibility = View.VISIBLE
                    showExams(state.data.exams)
                }
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.loadExams()
    }

    private val shimmer get() = binding.shimmerPlaceholder.root

    private fun showLoading() {
        shimmer.startShimmer()
        shimmer.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        shimmer.stopShimmer()
        shimmer.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showExams(exams: List<Exam>) {
        shimmer.stopShimmer()
        shimmer.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val conflicts = findConflicts(exams)
        val allItems = mutableListOf<Any>()
        if (conflicts.isNotEmpty()) {
            allItems.add(ConflictWarning(conflicts))
        }
        allItems.addAll(exams)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = ExamAdapter(allItems)
    }

    private fun findConflicts(exams: List<Exam>): List<List<Exam>> {
        val datePatterns = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.getDefault()),
            java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault())
        )
        val grouped = exams.filter { it.datetime.isNotEmpty() }.groupBy { exam ->
            val raw = exam.datetime.replace("（", "(").replace("）", ")").split("(", "~", " ").first().trim()
            var dateStr = ""
            for (fmt in datePatterns) {
                try { dateStr = fmt.format(fmt.parse(raw)!!); break } catch (_: Exception) {}
            }
            dateStr
        }.filter { it.key.isNotEmpty() && it.value.size > 1 }
        return grouped.values.toList()
    }

    data class ConflictWarning(val groups: List<List<Exam>>)

    inner class ExamAdapter(private val items: List<Any>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_WARNING = 0
        private val TYPE_EXAM = 1

        override fun getItemViewType(position: Int) = if (items[position] is ConflictWarning) TYPE_WARNING else TYPE_EXAM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            if (viewType == TYPE_WARNING) {
                val card = MaterialCardView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10 }
                    radius = 14f; cardElevation = 0f
                    strokeColor = 0xFFFFAB91.toInt()
                    strokeWidth = 2
                    setCardBackgroundColor(0x1AFFAB91.toInt())
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
                holder.content.addView(TextView(requireContext()).apply {
                    text = "⚠ 考试冲突提醒"
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(0xFFFF6D00.toInt())
                    typeface = resources.getFont(R.font.claude_serif)
                    textSize = 15f
                })
                for (group in warning.groups) {
                    val dateStr = group.first().datetime.split(" ", "(", "（").first()
                    val names = group.joinToString("、") { it.name }
                    holder.content.addView(TextView(requireContext()).apply {
                        text = "${dateStr}: $names"
                        setTextAppearance(R.style.ClaudeCaption)
                        setPadding(0, 6, 0, 0)
                        typeface = resources.getFont(R.font.claude_serif)
                    })
                }
            } else if (holder is ExamVH) {
                val exam = items[position] as Exam
                holder.content.removeAllViews()
                holder.content.addView(TextView(requireContext()).apply {
                    text = exam.name; setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                })
                holder.content.addView(TextView(requireContext()).apply {
                    text = "时间: ${exam.datetime}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
                holder.content.addView(TextView(requireContext()).apply {
                    text = "地点: ${exam.address}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
                holder.content.addView(TextView(requireContext()).apply {
                    text = "座位号: ${exam.seatNumber}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
                holder.content.addView(TextView(requireContext()).apply {
                    text = "校区: ${exam.campus}"; setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }
        }

        override fun getItemCount() = items.size

        inner class WarningVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
        inner class ExamVH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
