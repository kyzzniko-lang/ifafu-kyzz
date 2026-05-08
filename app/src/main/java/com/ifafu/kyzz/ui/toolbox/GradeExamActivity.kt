package com.ifafu.kyzz.ui.toolbox

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.databinding.ActivityGradeExamBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.UiState
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GradeExamActivity : BaseActivity<ActivityGradeExamBinding>() {

    private val viewModel: GradeExamViewModel by viewModels()

    override fun createBinding() = ActivityGradeExamBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load(forceRefresh = true) }

        viewModel.state.observe(this) { state ->
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> showExams(state.data)
                is UiState.Cached -> showExams(state.data)
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.load()
    }

    private fun showLoading() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.scrollContent.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingLayout.visibility = View.GONE
        binding.scrollContent.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showExams(exams: List<GradeExam>) {
        binding.loadingLayout.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.contentLayout.removeAllViews()

        if (exams.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_data)
                setTextAppearance(R.style.ClaudeBody)
                setPadding(0, 48, 0, 0)
            }
            binding.contentLayout.addView(empty)
            return
        }

        val title = TextView(this).apply {
            text = "等级考试成绩 (${exams.size}条)"
            setTextAppearance(R.style.ClaudeSubtitle)
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            setPadding(0, 8, 0, 12)
        }
        binding.contentLayout.addView(title)

        for (exam in exams) {
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
                setPadding(20, 16, 20, 16)
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val name = TextView(this).apply {
                text = exam.name
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val score = TextView(this).apply {
                text = if (exam.score.isNotEmpty()) "${exam.score}分" else ""
                setTextAppearance(R.style.ClaudeSubtitle)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
            }

            header.addView(name)
            header.addView(score)
            content.addView(header)

            val details = mutableListOf<Pair<String, String>>()
            if (exam.year.isNotEmpty()) details.add("学年" to "${exam.year} 第${exam.term}学期")
            if (exam.date.isNotEmpty()) details.add("考试日期" to exam.date)
            if (exam.ticketNumber.isNotEmpty()) details.add("准考证号" to exam.ticketNumber)

            val subScores = mutableListOf<String>()
            if (exam.listeningScore.isNotEmpty()) subScores.add("听力:${exam.listeningScore}")
            if (exam.readingScore.isNotEmpty()) subScores.add("阅读:${exam.readingScore}")
            if (exam.writingScore.isNotEmpty()) subScores.add("写作:${exam.writingScore}")
            if (exam.comprehensiveScore.isNotEmpty()) subScores.add("综合:${exam.comprehensiveScore}")
            if (subScores.isNotEmpty()) details.add("各项成绩" to subScores.joinToString(" "))

            for ((label, value) in details) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4, 0, 4)
                }
                val labelView = TextView(this).apply {
                    text = label
                    setTextAppearance(R.style.ClaudeCaption)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                }
                val valueView = TextView(this).apply {
                    text = value
                    setTextAppearance(R.style.ClaudeCaption)
                    setTextColor(resources.getColor(R.color.claude_text_primary, null))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                    gravity = Gravity.END
                }
                row.addView(labelView)
                row.addView(valueView)
                content.addView(row)
            }

            card.addView(content)
            binding.contentLayout.addView(card)
        }
    }
}
