package com.ifafu.kyzz.ui.toolbox

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.CreditSummary
import com.ifafu.kyzz.data.model.TrainingCourse
import com.ifafu.kyzz.data.model.TrainingPlan
import com.ifafu.kyzz.databinding.ActivityTrainingPlanBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TrainingPlanActivity : BaseActivity<ActivityTrainingPlanBinding>() {

    private val viewModel: TrainingPlanViewModel by viewModels()

    override fun createBinding() = ActivityTrainingPlanBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is TrainingPlanViewModel.State.Loading -> showLoading()
                is TrainingPlanViewModel.State.Success -> showPlan(state.plan)
                is TrainingPlanViewModel.State.Error -> showError(state.message)
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

    private fun showPlan(plan: TrainingPlan) {
        binding.loadingLayout.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.contentLayout.removeAllViews()

        if (plan.college.isNotEmpty() || plan.major.isNotEmpty()) {
            addSection("基本信息", listOf(
                "学院" to plan.college,
                "专业" to plan.major
            ))
        }

        if (plan.courses.isNotEmpty()) {
            val grouped = plan.courses.groupBy { it.courseNature.ifEmpty { "其他" } }
            val order = listOf("专业核心课", "科类基础课", "公共课", "实践教学", "学科(专业)选修课", "学科、专业选修课")
            val sortedKeys = grouped.keys.sortedBy { key ->
                val idx = order.indexOf(key)
                if (idx >= 0) idx else order.size
            }

            for (nature in sortedKeys) {
                val courses = grouped[nature] ?: continue
                val title = createSectionTitle("$nature (${courses.size}门)")
                binding.contentLayout.addView(title)

                for (course in courses) {
                    addCourseCard(course)
                }
            }
        }

        if (plan.creditSummary.isNotEmpty()) {
            addSection("学分要求", plan.creditSummary.map { it.label to it.credit })
        }

        if (plan.courses.isEmpty() && plan.creditSummary.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_data)
                setTextAppearance(R.style.ClaudeBody)
                setPadding(0, 48, 0, 0)
            }
            binding.contentLayout.addView(empty)
        }
    }

    private fun addCourseCard(course: TrainingCourse) {
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
            text = course.name
            setTextAppearance(R.style.ClaudeBody)
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val credit = TextView(this).apply {
            text = "${course.credit}学分"
            setTextAppearance(R.style.ClaudeSubtitle)
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
        }

        header.addView(name)
        header.addView(credit)
        content.addView(header)

        val fields = mutableListOf<Pair<String, String>>()
        if (course.code.isNotEmpty()) fields.add("课程代码" to course.code)
        if (course.weeklyHours.isNotEmpty()) fields.add("周学时" to course.weeklyHours)
        if (course.courseNature.isNotEmpty()) fields.add("课程性质" to course.courseNature)
        if (course.suggestTerm.isNotEmpty()) fields.add("建议修读学期" to "第${course.suggestTerm}学期")
        if (course.weeks.isNotEmpty()) fields.add("起止周" to "第${course.weeks}周")

        for ((label, value) in fields) {
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

    private fun addSection(title: String, items: List<Pair<String, String>>) {
        val sectionTitle = createSectionTitle(title)
        binding.contentLayout.addView(sectionTitle)

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            radius = 14f
            cardElevation = 0f
            strokeColor = resources.getColor(R.color.claude_border, null)
            strokeWidth = 1
            setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
        }

        for ((label, value) in items) {
            if (value.isEmpty()) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
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

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextAppearance(R.style.ClaudeSubtitle)
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            setPadding(0, 20, 0, 8)
        }
    }
}
