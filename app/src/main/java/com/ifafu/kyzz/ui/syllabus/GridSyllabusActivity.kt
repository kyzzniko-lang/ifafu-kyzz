package com.ifafu.kyzz.ui.syllabus

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.google.android.material.button.MaterialButton
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.databinding.ActivityGridSyllabusBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class GridSyllabusActivity : BaseActivity<ActivityGridSyllabusBinding>() {

    private val viewModel: SyllabusViewModel by viewModels()
    private var currentWeek = 1
    private var realCurrentWeek = 0
    private var maxWeek = 24
    private var minWeek = 1
    private var allCourses: List<Course> = emptyList()
    private var termStartDate: Calendar? = null

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
        binding.toolbar.inflateMenu(R.menu.menu_grid_syllabus)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    viewModel.loadSyllabus(forceRefresh = true)
                    true
                }
                R.id.action_export -> {
                    exportToCalendar()
                    true
                }
                R.id.action_share -> {
                    shareSchedule()
                    true
                }
                else -> false
            }
        }

        binding.btnPrevWeek.setOnClickListener {
            if (currentWeek > 1) {
                currentWeek--
                updateWeekDisplay()
                updateDateRow()
                displayCoursesForWeek()
            }
        }

        binding.btnNextWeek.setOnClickListener {
            if (currentWeek < maxWeek) {
                currentWeek++
                updateWeekDisplay()
                updateDateRow()
                displayCoursesForWeek()
            }
        }

        binding.btnRetry.setOnClickListener { viewModel.loadSyllabus() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    hideLoading()
                    allCourses = state.data.courses
                    calculateWeekRange()
                    currentWeek = calculateCurrentWeek()
                    realCurrentWeek = currentWeek
                    updateWeekDisplay()
                    updateDateRow()
                    displayCoursesForWeek()
                }
                is UiState.Cached -> {
                    hideLoading()
                    allCourses = state.data.courses
                    calculateWeekRange()
                    currentWeek = calculateCurrentWeek()
                    realCurrentWeek = currentWeek
                    updateWeekDisplay()
                    updateDateRow()
                    displayCoursesForWeek()
                }
                is UiState.Error -> showError(state.message)
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

                termStartDate = termStart.clone() as Calendar

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
        val title = if (currentWeek == realCurrentWeek && realCurrentWeek > 0) {
            "第${currentWeek}周(本周)"
        } else {
            "第${currentWeek}周"
        }
        binding.tvWeekTitle.text = title
        binding.btnPrevWeek.alpha = if (currentWeek <= 1) 0.3f else 1.0f
        binding.btnNextWeek.alpha = if (currentWeek >= maxWeek) 0.3f else 1.0f
    }

    private fun updateDateRow() {
        val start = termStartDate ?: return

        val monday = start.clone() as Calendar
        monday.add(Calendar.DAY_OF_YEAR, (currentWeek - 1) * 7)

        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        val dateViews = listOf(
            binding.tvDateMon,
            binding.tvDateTue,
            binding.tvDateWed,
            binding.tvDateThu,
            binding.tvDateFri,
            binding.tvDateSat,
            binding.tvDateSun
        )

        for (i in 0 until 7) {
            val day = monday.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, i)
            val isToday = day.timeInMillis == today.timeInMillis
            dateViews[i].text = "${day.get(Calendar.MONTH) + 1}/${day.get(Calendar.DAY_OF_MONTH)}"
            if (isToday) {
                dateViews[i].setTextColor(Color.WHITE)
                dateViews[i].setTypeface(null, android.graphics.Typeface.BOLD)
                dateViews[i].setBackgroundResource(R.drawable.bg_today_highlight)
            } else {
                dateViews[i].setTextColor(resources.getColor(R.color.claude_text_hint, null))
                dateViews[i].setTypeface(null, android.graphics.Typeface.NORMAL)
                dateViews[i].setBackgroundResource(0)
            }
        }
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

        // 上午 1-5 节
        for (sectionNum in 1..5) {
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
                dpToPx(28)
            )
            orientation = LinearLayout.HORIZONTAL
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
            gravity = Gravity.CENTER
            text = "午休"
            setTextColor(resources.getColor(R.color.claude_text_hint, null))
            textSize = 10f
            setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
        }
        row.addView(label)

        for (day in 1..7) {
            val cell = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(28), 1f).apply {
                    marginStart = dpToPx(1)
                }
                setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
            }
            row.addView(cell)
        }

        return row
    }

    private fun createRow(sectionNum: Int, courseMap: Map<String, Course>): LinearLayout {
        val rowHeight = 60
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(rowHeight)
            )
            orientation = LinearLayout.HORIZONTAL
        }

        val sectionLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(rowHeight))
            gravity = Gravity.CENTER
            text = "${sectionNum}"
            setTextColor(resources.getColor(R.color.claude_text_secondary, null))
            textSize = 11f
            setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
        }
        row.addView(sectionLabel)

        for (day in 1..7) {
            val key = "${day}_${sectionNum}"
            val course = courseMap[key]

            val cellContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(rowHeight), 1f).apply {
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
                val tvInfo = cellView.findViewById<TextView>(R.id.tvCourseInfo)

                tvName.text = course.name
                tvName.visibility = View.VISIBLE

                val infoParts = mutableListOf<String>()
                if (course.teacher.isNotEmpty()) infoParts.add(course.teacher)
                if (course.address.isNotEmpty()) infoParts.add(course.address)
                tvInfo.text = infoParts.joinToString("\n")
                tvInfo.visibility = View.VISIBLE

                cellView.setBackgroundColor(color)
                cellView.setOnClickListener { showCourseDetail(course) }

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
                continuationView.setOnClickListener { showCourseDetail(course) }
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

    private fun showCourseDetail(course: Course) {
        val view = layoutInflater.inflate(R.layout.dialog_course_detail, null)

        view.findViewById<TextView>(R.id.tvCourseName).text = course.name
        view.findViewById<TextView>(R.id.tvTeacher).text = course.teacher.ifEmpty { "-" }
        view.findViewById<TextView>(R.id.tvAddress).text = course.address.ifEmpty { "-" }

        val weekDays = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val dayText = if (course.weekDay in 1..7) weekDays[course.weekDay] else ""
        val oddText = when (course.oddOrTwice) {
            1 -> " 单周"
            2 -> " 双周"
            else -> ""
        }
        view.findViewById<TextView>(R.id.tvTime).text =
            "第${course.weekBegin}-${course.weekEnd}周 $dayText 第${course.begin}-${course.end}节$oddText"

        val examSection = view.findViewById<android.view.ViewGroup>(R.id.examSection)
        if (course.examDate.isNotEmpty()) {
            examSection.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tvExamDate).text = course.examDate
            view.findViewById<TextView>(R.id.tvExamTime).text = course.examTime.ifEmpty { "-" }
            view.findViewById<TextView>(R.id.tvExamAddress).text = course.examAddress.ifEmpty { "-" }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun exportToCalendar() {
        val start = termStartDate ?: run {
            Toast.makeText(this, "请先设置学期首日", Toast.LENGTH_SHORT).show()
            return
        }
        if (allCourses.isEmpty()) {
            Toast.makeText(this, "暂无课程数据", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//iFAFU//KYZZ//CN")

        val timeMap = mapOf(
            1 to Pair("0800", "0845"), 2 to Pair("0855", "0940"), 3 to Pair("1010", "1055"),
            4 to Pair("1105", "1150"), 5 to Pair("1200", "1245"), 6 to Pair("1400", "1445"), 7 to Pair("1455", "1540"),
            8 to Pair("1600", "1645"), 9 to Pair("1655", "1740"),
            10 to Pair("1900", "1945"), 11 to Pair("1955", "2040"), 12 to Pair("2050", "2135")
        )

        val weekDays = arrayOf("", "MO", "TU", "WE", "TH", "FR", "SA", "SU")

        for (course in allCourses.distinctBy { "${it.name}_${it.weekDay}_${it.begin}" }) {
            val dayOffset = course.weekDay - 1
            val eventCal = start.clone() as java.util.Calendar
            eventCal.add(java.util.Calendar.DAY_OF_YEAR, (course.weekBegin - 1) * 7 + dayOffset)

            val times = timeMap[course.begin] ?: timeMap[1]!!
            val endTime = timeMap[course.end.coerceAtMost(12)]?.second ?: times.second

            val dateStr = java.text.SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(eventCal.time)
            val byDay = weekDays.getOrElse(course.weekDay) { "MO" }
            val totalWeeks = course.weekEnd - course.weekBegin + 1
            val rrule = when (course.oddOrTwice) {
                1 -> "FREQ=WEEKLY;INTERVAL=2;COUNT=${(totalWeeks + 1) / 2};BYDAY=$byDay"
                2 -> "FREQ=WEEKLY;INTERVAL=2;COUNT=${totalWeeks / 2};BYDAY=$byDay"
                else -> "FREQ=WEEKLY;COUNT=$totalWeeks;BYDAY=$byDay"
            }

            sb.appendLine("BEGIN:VEVENT")
            sb.appendLine("DTSTART:${dateStr}T${times.first}00")
            sb.appendLine("DTEND:${dateStr}T${endTime}00")
            sb.appendLine("RRULE:$rrule")
            sb.appendLine("SUMMARY:${course.name}")
            sb.appendLine("LOCATION:${course.address}")
            sb.appendLine("DESCRIPTION:${course.teacher}")
            sb.appendLine("END:VEVENT")
        }

        sb.appendLine("END:VCALENDAR")

        try {
            val file = java.io.File(cacheDir, "schedule.ics")
            file.writeText(sb.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "导出课表到日历"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSchedule() {
        val bitmap = captureView(binding.scrollView)
        if (bitmap == null) { Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show(); return }
        try {
            val file = java.io.File(cacheDir, "schedule_share.png")
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "分享课表"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureView(view: View): android.graphics.Bitmap? {
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (_: Exception) { null }
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
