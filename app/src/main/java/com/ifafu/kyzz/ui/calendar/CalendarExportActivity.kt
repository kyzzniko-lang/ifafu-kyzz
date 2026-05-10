package com.ifafu.kyzz.ui.calendar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.databinding.ActivityCalendarExportBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CalendarExportActivity : BaseActivity<ActivityCalendarExportBinding>() {

    override fun createBinding() = ActivityCalendarExportBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val userPrefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        val account = userPrefs.getString("account", "") ?: ""
        val termFirstDay = userPrefs.getString("termFirstDay", "") ?: ""

        val cachePrefs = getSharedPreferences("ifafu_cache", MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val json = cachePrefs.getString("syllabus_$account", null)
        val syllabus = if (json != null) {
            try { gson.fromJson(json, Syllabus::class.java) } catch (_: Exception) { null }
        } else null

        if (syllabus == null || syllabus.courses.isEmpty()) {
            binding.tvCourseCount.text = "0"
            binding.tvStatus.text = "暂无课表数据，请先刷新课表"
            binding.btnExport.isEnabled = false
            binding.btnShare.isEnabled = false
            return
        }

        val courseCount = syllabus.courses.distinctBy { "${it.name}_${it.weekDay}_${it.begin}" }.size
        binding.tvCourseCount.text = courseCount.toString()

        binding.btnExport.setOnClickListener { export(syllabus, termFirstDay, openCalendar = true) }
        binding.btnShare.setOnClickListener { export(syllabus, termFirstDay, openCalendar = false) }
    }

    private fun export(syllabus: Syllabus, termFirstDay: String, openCalendar: Boolean) {
        if (termFirstDay.isEmpty()) {
            Toast.makeText(this, "请先设置学期首日", Toast.LENGTH_SHORT).show()
            return
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val parsed = sdf.parse(termFirstDay)
        if (parsed == null) {
            Toast.makeText(this, "学期首日格式错误", Toast.LENGTH_SHORT).show()
            return
        }
        val start = Calendar.getInstance().apply { time = parsed }

        val timeMap = mapOf(
            1 to Pair("0800", "0845"), 2 to Pair("0855", "0940"), 3 to Pair("1010", "1055"),
            4 to Pair("1105", "1150"), 5 to Pair("1200", "1245"), 6 to Pair("1400", "1445"), 7 to Pair("1455", "1540"),
            8 to Pair("1600", "1645"), 9 to Pair("1655", "1740"),
            10 to Pair("1900", "1945"), 11 to Pair("1955", "2040"), 12 to Pair("2050", "2135")
        )
        val weekDays = arrayOf("", "MO", "TU", "WE", "TH", "FR", "SA", "SU")

        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//iFAFU//KYZZ//CN")

        for (course in syllabus.courses.distinctBy { "${it.name}_${it.weekDay}_${it.begin}" }) {
            val dayOffset = course.weekDay - 1
            val eventCal = start.clone() as Calendar
            eventCal.add(Calendar.DAY_OF_YEAR, (course.weekBegin - 1) * 7 + dayOffset)
            val times = timeMap[course.begin] ?: timeMap[1]!!
            val endTime = timeMap[course.end.coerceAtMost(12)]?.second ?: times.second
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
            val file = File(cacheDir, "schedule.ics")
            file.writeText(sb.toString())

            if (openCalendar) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/calendar")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/calendar"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享课表"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
