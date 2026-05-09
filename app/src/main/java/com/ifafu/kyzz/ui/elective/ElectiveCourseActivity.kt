package com.ifafu.kyzz.ui.elective

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.ElectiveCourse
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.databinding.ActivityElectiveCourseBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import android.widget.FrameLayout
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ElectiveCourseActivity : BaseActivity<ActivityElectiveCourseBinding>() {

    private val viewModel: ElectiveViewModel by viewModels()

    override fun createBinding(): ActivityElectiveCourseBinding = ActivityElectiveCourseBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.elective_title)
        binding.btnRetry.setOnClickListener { viewModel.loadCourses() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is ElectiveViewModel.ElectiveState.Idle -> {}
                is ElectiveViewModel.ElectiveState.Loading -> showLoading()
                is ElectiveViewModel.ElectiveState.Success -> showCourses()
                is ElectiveViewModel.ElectiveState.Error -> showError(state.message)
            }
        }

        viewModel.courseList.observe(this) { showCourses() }
        viewModel.loadCourses()
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

    private fun showCourses() {
        binding.petLoading.root.stopLoading()
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val available = viewModel.courseList.value?.courses ?: emptyList()
        val selected = viewModel.courseList.value?.electived ?: emptyList()

        val items = mutableListOf<AdapterItem>()
        if (selected.isNotEmpty()) {
            items.add(AdapterItem.Header("已选课程"))
            selected.forEach { items.add(AdapterItem.Course(it, false)) }
        }
        if (available.isNotEmpty()) {
            items.add(AdapterItem.Header("可选课程"))
            available.forEach { items.add(AdapterItem.Course(it, true)) }
        }

        if (items.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.errorLayout.visibility = View.VISIBLE
            binding.tvError.text = "暂无课程数据"
            return
        }

        val adapter = ElectiveAdapter(items) { courseIndex ->
            viewModel.selectCourse(courseIndex)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    sealed class AdapterItem {
        data class Header(val title: String) : AdapterItem()
        data class Course(val course: ElectiveCourse, val selectable: Boolean) : AdapterItem()
    }

    inner class ElectiveAdapter(
        private val items: List<AdapterItem>,
        private val onSelect: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_COURSE = 1

        override fun getItemViewType(position: Int) = when (items[position]) {
            is AdapterItem.Header -> TYPE_HEADER
            is AdapterItem.Course -> TYPE_COURSE
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 16; bottomMargin = 8 }
                    setTextAppearance(R.style.ClaudeOverline)
                    typeface = resources.getFont(R.font.claude_serif)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                }
                HeaderViewHolder(tv)
            } else {
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
                    isClickable = true
                    isFocusable = true
                }
                val content = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(20, 16, 20, 16)
                }
                card.addView(content)
                CourseViewHolder(card, content)
            }
        }

        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is AdapterItem.Header -> (holder as HeaderViewHolder).bind(item)
                is AdapterItem.Course -> (holder as CourseViewHolder).bind(item)
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderViewHolder(private val tv: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {
            fun bind(item: AdapterItem.Header) { tv.text = item.title }
        }

        inner class CourseViewHolder(
            val card: MaterialCardView,
            val content: LinearLayout
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(card) {

            fun bind(item: AdapterItem.Course) {
                val course = item.course
                content.removeAllViews()
                if (item.selectable) {
                    card.setOnClickListener { onSelect(course.courseIndex) }
                    card.alpha = 1f
                } else {
                    card.setOnClickListener(null)
                    card.alpha = 0.7f
                }

                val topRow = LinearLayout(this@ElectiveCourseActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }

                val name = TextView(this@ElectiveCourseActivity).apply {
                    text = course.name
                    setTextAppearance(R.style.ClaudeBody)
                    setTextColor(resources.getColor(R.color.claude_text_primary, null))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    typeface = resources.getFont(R.font.claude_serif)
                }

                topRow.addView(name)

                if (item.selectable && course.have > 0) {
                    val remain = TextView(this@ElectiveCourseActivity).apply {
                        text = "余${course.have}"
                        setTextAppearance(R.style.ClaudeCaption)
                        setTextColor(resources.getColor(R.color.claude_success, null))
                        typeface = resources.getFont(R.font.claude_serif)
                    }
                    topRow.addView(remain)
                } else if (!item.selectable) {
                    val tag = TextView(this@ElectiveCourseActivity).apply {
                        text = "已选"
                        setTextAppearance(R.style.ClaudeCaption)
                        setTextColor(resources.getColor(R.color.claude_terracotta, null))
                        typeface = resources.getFont(R.font.claude_serif)
                    }
                    topRow.addView(tag)
                }

                content.addView(topRow)

                if (course.teacher.isNotBlank()) {
                    content.addView(TextView(this@ElectiveCourseActivity).apply {
                        text = "教师: ${course.teacher}"
                        setTextAppearance(R.style.ClaudeCaption)
                        typeface = resources.getFont(R.font.claude_serif)
                    })
                }
                if (course.time.isNotBlank()) {
                    content.addView(TextView(this@ElectiveCourseActivity).apply {
                        text = "时间: ${course.time}"
                        setTextAppearance(R.style.ClaudeCaption)
                        typeface = resources.getFont(R.font.claude_serif)
                    })
                }
                if (course.location.isNotBlank()) {
                    content.addView(TextView(this@ElectiveCourseActivity).apply {
                        text = "地点: ${course.location}"
                        setTextAppearance(R.style.ClaudeCaption)
                        typeface = resources.getFont(R.font.claude_serif)
                    })
                }

                val infoParts = mutableListOf<String>()
                if (course.nature.isNotBlank()) infoParts.add(course.nature)
                if (course.campus.isNotBlank()) infoParts.add(course.campus)
                if (course.studyScore > 0) infoParts.add("学分: ${course.studyScore}")
                if (infoParts.isNotEmpty()) {
                    content.addView(TextView(this@ElectiveCourseActivity).apply {
                        text = infoParts.joinToString("  |  ")
                        setTextAppearance(R.style.ClaudeCaption)
                        typeface = resources.getFont(R.font.claude_serif)
                    })
                }
            }
        }
    }
}
