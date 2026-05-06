package com.ifafu.kyzz.ui.syllabus

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.databinding.ActivitySyllabusBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SyllabusActivity : BaseActivity<ActivitySyllabusBinding>() {

    private val viewModel: SyllabusViewModel by viewModels()

    override fun createBinding(): ActivitySyllabusBinding = ActivitySyllabusBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.syllabus_title)
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadSyllabus() }
        binding.btnRetry.setOnClickListener { viewModel.loadSyllabus() }

        viewModel.state.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is SyllabusViewModel.SyllabusState.Idle -> {}
                is SyllabusViewModel.SyllabusState.Loading -> showLoading()
                is SyllabusViewModel.SyllabusState.Success -> showSyllabus(state.syllabus.courses)
                is SyllabusViewModel.SyllabusState.Error -> showError(state.message)
            }
        }

        viewModel.loadSyllabus()
    }

    private fun showLoading() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingLayout.visibility = View.GONE
        binding.contentLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showSyllabus(courses: List<Course>) {
        binding.loadingLayout.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        binding.contentLayout.removeAllViews()

        val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val grouped = courses.groupBy { it.weekDay }

        for (day in 1..7) {
            val dayCourses = grouped[day] ?: continue

            val dayLabel = TextView(this).apply {
                text = weekDays[day - 1]
                setTextAppearance(R.style.ClaudeSubtitle)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                setPadding(0, 24, 0, 8)
                typeface = resources.getFont(R.font.claude_serif)
            }
            binding.contentLayout.addView(dayLabel)

            for (course in dayCourses) {
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

                val name = TextView(this).apply {
                    text = course.name
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val teacher = TextView(this).apply {
                    text = getString(R.string.course_detail_teacher, course.teacher)
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val address = TextView(this).apply {
                    text = getString(R.string.course_detail_address, course.address)
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val time = TextView(this).apply {
                    text = getString(R.string.course_detail_time, "第${course.weekBegin}-${course.weekEnd}周")
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                content.addView(name)
                content.addView(teacher)
                content.addView(address)
                content.addView(time)
                card.addView(content)
                binding.contentLayout.addView(card)
            }
        }

        if (courses.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_data)
                setTextAppearance(R.style.ClaudeBody)
                setPadding(0, 48, 0, 0)
                typeface = resources.getFont(R.font.claude_serif)
            }
            binding.contentLayout.addView(empty)
        }
    }
}
