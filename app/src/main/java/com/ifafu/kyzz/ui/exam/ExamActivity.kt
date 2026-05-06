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
import android.widget.FrameLayout
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExamActivity : BaseActivity<ActivityExamBinding>() {

    private val viewModel: ExamViewModel by viewModels()

    override fun createBinding(): ActivityExamBinding = ActivityExamBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.exam_title)
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadExams() }
        binding.btnRetry.setOnClickListener { viewModel.loadExams() }

        viewModel.state.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is ExamViewModel.ExamState.Idle -> {}
                is ExamViewModel.ExamState.Loading -> showLoading()
                is ExamViewModel.ExamState.Success -> showExams(state.examTable.exams)
                is ExamViewModel.ExamState.Error -> showError(state.message)
            }
        }

        viewModel.loadExams()
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

    private fun showExams(exams: List<Exam>) {
        binding.loadingLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val adapter = ExamAdapter(exams)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    inner class ExamAdapter(private val exams: List<Exam>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<ExamAdapter.ViewHolder>() {

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
            val exam = exams[position]
            holder.content.removeAllViews()

            val name = TextView(this@ExamActivity).apply {
                text = exam.name
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
            }

            val time = TextView(this@ExamActivity).apply {
                text = "时间: ${exam.datetime}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val location = TextView(this@ExamActivity).apply {
                text = "地点: ${exam.address}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val seat = TextView(this@ExamActivity).apply {
                text = "座位号: ${exam.seatNumber}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val campus = TextView(this@ExamActivity).apply {
                text = "校区: ${exam.campus}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            holder.content.addView(name)
            holder.content.addView(time)
            holder.content.addView(location)
            holder.content.addView(seat)
            holder.content.addView(campus)
        }

        override fun getItemCount() = exams.size

        inner class ViewHolder(
            itemView: android.view.View,
            val content: LinearLayout
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }
}
