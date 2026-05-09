package com.ifafu.kyzz.ui.elective

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.api.SimpleElectiveApi
import com.ifafu.kyzz.data.model.SimpleCourse
import com.ifafu.kyzz.databinding.ActivitySimpleElectiveBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SimpleElectiveActivity : BaseActivity<ActivitySimpleElectiveBinding>() {

    private val viewModel: SimpleElectiveViewModel by viewModels()

    private data class TabInfo(
        val type: String,
        val label: String,
        var view: MaterialCardView? = null
    )

    private data class SpecialTab(val label: String, val activityClass: Class<*>)

    private val tabs = listOf(
        TabInfo(SimpleElectiveApi.TYPE_PROFESSIONAL, "专业选修"),
        TabInfo(SimpleElectiveApi.TYPE_SPORTS, "体育/英语"),
        TabInfo(SimpleElectiveApi.TYPE_RETAKE, "重新修读"),
        TabInfo(SimpleElectiveApi.TYPE_MINOR, "辅修选课")
    )

    private val specialTabs = listOf(
        SpecialTab("个性发展课", ElectiveCourseActivity::class.java)
    )

    private var selectedTab = 0

    override fun createBinding() = ActivitySimpleElectiveBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.title = "选课中心"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.reload() }

        setupTabs()
        observeState()
        selectTab(0)
    }

    private fun setupTabs() {
        tabs.forEachIndexed { index, tab ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 8 }
                radius = 20f
                cardElevation = 0f
                strokeWidth = 1
                strokeColor = resources.getColor(R.color.claude_border, null)
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                isClickable = true
                isFocusable = true
            }
            val tv = TextView(this).apply {
                text = tab.label
                setPadding(20, 10, 20, 10)
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                typeface = resources.getFont(R.font.claude_serif)
                gravity = Gravity.CENTER
            }
            card.addView(tv)
            card.setOnClickListener { selectTab(index) }
            binding.tabContainer.addView(card)
            tab.view = card
        }

        // Special tabs that open separate activities
        specialTabs.forEach { special ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 8 }
                radius = 20f
                cardElevation = 0f
                strokeWidth = 1
                strokeColor = resources.getColor(R.color.claude_border, null)
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                isClickable = true
                isFocusable = true
            }
            val tv = TextView(this).apply {
                text = special.label
                setPadding(20, 10, 20, 10)
                setTextAppearance(R.style.ClaudeCaption)
                setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                typeface = resources.getFont(R.font.claude_serif)
                gravity = Gravity.CENTER
            }
            card.addView(tv)
            card.setOnClickListener { startActivity(Intent(this, special.activityClass)) }
            binding.tabContainer.addView(card)
        }
    }

    private fun selectTab(index: Int) {
        selectedTab = index
        tabs.forEachIndexed { i, tab ->
            tab.view?.let { card ->
                val tv = card.getChildAt(0) as? TextView ?: return@let
                if (i == index) {
                    card.setCardBackgroundColor(resources.getColor(R.color.claude_terracotta, null))
                    card.strokeColor = resources.getColor(R.color.claude_terracotta, null)
                    tv.setTextColor(resources.getColor(R.color.white, null))
                } else {
                    card.setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                    card.strokeColor = resources.getColor(R.color.claude_border, null)
                    tv.setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                }
            }
        }
        viewModel.loadCourses(tabs[index].type)
    }

    private fun observeState() {
        viewModel.state.observe(this) { state ->
            when (state) {
                is SimpleElectiveViewModel.State.Idle -> {}
                is SimpleElectiveViewModel.State.Loading -> {
                    binding.petLoading.root.startLoading()
                    binding.recyclerView.visibility = View.GONE
                    binding.errorLayout.visibility = View.GONE
                }
                is SimpleElectiveViewModel.State.Success -> {
                    binding.petLoading.root.stopLoading()
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.errorLayout.visibility = View.GONE
                    showCourses()
                }
                is SimpleElectiveViewModel.State.Error -> {
                    binding.petLoading.root.stopLoading()
                    binding.recyclerView.visibility = View.GONE
                    binding.errorLayout.visibility = View.VISIBLE
                    binding.tvError.text = state.message
                }
                is SimpleElectiveViewModel.State.NotOpen -> {
                    binding.petLoading.root.stopLoading()
                    binding.recyclerView.visibility = View.GONE
                    binding.errorLayout.visibility = View.VISIBLE
                    binding.tvError.text = state.message
                }
            }
        }

        viewModel.available.observe(this) { showCourses() }
        viewModel.selected.observe(this) { showCourses() }
    }

    private fun showCourses() {
        val available = viewModel.available.value ?: emptyList()
        val selected = viewModel.selected.value ?: emptyList()

        val items = mutableListOf<CourseItem>()
        if (selected.isNotEmpty()) {
            items.add(CourseItem.Header("已选课程"))
            selected.forEach { items.add(CourseItem.Data(it)) }
        }
        if (available.isNotEmpty()) {
            items.add(CourseItem.Header("可选课程"))
            available.forEach { items.add(CourseItem.Data(it)) }
        }

        if (items.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.errorLayout.visibility = View.VISIBLE
            binding.tvError.text = "暂无课程数据"
            return
        }

        binding.recyclerView.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = CourseAdapter(items)
    }

    sealed class CourseItem {
        data class Header(val title: String) : CourseItem()
        data class Data(val course: SimpleCourse) : CourseItem()
    }

    inner class CourseAdapter(
        private val items: List<CourseItem>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_HEADER = 0
        private val TYPE_COURSE = 1

        override fun getItemViewType(position: Int) = when (items[position]) {
            is CourseItem.Header -> TYPE_HEADER
            is CourseItem.Data -> TYPE_COURSE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 16
                        bottomMargin = 8
                    }
                    setTextAppearance(R.style.ClaudeOverline)
                    typeface = resources.getFont(R.font.claude_serif)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                }
                HeaderViewHolder(tv)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_simple_course, parent, false)
                CourseViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is CourseItem.Header -> (holder as HeaderViewHolder).bind(item)
                is CourseItem.Data -> (holder as CourseViewHolder).bind(item.course)
            }
        }

        override fun getItemCount() = items.size
    }

    class HeaderViewHolder(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(item: CourseItem.Header) {
            tv.text = item.title
        }
    }

    class CourseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvCode: TextView = view.findViewById(R.id.tvCode)
        private val tvTeacher: TextView = view.findViewById(R.id.tvTeacher)
        private val tvInfo: TextView = view.findViewById(R.id.tvInfo)
        private val tvCapacity: TextView = view.findViewById(R.id.tvCapacity)

        fun bind(course: SimpleCourse) {
            tvName.text = course.name
            tvCode.text = course.code

            tvTeacher.visibility = if (course.teacher.isNotBlank()) View.VISIBLE else View.GONE
            tvTeacher.text = "教师: ${course.teacher}"

            val infoParts = mutableListOf<String>()
            if (course.credits.isNotBlank()) infoParts.add("学分: ${course.credits}")
            if (course.weekHours.isNotBlank()) infoParts.add("周学时: ${course.weekHours}")
            if (course.examMethod.isNotBlank()) infoParts.add(course.examMethod)
            if (course.college.isNotBlank()) infoParts.add(course.college)
            if (course.nature.isNotBlank()) infoParts.add(course.nature)
            tvInfo.text = infoParts.joinToString("  |  ")
            tvInfo.visibility = if (infoParts.isEmpty()) View.GONE else View.VISIBLE

            if (course.capacity.isNotBlank()) {
                tvCapacity.text = "容量: ${course.capacity}"
                tvCapacity.setTextColor(
                    itemView.context.resources.getColor(R.color.claude_text_secondary, null)
                )
                tvCapacity.visibility = View.VISIBLE
            } else {
                tvCapacity.visibility = View.GONE
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SimpleElectiveActivity::class.java))
        }
    }
}
