package com.ifafu.kyzz.ui.syllabus

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
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

    private val userName: String by lazy {
        val prefs = getSharedPreferences("ifafu_user", android.content.Context.MODE_PRIVATE)
        prefs.getString("name", "") ?: ""
    }
    private var currentWeek = 1
    private var realCurrentWeek = 0
    private var maxWeek = 24
    private var minWeek = 1
    private var allCourses: List<Course> = emptyList()
    private var termStartDate: Calendar? = null

    private var yearList: List<String> = emptyList()
    private var termList: List<String> = emptyList()
    private var suppressSelection = false

    private val timeMap = mapOf(
        1 to Pair("08:00", "08:45"), 2 to Pair("08:50", "09:35"), 3 to Pair("09:55", "10:40"),
        4 to Pair("10:45", "11:30"), 5 to Pair("11:35", "12:20"), 6 to Pair("14:00", "14:45"),
        7 to Pair("14:50", "15:35"), 8 to Pair("15:50", "16:35"), 9 to Pair("16:40", "17:25"),
        10 to Pair("18:25", "19:10"), 11 to Pair("19:15", "20:00"), 12 to Pair("20:05", "20:50")
    )

    private val courseColors = listOf(
        "#D4724A", "#2D7A4F", "#B7791F", "#C53030", "#4A6FA5",
        "#6B4C9A", "#2B8A8A", "#8B5A2B", "#5B6BBF", "#CC5577",
        "#7B68EE", "#20B2AA", "#CD853F", "#708090", "#DA70D6"
    )

    private val customColors by lazy { loadCustomColors() }

    private lateinit var gestureDetector: GestureDetector

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
                    viewModel.loadSyllabus(viewModel.selectedYear, viewModel.selectedTerm, forceRefresh = true)
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
            if (currentWeek > minWeek) {
                currentWeek--
                navigateToWeek(-1)
            }
        }

        binding.btnNextWeek.setOnClickListener {
            if (currentWeek < maxWeek) {
                currentWeek++
                navigateToWeek(1)
            }
        }

        // Feature 1: Swipe gesture for week navigation
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 100 && Math.abs(velocityX) > 200) {
                    if (dx < 0 && currentWeek < maxWeek) {
                        currentWeek++
                        navigateToWeek(1)
                        return true
                    } else if (dx > 0 && currentWeek > minWeek) {
                        currentWeek--
                        navigateToWeek(-1)
                        return true
                    }
                }
                return false
            }
        })

        binding.scrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        // Feature 3: Pull-to-refresh
        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSyllabus(viewModel.selectedYear, viewModel.selectedTerm, forceRefresh = true)
        }

        binding.btnRetry.setOnClickListener {
            viewModel.loadSyllabus(viewModel.selectedYear, viewModel.selectedTerm)
        }

        // Feature 6: Week picker on title click
        binding.tvWeekTitle.setOnClickListener { showWeekPicker() }

        setupTermSelector()

        viewModel.state.observe(this) { state ->
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    hideLoading()
                    allCourses = state.data.courses
                    calculateWeekRange()
                    if (viewModel.isCurrentTerm) {
                        currentWeek = calculateCurrentWeek()
                        realCurrentWeek = currentWeek
                    } else {
                        estimateHistoricalTermStart()
                        currentWeek = 1
                        realCurrentWeek = 0
                    }
                    updateWeekDisplay()
                    updateDateRow()
                    displayCoursesForWeek()
                    autoScrollToCurrentPeriod()
                    syncTermToggle()
                    suppressSelection = false
                }
                is UiState.Cached -> {
                    hideLoading()
                    allCourses = state.data.courses
                    calculateWeekRange()
                    if (viewModel.isCurrentTerm) {
                        currentWeek = calculateCurrentWeek()
                        realCurrentWeek = currentWeek
                    } else {
                        estimateHistoricalTermStart()
                        currentWeek = 1
                        realCurrentWeek = 0
                    }
                    updateWeekDisplay()
                    updateDateRow()
                    displayCoursesForWeek()
                    autoScrollToCurrentPeriod()
                    syncTermToggle()
                    suppressSelection = false
                }
                is UiState.Error -> {
                    showError(state.message)
                    suppressSelection = false
                }
            }
        }

        viewModel.availableYears.observe(this) { years ->
            yearList = years
            setupYearSpinner()
        }

        viewModel.availableTerms.observe(this) { terms ->
            termList = terms
            setupTermButtons()
        }

        ensureTermFirstDay {
            viewModel.loadSyllabus()
        }
    }

    private fun navigateToWeek(direction: Int = 0) {
        val gridContent = binding.gridContent

        if (direction == 0) {
            updateWeekDisplay()
            updateDateRow()
            displayCoursesForWeek()
            return
        }

        val exitX = if (direction < 0) 80f else -80f

        gridContent.animate()
            .translationX(exitX)
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                updateWeekDisplay()
                updateDateRow()
                displayCoursesForWeek()
                gridContent.translationX = -exitX * 0.5f
                gridContent.scaleX = 0.95f
                gridContent.scaleY = 0.95f
                gridContent.alpha = 0f
                gridContent.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .start()
            }
            .start()
    }

    // Feature 2: Auto-scroll to current time period
    private fun autoScrollToCurrentPeriod() {
        if (currentWeek != realCurrentWeek || realCurrentWeek <= 0) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 21) return // 晚上9点后课程已结束，不自动滚动
        val rowHeight = dpToPx(60)
        val targetY = when {
            hour < 12 -> 0
            hour < 17 -> rowHeight * 5
            else -> rowHeight * 8
        }
        binding.scrollView.post { binding.scrollView.smoothScrollTo(0, targetY) }
    }

    // Feature 4: Today column detection
    private fun isTodayColumn(day: Int): Boolean {
        if (!viewModel.isCurrentTerm || currentWeek != realCurrentWeek) return false
        val today = Calendar.getInstance()
        val dayOfWeek = today.get(Calendar.DAY_OF_WEEK)
        val todayDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
        return day == todayDay
    }

    private fun getCourseColor(courseName: String): Int {
        val custom = customColors[courseName]
        if (custom != null) return Color.parseColor(custom)
        val colorIndex = (courseName.hashCode() and 0x7FFFFFFF) % courseColors.size
        return Color.parseColor(courseColors[colorIndex])
    }

    // Feature 6: Week picker
    private fun showWeekPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_week_picker, null)
        val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupWeeks)

        val weekSet = mutableSetOf<Int>()
        for (c in allCourses) {
            for (w in c.weekBegin..c.weekEnd) weekSet.add(w)
        }

        for (w in minWeek..maxWeek) {
            val chip = Chip(this).apply {
                text = "第${w}周"
                isClickable = true
                isCheckable = false
                if (w == currentWeek) {
                    setChipBackgroundColorResource(R.color.claude_terracotta)
                    setTextColor(Color.WHITE)
                } else if (w !in weekSet) {
                    alpha = 0.4f
                }
                setOnClickListener {
                    currentWeek = w
                    navigateToWeek(0)
                }
            }
            chipGroup.addView(chip)
        }

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(view)
        chipGroup.getChildAt(0)?.setOnClickListener {
            currentWeek = minWeek + chipGroup.indexOfChild(it as View)
            navigateToWeek(0)
            dialog.dismiss()
        }
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i)
            chip.setOnClickListener {
                currentWeek = minWeek + i
                navigateToWeek(0)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun setupTermSelector() {
        binding.btnTerm1.isChecked = true
        binding.toggleTerm.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressSelection) return@addOnButtonCheckedListener
            val term = when (checkedId) {
                R.id.btnTerm1 -> "1"
                R.id.btnTerm2 -> "2"
                R.id.btnTerm3 -> "3"
                else -> return@addOnButtonCheckedListener
            }
            val year = binding.spinnerYear.selectedItem?.toString() ?: return@addOnButtonCheckedListener
            if (year != viewModel.selectedYear || term != viewModel.selectedTerm) {
                suppressSelection = true
                viewModel.loadSyllabus(year, term)
            }
        }
    }

    private fun setupYearSpinner() {
        if (yearList.isEmpty()) return
        binding.termSelector.visibility = View.VISIBLE

        suppressSelection = true
        binding.spinnerYear.onItemSelectedListener = null

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, yearList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerYear.adapter = adapter

        val currentYearIdx = yearList.indexOfFirst { it == viewModel.selectedYear }
        if (currentYearIdx >= 0) {
            binding.spinnerYear.setSelection(currentYearIdx)
        }

        binding.spinnerYear.post {
            suppressSelection = false
            binding.spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressSelection) return
                    val year = yearList.getOrNull(position) ?: return
                    val term = when {
                        binding.btnTerm1.isChecked -> "1"
                        binding.btnTerm2.isChecked -> "2"
                        binding.btnTerm3.isChecked -> "3"
                        else -> "1"
                    }
                    if (year != viewModel.selectedYear || term != viewModel.selectedTerm) {
                        suppressSelection = true
                        viewModel.loadSyllabus(year, term)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun setupTermButtons() {
        if (termList.isEmpty()) return
        val termNameMap = mapOf("1" to "一", "2" to "二", "3" to "三")
        if (termList.size >= 1) {
            binding.btnTerm1.text = "第${termNameMap[termList[0]] ?: termList[0]}学期"
            binding.btnTerm1.visibility = View.VISIBLE
        }
        if (termList.size >= 2) {
            binding.btnTerm2.text = "第${termNameMap[termList[1]] ?: termList[1]}学期"
            binding.btnTerm2.visibility = View.VISIBLE
        } else {
            binding.btnTerm2.visibility = View.GONE
        }
        if (termList.size >= 3) {
            binding.btnTerm3.text = "第${termNameMap[termList[2]] ?: termList[2]}学期"
            binding.btnTerm3.visibility = View.VISIBLE
        } else {
            binding.btnTerm3.visibility = View.GONE
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

        if (firstDay.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val termStart = Calendar.getInstance()
                termStart.time = sdf.parse(firstDay) ?: return estimateWeekByMonth()
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
                    return week.coerceIn(minWeek, maxWeek)
                }
            } catch (_: Exception) {}
        }
        return estimateWeekByMonth()
    }

    private fun estimateWeekByMonth(): Int {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val year = calendar.get(Calendar.YEAR)

        // Estimate term start date based on current month
        val estimatedTermStart = when (month) {
            in Calendar.FEBRUARY..Calendar.JULY ->
                Calendar.getInstance().apply { set(year, Calendar.MARCH, 2, 0, 0, 0) }
            Calendar.JANUARY ->
                Calendar.getInstance().apply { set(year - 1, Calendar.SEPTEMBER, 1, 0, 0, 0) }
            else ->
                Calendar.getInstance().apply { set(year, Calendar.SEPTEMBER, 1, 0, 0, 0) }
        }
        estimatedTermStart.set(Calendar.MILLISECOND, 0)
        termStartDate = estimatedTermStart

        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        val diffDays = ((today.timeInMillis - estimatedTermStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
        val week = (diffDays / 7) + 1
        // Before term starts, show week 0 (will be coerced to minWeek=1)
        return week.coerceIn(minWeek, maxWeek)
    }

    private fun updateWeekDisplay() {
        val title = if (currentWeek == realCurrentWeek && realCurrentWeek > 0) {
            "第${currentWeek}周(本周)"
        } else {
            "第${currentWeek}周"
        }
        binding.tvWeekTitle.text = title
        binding.btnPrevWeek.alpha = if (currentWeek <= minWeek) 0.3f else 1.0f
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
            binding.tvDateMon, binding.tvDateTue, binding.tvDateWed,
            binding.tvDateThu, binding.tvDateFri, binding.tvDateSat, binding.tvDateSun
        )

        for (i in 0 until 7) {
            val day = monday.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, i)
            val isToday = day.timeInMillis == today.timeInMillis && viewModel.isCurrentTerm
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

        // 按列组织课程：day -> section -> courses
        val courseMap = mutableMapOf<String, MutableList<Course>>()
        for (course in weekCourses) {
            for (section in course.begin..course.end) {
                val key = "${course.weekDay}_$section"
                courseMap.getOrPut(key) { mutableListOf() }.add(course)
            }
        }

        // 第1列：节次标签
        binding.gridContent.addView(createSectionColumn())

        // 第2-8列：周一到周日
        for (day in 1..7) {
            binding.gridContent.addView(createDayColumn(day, courseMap))
        }
    }

    private fun createSectionColumn(): LinearLayout {
        val rowHeight = 60
        val col = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), LinearLayout.LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
        }
        for (section in 1..12) {
            col.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(rowHeight))
                gravity = Gravity.CENTER
                text = "$section"
                setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                textSize = 11f
                setBackgroundColor(resources.getColor(R.color.claude_bg_subtle, null))
            })
        }
        return col
    }

    private fun createDayColumn(day: Int, courseMap: Map<String, MutableList<Course>>): LinearLayout {
        val rowHeight = 60
        val todayBg = resources.getColor(R.color.claude_terracotta_100, null)
        val normalBg = resources.getColor(R.color.claude_bg_subtle, null)
        val isToday = isTodayColumn(day)

        val col = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(1)
            }
            orientation = LinearLayout.VERTICAL
        }

        var section = 1
        while (section <= 12) {
            val key = "${day}_$section"
            val courseList = courseMap[key]
            val course = courseList?.firstOrNull()

            if (course != null && course.begin == section) {
                val span = course.end - course.begin + 1
                val cellHeight = rowHeight * span
                val color = getCourseColor(course.name)

                val cellView = LayoutInflater.from(this).inflate(R.layout.grid_syllabus_cell, null)
                cellView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(cellHeight))
                cellView.findViewById<TextView>(R.id.tvCourseName).apply { text = course.name; visibility = View.VISIBLE }
                val infoParts = mutableListOf<String>()
                val startTime = timeMap[course.begin]?.first ?: ""
                val endTime = timeMap[course.end]?.second ?: ""
                if (startTime.isNotEmpty() && endTime.isNotEmpty()) infoParts.add("$startTime-$endTime")
                if (course.teacher.isNotEmpty()) infoParts.add(course.teacher)
                if (course.address.isNotEmpty()) infoParts.add(course.address)
                cellView.findViewById<TextView>(R.id.tvCourseInfo).apply { text = infoParts.joinToString("\n"); visibility = View.VISIBLE }
                cellView.setBackgroundColor(color)
                cellView.setOnClickListener {
                    showCourseDetailBottomSheet(courseList)
                }
                col.addView(cellView)
                section += span
            } else if (course != null) {
                // 不应该走到这里（被上面的span跳过），防御性处理
                col.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(rowHeight))
                    setBackgroundColor(getCourseColor(course.name))
                })
                section++
            } else {
                col.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(rowHeight))
                    setBackgroundColor(if (isToday) todayBg else normalBg)
                })
                section++
            }
        }
        return col
    }

    // Feature 5: BottomSheet course detail + Feature 9: Color customization
    private fun showCourseDetailBottomSheet(courseList: List<Course>) {
        val course = courseList.firstOrNull() ?: return
        val color = getCourseColor(course.name)
        val bottomSheet = CourseDetailBottomSheet.newInstance(courseList, color) { courseName, colorHex ->
            customColors[courseName] = colorHex
            saveCustomColors(customColors)
            displayCoursesForWeek()
        }
        bottomSheet.show(supportFragmentManager, "course_detail")
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

        val exportTimeMap = timeMap.mapValues { (_, v) ->
            Pair(v.first.replace(":", ""), v.second.replace(":", ""))
        }
        val weekDays = arrayOf("", "MO", "TU", "WE", "TH", "FR", "SA", "SU")

        for (course in allCourses.distinctBy { "${it.name}_${it.weekDay}_${it.begin}" }) {
            val dayOffset = course.weekDay - 1
            val eventCal = start.clone() as Calendar
            eventCal.add(Calendar.DAY_OF_YEAR, (course.weekBegin - 1) * 7 + dayOffset)
            val times = exportTimeMap[course.begin] ?: exportTimeMap[1]!!
            val endTime = exportTimeMap[course.end.coerceAtMost(12)]?.second ?: times.second
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(eventCal.time)
            val byDay = weekDays.getOrElse(course.weekDay) { "MO" }
            val totalWeeks = course.weekEnd - course.weekBegin + 1
            val rrule = when (course.oddOrTwice) {
                1 -> "FREQ=WEEKLY;INTERVAL=2;COUNT=${(totalWeeks + 1) / 2};BYDAY=$byDay"
                2 -> "FREQ=WEEKLY;INTERVAL=2;COUNT=${totalWeeks / 2 + 1};BYDAY=$byDay"
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

    // Feature 8: Smart share with branding header and watermark
    private fun shareSchedule() {
        if (allCourses.isEmpty()) {
            Toast.makeText(this, "暂无课程数据", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val gridWidth = binding.gridContent.width
            if (gridWidth <= 0) {
                Toast.makeText(this, "请等待课表加载完成", Toast.LENGTH_SHORT).show()
                return
            }

            val dp = resources.displayMetrics.density
            val padding = (12 * dp).toInt()
            val headerHeight = (52 * dp).toInt()
            val watermarkHeight = (36 * dp).toInt()
            val gridHeight = binding.gridContent.height
            val totalWidth = gridWidth + padding * 2
            val totalHeight = padding + headerHeight + gridHeight + watermarkHeight + padding

            val bitmap = android.graphics.Bitmap.createBitmap(totalWidth, totalHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgColor = resources.getColor(R.color.claude_bg, null)
            val terracotta = resources.getColor(R.color.claude_terracotta, null)
            val terracottaLight = resources.getColor(R.color.claude_terracotta_100, null)
            val textPrimary = resources.getColor(R.color.claude_text_primary, null)
            val textHint = resources.getColor(R.color.claude_text_hint, null)
            val white = resources.getColor(R.color.claude_bg_elevated, null)

            canvas.drawColor(bgColor)

            // Header background with rounded rect
            val headerRect = android.graphics.RectF(padding.toFloat(), padding.toFloat(),
                (totalWidth - padding).toFloat(), (padding + headerHeight).toFloat())
            android.graphics.drawable.GradientDrawable().apply {
                setColor(terracotta)
                cornerRadii = floatArrayOf(16f * dp, 16f * dp, 16f * dp, 16f * dp, 0f, 0f, 0f, 0f)
            }.apply { bounds = android.graphics.Rect(headerRect.left.toInt(), headerRect.top.toInt(), headerRect.right.toInt(), headerRect.bottom.toInt()); draw(canvas) }

            // Header text
            val headerPaint = Paint().apply {
                color = white
                textSize = 15f * dp
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val titleText = "第${currentWeek}周课表"
            val userName = this.userName
            val headerLine1 = if (userName.isNotEmpty()) "${userName}的课表" else "我的课表"
            val yearTermText = if (viewModel.selectedYear != null && viewModel.selectedTerm != null) {
                "${viewModel.selectedYear} 第${viewModel.selectedTerm}学期"
            } else ""
            canvas.drawText(headerLine1, padding + (14 * dp).toFloat(), padding + (20 * dp).toFloat(), headerPaint)
            val headerSmallPaint = Paint().apply {
                color = 0xB3FFFFFF.toInt()
                textSize = 10f * dp
                isAntiAlias = true
            }
            canvas.drawText("$yearTermText  $titleText", padding + (14 * dp).toFloat(), padding + (38 * dp).toFloat(), headerSmallPaint)

            // Grid
            canvas.save()
            canvas.translate(padding.toFloat(), (padding + headerHeight).toFloat())
            binding.gridContent.draw(canvas)
            canvas.restore()

            // Footer
            val footerY = padding + headerHeight + gridHeight
            val footerPaint = Paint().apply {
                color = textHint
                textSize = 9f * dp
                isAntiAlias = true
            }
            canvas.drawText("由 iFAFU KYZZ 生成", padding + (14 * dp).toFloat(), (footerY + 20 * dp).toFloat(), footerPaint)

            val brandPaint = Paint().apply {
                color = terracotta
                textSize = 10f * dp
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val brandText = "iFAFU"
            val brandWidth = brandPaint.measureText(brandText)
            canvas.drawText(brandText, (totalWidth - padding - 14 * dp - brandWidth), (footerY + 20 * dp).toFloat(), brandPaint)

            val file = java.io.File(cacheDir, "schedule_share.png")
            java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

            // Share with chooser
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

    // Feature 7: Shimmer loading + Feature 3: SwipeRefresh handling
    private fun showLoading() {
        binding.swipeRefresh.isRefreshing = false
        binding.shimmerLoading.root.visibility = View.VISIBLE
        (binding.shimmerLoading.root as? ShimmerFrameLayout)?.startShimmer()
        binding.petLoading.root.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun hideLoading() {
        binding.swipeRefresh.isRefreshing = false
        (binding.shimmerLoading.root as? ShimmerFrameLayout)?.stopShimmer()
        binding.shimmerLoading.root.visibility = View.GONE
        binding.petLoading.root.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = false
        (binding.shimmerLoading.root as? ShimmerFrameLayout)?.stopShimmer()
        binding.shimmerLoading.root.visibility = View.GONE
        binding.petLoading.root.stopLoading()
        binding.petLoading.root.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun syncTermToggle() {
        suppressSelection = true
        when (viewModel.selectedTerm) {
            "1" -> binding.toggleTerm.check(R.id.btnTerm1)
            "2" -> binding.toggleTerm.check(R.id.btnTerm2)
            "3" -> binding.toggleTerm.check(R.id.btnTerm3)
            else -> binding.toggleTerm.check(R.id.btnTerm1)
        }
        suppressSelection = false
    }

    private fun estimateHistoricalTermStart() {
        val year = viewModel.selectedYear ?: return
        val term = viewModel.selectedTerm ?: return
        try {
            val parts = year.split("-")
            val startYear = parts[0].toInt()
            val cal = Calendar.getInstance()
            if (term == "1") {
                cal.set(startYear, Calendar.SEPTEMBER, 1, 0, 0, 0)
            } else {
                cal.set(startYear + 1, Calendar.MARCH, 2, 0, 0, 0)
            }
            cal.set(Calendar.MILLISECOND, 0)
            termStartDate = cal
        } catch (_: Exception) {}
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun isValidDateFormat(date: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.isLenient = false
            sdf.parse(date)
            true
        } catch (_: Exception) { false }
    }

    private fun ensureTermFirstDay(onReady: () -> Unit) {
        val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val existing = prefs.getString("termFirstDay", "") ?: ""
        if (existing.isNotEmpty() && isValidDateFormat(existing) && isTermFirstDayReasonable(existing)) {
            onReady()
            return
        }
        // Term first day is stale — reset to default and clear old cache
        setDefaultTermFirstDay(prefs)
        // Clear syllabus cache so we don't show old semester data with new term start
        val account = getSharedPreferences("ifafu_user", MODE_PRIVATE).getString("account", "") ?: ""
        if (account.isNotEmpty()) {
            val cachePrefs = getSharedPreferences("ifafu_cache", MODE_PRIVATE)
            cachePrefs.edit().apply {
                remove("syllabus_$account")
                remove("syllabus_${account}_ts")
                for ((key, _) in cachePrefs.all) {
                    if (key.startsWith("syllabus_${account}_") && key != "syllabus_${account}_ts") {
                        remove(key)
                    }
                }
                apply()
            }
        }
        onReady()
    }

    private fun isTermFirstDayReasonable(dateStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val parsed = sdf.parse(dateStr) ?: return false
            val termStart = Calendar.getInstance().apply { time = parsed }
            val now = Calendar.getInstance()
            val diffWeeks = ((now.timeInMillis - termStart.timeInMillis) / (7 * 24 * 60 * 60 * 1000L)).toInt()
            diffWeeks in 0..24
        } catch (_: Exception) { false }
    }

    private fun setDefaultTermFirstDay(prefs: android.content.SharedPreferences) {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val year = calendar.get(Calendar.YEAR)
        val termStart = when (month) {
            in Calendar.FEBRUARY..Calendar.JULY ->
                Calendar.getInstance().apply { set(year, Calendar.MARCH, 2, 0, 0, 0) }
            Calendar.JANUARY ->
                Calendar.getInstance().apply { set(year - 1, Calendar.SEPTEMBER, 1, 0, 0, 0) }
            else ->
                Calendar.getInstance().apply { set(year, Calendar.SEPTEMBER, 1, 0, 0, 0) }
        }
        termStart.set(Calendar.MILLISECOND, 0)
        // Normalize to Monday of that week
        val dayOfWeek = termStart.get(Calendar.DAY_OF_WEEK)
        val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
        termStart.add(Calendar.DAY_OF_YEAR, -daysToMonday)
        termStart.set(Calendar.HOUR_OF_DAY, 0)
        termStart.set(Calendar.MINUTE, 0)
        termStart.set(Calendar.SECOND, 0)

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        prefs.edit().putString("termFirstDay", sdf.format(termStart.time)).apply()
    }

    // Feature 9: Custom color persistence
    private fun loadCustomColors(): MutableMap<String, String> {
        val prefs = getSharedPreferences("course_colors", MODE_PRIVATE)
        val json = prefs.getString("custom_colors", null) ?: return mutableMapOf()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, String>>() {}.type
            com.google.gson.Gson().fromJson(json, type) ?: mutableMapOf()
        } catch (_: Exception) { mutableMapOf() }
    }

    private fun saveCustomColors(colors: Map<String, String>) {
        val prefs = getSharedPreferences("course_colors", MODE_PRIVATE)
        prefs.edit().putString("custom_colors", com.google.gson.Gson().toJson(colors)).apply()
    }
}
