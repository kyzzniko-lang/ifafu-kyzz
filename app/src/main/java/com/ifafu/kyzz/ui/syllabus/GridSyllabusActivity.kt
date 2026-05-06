package com.ifafu.kyzz.ui.syllabus

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.databinding.ActivityGridSyllabusBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class GridSyllabusActivity : BaseActivity<ActivityGridSyllabusBinding>() {

    private val viewModel: SyllabusViewModel by viewModels()
    private var currentWeek = 1
    private var maxWeek = 24
    private var minWeek = 1
    private var allCourses: List<Course> = emptyList()

    private val courseColors = listOf(
        "#D4724A", "#2D7A4F", "#B7791F", "#C53030", "#4A6FA5",
        "#6B4C9A", "#2B8A8A", "#8B5A2B", "#5B6BBF", "#CC5577",
        "#7B68EE", "#20B2AA", "#CD853F", "#708090", "#DA70D6"
    )

    override fun createBinding(): ActivityGridSyllabusBinding =
        ActivityGridSyllabusBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.title = getString(R.string.grid_syllabus_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnPrevWeek.setOnClickListener {
            if (currentWeek > 1) {
                currentWeek--
                updateWeekDisplay()
                displayCoursesForWeek()
            }
        }

        binding.btnNextWeek.setOnClickListener {
            if (currentWeek < maxWeek) {
                currentWeek++
                updateWeekDisplay()
                displayCoursesForWeek()
            }
        }

        binding.btnRetry.setOnClickListener { viewModel.loadSyllabus() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is SyllabusViewModel.SyllabusState.Idle -> {}
                is SyllabusViewModel.SyllabusState.Loading -> showLoading()
                is SyllabusViewModel.SyllabusState.Success -> {
                    hideLoading()
                    allCourses = state.syllabus.courses
                    calculateWeekRange()
                    currentWeek = calculateCurrentWeek()
                    updateWeekDisplay()
                    displayCoursesForWeek()
                }
                is SyllabusViewModel.SyllabusState.Error -> showError(state.message)
            }
        }

        ensureTermFirstDay {
            viewModel.loadSyllabus()
        }
    }

    private fun calculateWeekRange() {
        if (allCourses.isEmpty()) {
            minWeek = 1
            maxWeek = 20
            return
        }

        minWeek = allCourses.minOf { it.weekBegin }.coerceAtLeast(1)
        maxWeek = allCourses.maxOf { it.weekEnd }.coerceAtMost(24)

        if (minWeek > maxWeek) {
            minWeek = 1
            maxWeek = 20
        }
    }

    private fun calculateCurrentWeek(): Int {
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val firstDay = prefs.getString("termFirstDay", "") ?: ""
        android.util.Log.d("GridSyllabus", "termFirstDay from prefs: '$firstDay'")

        if (firstDay.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val termStart = Calendar.getInstance()
                termStart.time = sdf.parse(firstDay) ?: run {
                    android.util.Log.e("GridSyllabus", "Failed to parse termFirstDay: $firstDay")
                    return estimateWeekByMonth()
                }
                termStart.set(Calendar.HOUR_OF_DAY, 0)
                termStart.set(Calendar.MINUTE, 0)
                termStart.set(Calendar.SECOND, 0)
                termStart.set(Calendar.MILLISECOND, 0)

                val today = Calendar.getInstance()
                today.set(Calendar.HOUR_OF_DAY, 0)
                today.set(Calendar.MINUTE, 0)
                today.set(Calendar.SECOND, 0)
                today.set(Calendar.MILLISECOND, 0)

                val diffMillis = today.timeInMillis - termStart.timeInMillis
                if (diffMillis >= 0) {
                    val diffDays = (diffMillis / (24 * 60 * 60 * 1000)).toInt()
                    val week = (diffDays / 7) + 1
                    android.util.Log.d("GridSyllabus", "Calculated week: $week (diffDays=$diffDays, termStart=$firstDay)")
                    return week.coerceIn(minWeek, maxWeek)
                } else {
                    android.util.Log.w("GridSyllabus", "Term start is in the future: $firstDay")
                }
            } catch (e: Exception) {
                android.util.Log.e("GridSyllabus", "Error calculating week", e)
            }
        }

        val estimated = estimateWeekByMonth()
        android.util.Log.d("GridSyllabus", "Using estimated week: $estimated (no termFirstDay)")
        return estimated
    }

    private fun estimateWeekByMonth(): Int {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val weekOfYear = calendar.get(Calendar.WEEK_OF_YEAR)

        return if (month in 1..6) {
            val week = weekOfYear - 5
            week.coerceIn(minWeek, maxWeek)
        } else {
            val week = weekOfYear - 35
            week.coerceIn(minWeek, maxWeek)
        }
    }

    private fun updateWeekDisplay() {
        binding.tvWeekTitle.text = "第${currentWeek}周"
        binding.btnPrevWeek.alpha = if (currentWeek <= 1) 0.3f else 1.0f
        binding.btnNextWeek.alpha = if (currentWeek >= maxWeek) 0.3f else 1.0f
    }

    private fun displayCoursesForWeek() {
        binding.gridContent.removeAllViews()

        val weekCourses = allCourses.filter { course ->
            currentWeek >= course.weekBegin && currentWeek <= course.weekEnd &&
            (course.oddOrTwice == 0 ||
             (course.oddOrTwice == 1 && currentWeek % 2 == 1) ||
             (course.oddOrTwice == 2 && currentWeek % 2 == 0))
        }

        val courseMap = mutableMapOf<String, Course>()
        for (course in weekCourses) {
            for (section in course.begin..course.end) {
                val key = "${course.weekDay}_$section"
                courseMap[key] = course
            }
        }

        // 上午 1-4 节
        for (sectionNum in 1..4) {
            val row = createRow(sectionNum, courseMap)
            binding.gridContent.addView(row)
        }

        // 午休分隔
        val breakRow = createBreakRow()
        binding.gridContent.addView(breakRow)

        // 下午 6-9 节
        for (sectionNum in 6..9) {
            val row = createRow(sectionNum, courseMap)
            binding.gridContent.addView(row)
        }

        // 晚上 10-12 节
        for (sectionNum in 10..12) {
            val row = createRow(sectionNum, courseMap)
            binding.gridContent.addView(row)
        }
    }

    private fun createBreakRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(32)
            )
            orientation = LinearLayout.HORIZONTAL
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(32))
            gravity = Gravity.CENTER
            text = "午休"
            setTextColor(resources.getColor(R.color.claude_text_hint, null))
            textSize = 11f
            setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
        }
        row.addView(label)

        for (day in 1..7) {
            val cell = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply {
                    marginStart = dpToPx(1)
                }
                setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
            }
            row.addView(cell)
        }

        return row
    }

    private fun createRow(sectionNum: Int, courseMap: Map<String, Course>): LinearLayout {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(80)
            )
            orientation = LinearLayout.HORIZONTAL
        }

        val sectionLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(80))
            gravity = Gravity.CENTER
            text = "${sectionNum}"
            setTextColor(resources.getColor(R.color.claude_text_secondary, null))
            textSize = 12f
            setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
        }
        row.addView(sectionLabel)

        for (day in 1..7) {
            val key = "${day}_${sectionNum}"
            val course = courseMap[key]

            val cellContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(80), 1f).apply {
                    marginStart = dpToPx(1)
                }
            }

            if (course != null && sectionNum == course.begin) {
                val colorIndex = (course.name.hashCode() and 0x7FFFFFFF) % courseColors.size
                val color = Color.parseColor(courseColors[colorIndex])

                val cellView = LayoutInflater.from(this).inflate(R.layout.grid_syllabus_cell, null)
                cellView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )

                val tvName = cellView.findViewById<TextView>(R.id.tvCourseName)
                val tvLocation = cellView.findViewById<TextView>(R.id.tvCourseLocation)

                tvName.text = course.name
                tvName.visibility = View.VISIBLE
                tvLocation.text = if (course.address.isNotEmpty()) "@${course.address}" else ""
                tvLocation.visibility = View.VISIBLE
                cellView.setBackgroundColor(color)

                cellContainer.addView(cellView)
            } else if (course != null) {
                val colorIndex = (course.name.hashCode() and 0x7FFFFFFF) % courseColors.size
                val color = Color.parseColor(courseColors[colorIndex])
                val continuationView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(color)
                }
                cellContainer.addView(continuationView)
            } else {
                val emptyView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
                }
                cellContainer.addView(emptyView)
            }

            row.addView(cellContainer)
        }

        return row
    }

    private fun showLoading() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
    }

    private fun hideLoading() {
        binding.loadingLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loadingLayout.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun isValidDateFormat(date: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.isLenient = false
            sdf.parse(date)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureTermFirstDay(onReady: () -> Unit) {
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val existing = prefs.getString("termFirstDay", "") ?: ""
        android.util.Log.d("GridSyllabus", "ensureTermFirstDay: existing='$existing'")

        if (existing.isNotEmpty() && isValidDateFormat(existing)) {
            onReady()
            return
        }

        setDefaultTermFirstDay(prefs)
        onReady()
    }

    private fun setDefaultTermFirstDay(prefs: android.content.SharedPreferences) {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val year = calendar.get(Calendar.YEAR)

        val termStart = if (month in 1..6) {
            Calendar.getInstance().apply {
                set(year, Calendar.MARCH, 2, 0, 0, 0)
            }
        } else {
            Calendar.getInstance().apply {
                set(year, Calendar.SEPTEMBER, 1, 0, 0, 0)
            }
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val defaultDate = sdf.format(termStart.time)
        android.util.Log.d("GridSyllabus", "setDefaultTermFirstDay: $defaultDate")
        prefs.edit().putString("termFirstDay", defaultDate).apply()
    }
}
