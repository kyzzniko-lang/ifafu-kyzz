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

    private fun showCourses() {
        binding.loadingLayout.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE

        val courses = viewModel.courseList.value?.courses ?: emptyList()
        val adapter = ElectiveAdapter(courses) { courseIndex ->
            viewModel.selectCourse(courseIndex)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    inner class ElectiveAdapter(
        private val courses: List<ElectiveCourse>,
        private val onSelect: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ElectiveAdapter.ViewHolder>() {

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
                isClickable = true
                isFocusable = true
            }

            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }

            card.addView(content)
            return ViewHolder(card, content)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val course = courses[position]
            holder.content.removeAllViews()
            holder.card.setOnClickListener { onSelect(course.courseIndex) }

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

            val remain = TextView(this@ElectiveCourseActivity).apply {
                text = "余${course.have}"
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(
                    if (course.have > 0) resources.getColor(R.color.claude_success, null)
                    else resources.getColor(R.color.claude_error, null)
                )
                typeface = resources.getFont(R.font.claude_serif)
            }

            topRow.addView(name)
            topRow.addView(remain)
            holder.content.addView(topRow)

            val teacher = TextView(this@ElectiveCourseActivity).apply {
                text = "教师: ${course.teacher}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val time = TextView(this@ElectiveCourseActivity).apply {
                text = "时间: ${course.time}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val location = TextView(this@ElectiveCourseActivity).apply {
                text = "地点: ${course.location}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            val info = TextView(this@ElectiveCourseActivity).apply {
                text = "${course.nature}  |  ${course.campus}  |  学分: ${course.studyScore}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            holder.content.addView(teacher)
            holder.content.addView(time)
            holder.content.addView(location)
            holder.content.addView(info)
        }

        override fun getItemCount() = courses.size

        inner class ViewHolder(
            val card: MaterialCardView,
            val content: LinearLayout
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(card)
    }
}
