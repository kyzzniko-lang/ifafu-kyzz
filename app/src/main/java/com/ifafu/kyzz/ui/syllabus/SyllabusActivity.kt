package com.ifafu.kyzz.ui.syllabus

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.AdjustCourse
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.PracticeCourse
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.model.UnscheduledCourse
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
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadSyllabus(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadSyllabus() }

        viewModel.state.observe(this) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is SyllabusViewModel.SyllabusState.Idle -> {}
                is SyllabusViewModel.SyllabusState.Loading -> showLoading()
                is SyllabusViewModel.SyllabusState.Success -> showSyllabus(state.syllabus)
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

    private fun showSyllabus(syllabus: Syllabus) {
        binding.loadingLayout.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        binding.contentLayout.removeAllViews()

        showCourses(syllabus.courses)
        showAdjustCourses(syllabus.adjustCourses)
        showPracticeCourses(syllabus.practiceCourses)
        showUnscheduledCourses(syllabus.unscheduledCourses)

        if (syllabus.courses.isEmpty() && syllabus.adjustCourses.isEmpty()
            && syllabus.practiceCourses.isEmpty() && syllabus.unscheduledCourses.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_data)
                setTextAppearance(R.style.ClaudeBody)
                setPadding(0, 48, 0, 0)
                typeface = resources.getFont(R.font.claude_serif)
            }
            binding.contentLayout.addView(empty)
        }
    }

    private fun showCourses(courses: List<Course>) {
        if (courses.isEmpty()) return

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
                    text = "教师: ${course.teacher}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val address = TextView(this).apply {
                    text = "地点: ${course.address}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val time = TextView(this).apply {
                    val oddText = when (course.oddOrTwice) {
                        1 -> " 单周"
                        2 -> " 双周"
                        else -> ""
                    }
                    text = "第${course.weekBegin}-${course.weekEnd}周${oddText} 第${course.begin}-${course.end}节"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                content.addView(name)
                content.addView(teacher)
                content.addView(address)
                content.addView(time)

                if (course.examDate.isNotEmpty()) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { topMargin = 8; bottomMargin = 8 }
                        setBackgroundColor(resources.getColor(R.color.claude_border, null))
                    }
                    content.addView(divider)

                    val exam = TextView(this).apply {
                        text = "考试: ${course.examDate} ${course.examTime}"
                        setTextAppearance(R.style.ClaudeCaption)
                        setTextColor(resources.getColor(R.color.claude_terracotta, null))
                        typeface = resources.getFont(R.font.claude_serif)
                    }
                    content.addView(exam)

                    if (course.examAddress.isNotEmpty()) {
                        val examAddr = TextView(this).apply {
                            text = "考场: ${course.examAddress}"
                            setTextAppearance(R.style.ClaudeCaption)
                            setTextColor(resources.getColor(R.color.claude_terracotta, null))
                            typeface = resources.getFont(R.font.claude_serif)
                        }
                        content.addView(examAddr)
                    }
                }

                card.addView(content)
                binding.contentLayout.addView(card)
            }
        }
    }

    private fun showAdjustCourses(items: List<AdjustCourse>) {
        if (items.isEmpty()) return

        val sectionTitle = createSectionTitle("调停补课信息")
        binding.contentLayout.addView(sectionTitle)

        for (item in items) {
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

            val idTag = TextView(this).apply {
                text = item.id
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(resources.getColor(R.color.white, null))
                setPadding(8, 2, 8, 2)
                setBackgroundResource(R.drawable.bg_icon_circle)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val name = TextView(this).apply {
                text = "  ${item.name}"
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
            }

            header.addView(idTag)
            header.addView(name)
            content.addView(header)

            if (item.adjusted.isNotEmpty()) {
                val adjusted = TextView(this).apply {
                    text = "调整为: ${item.adjusted}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }
                content.addView(adjusted)
            }

            if (item.applyTime.isNotEmpty()) {
                val applyTime = TextView(this).apply {
                    text = "申请时间: ${item.applyTime}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }
                content.addView(applyTime)
            }

            card.addView(content)
            binding.contentLayout.addView(card)
        }
    }

    private fun showPracticeCourses(items: List<PracticeCourse>) {
        if (items.isEmpty()) return

        val sectionTitle = createSectionTitle("实践课信息")
        binding.contentLayout.addView(sectionTitle)

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
            setPadding(20, 12, 20, 12)
        }

        for ((index, item) in items.withIndex()) {
            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 8; bottomMargin = 8 }
                    setBackgroundColor(resources.getColor(R.color.claude_border, null))
                }
                content.addView(divider)
            }

            val row = createInfoRow(item.name, "${item.teacher} | ${item.credit}学分")
            content.addView(row)

            if (item.weeks.isNotEmpty()) {
                val weeksRow = createInfoRow("起止周", item.weeks)
                content.addView(weeksRow)
            }
        }

        card.addView(content)
        binding.contentLayout.addView(card)
    }

    private fun showUnscheduledCourses(items: List<UnscheduledCourse>) {
        if (items.isEmpty()) return

        val sectionTitle = createSectionTitle("未安排上课时间的课程")
        binding.contentLayout.addView(sectionTitle)

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
            setPadding(20, 12, 20, 12)
        }

        for ((index, item) in items.withIndex()) {
            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 8; bottomMargin = 8 }
                    setBackgroundColor(resources.getColor(R.color.claude_border, null))
                }
                content.addView(divider)
            }

            val row = createInfoRow(item.name, "${item.teacher} | ${item.credit}学分")
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
            setPadding(0, 24, 0, 8)
            typeface = resources.getFont(R.font.claude_serif)
        }
    }

    private fun createInfoRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 6, 0, 6)

            val labelView = TextView(context).apply {
                text = label
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }

            val valueView = TextView(context).apply {
                text = value
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                gravity = Gravity.END
            }

            addView(labelView)
            addView(valueView)
        }
    }
}
