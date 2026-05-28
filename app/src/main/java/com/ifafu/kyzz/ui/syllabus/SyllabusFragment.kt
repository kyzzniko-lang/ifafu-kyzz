package com.ifafu.kyzz.ui.syllabus

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.AdjustCourse
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.data.model.PracticeCourse
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.model.UnscheduledCourse
import com.ifafu.kyzz.databinding.FragmentSyllabusBinding
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SyllabusFragment : Fragment() {

    private var _binding: FragmentSyllabusBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SyllabusViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSyllabusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            requireContext().getColor(R.color.claude_bg_elevated)
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadSyllabus(forceRefresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadSyllabus() }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            binding.swipeRefresh.isRefreshing = false
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    binding.offlineBanner.root.visibility = View.GONE
                    showSyllabus(state.data)
                }
                is UiState.Cached -> {
                    binding.offlineBanner.root.visibility = View.VISIBLE
                    showSyllabus(state.data)
                }
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.loadSyllabus()
    }

    private fun showLoading() {
        binding.petLoading.root.startLoading()
        binding.contentLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.petLoading.root.stopLoading()
        binding.contentLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showSyllabus(syllabus: Syllabus) {
        binding.petLoading.root.stopLoading()
        binding.contentLayout.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        binding.contentLayout.removeAllViews()

        showCourses(syllabus.courses)
        showAdjustCourses(syllabus.adjustCourses)
        showPracticeCourses(syllabus.practiceCourses)
        showUnscheduledCourses(syllabus.unscheduledCourses)

        if (syllabus.courses.isEmpty() && syllabus.adjustCourses.isEmpty()
            && syllabus.practiceCourses.isEmpty() && syllabus.unscheduledCourses.isEmpty()) {
            val empty = TextView(requireContext()).apply {
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

            val dayLabel = TextView(requireContext()).apply {
                text = weekDays[day - 1]
                setTextAppearance(R.style.ClaudeSubtitle)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                setPadding(0, 24, 0, 8)
                typeface = resources.getFont(R.font.claude_serif)
            }
            binding.contentLayout.addView(dayLabel)

            for (course in dayCourses) {
                val card = MaterialCardView(requireContext()).apply {
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

                val content = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 16, 20, 16)
                }

                val name = TextView(requireContext()).apply {
                    text = course.name
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val teacher = TextView(requireContext()).apply {
                    text = "教师: ${course.teacher}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val address = TextView(requireContext()).apply {
                    text = "地点: ${course.address}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                val time = TextView(requireContext()).apply {
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
                    val divider = View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { topMargin = 8; bottomMargin = 8 }
                        setBackgroundColor(resources.getColor(R.color.claude_border, null))
                    }
                    content.addView(divider)

                    val exam = TextView(requireContext()).apply {
                        text = "考试: ${course.examDate} ${course.examTime}"
                        setTextAppearance(R.style.ClaudeCaption)
                        setTextColor(resources.getColor(R.color.claude_terracotta, null))
                        typeface = resources.getFont(R.font.claude_serif)
                    }
                    content.addView(exam)

                    if (course.examAddress.isNotEmpty()) {
                        val examAddr = TextView(requireContext()).apply {
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
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 14f; cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }

            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }

            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val idTag = TextView(requireContext()).apply {
                text = item.id
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(resources.getColor(R.color.white, null))
                setPadding(8, 2, 8, 2)
                setBackgroundResource(R.drawable.bg_icon_circle)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val name = TextView(requireContext()).apply {
                text = "  ${item.name}"
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
            }

            header.addView(idTag)
            header.addView(name)
            content.addView(header)

            if (item.adjusted.isNotEmpty()) {
                content.addView(TextView(requireContext()).apply {
                    text = "调整为: ${item.adjusted}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }

            if (item.applyTime.isNotEmpty()) {
                content.addView(TextView(requireContext()).apply {
                    text = "申请时间: ${item.applyTime}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }

            card.addView(content)
            binding.contentLayout.addView(card)
        }
    }

    private fun showPracticeCourses(items: List<PracticeCourse>) {
        if (items.isEmpty()) return

        binding.contentLayout.addView(createSectionTitle("实践课信息"))

        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            radius = 14f; cardElevation = 0f
            strokeColor = resources.getColor(R.color.claude_border, null)
            strokeWidth = 1
            setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
        }

        for ((index, item) in items.withIndex()) {
            if (index > 0) {
                content.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 8; bottomMargin = 8 }
                    setBackgroundColor(resources.getColor(R.color.claude_border, null))
                })
            }
            content.addView(createInfoRow(item.name, "${item.teacher} | ${item.credit}学分"))
            if (item.weeks.isNotEmpty()) {
                content.addView(createInfoRow("起止周", item.weeks))
            }
        }

        card.addView(content)
        binding.contentLayout.addView(card)
    }

    private fun showUnscheduledCourses(items: List<UnscheduledCourse>) {
        if (items.isEmpty()) return

        binding.contentLayout.addView(createSectionTitle("未安排上课时间的课程"))

        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            radius = 14f; cardElevation = 0f
            strokeColor = resources.getColor(R.color.claude_border, null)
            strokeWidth = 1
            setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
        }

        for ((index, item) in items.withIndex()) {
            if (index > 0) {
                content.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 8; bottomMargin = 8 }
                    setBackgroundColor(resources.getColor(R.color.claude_border, null))
                })
            }
            content.addView(createInfoRow(item.name, "${item.teacher} | ${item.credit}学分"))
        }

        card.addView(content)
        binding.contentLayout.addView(card)
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(requireContext()).apply {
            text = title
            setTextAppearance(R.style.ClaudeSubtitle)
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            setPadding(0, 24, 0, 8)
            typeface = resources.getFont(R.font.claude_serif)
        }
    }

    private fun createInfoRow(label: String, value: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 6, 0, 6)

            addView(TextView(requireContext()).apply {
                text = label
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })

            addView(TextView(requireContext()).apply {
                text = value
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                gravity = Gravity.END
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
