package com.ifafu.kyzz.ui.calendar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.util.SyllabusCalendarExporter
import com.ifafu.kyzz.data.model.scheduleIdentity
import com.ifafu.kyzz.databinding.ActivityCalendarExportBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import java.io.File

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

        val courseCount = syllabus.courses.distinctBy { it.scheduleIdentity() }.size
        binding.tvCourseCount.text = courseCount.toString()

        binding.btnExport.setOnClickListener { export(syllabus, termFirstDay, openCalendar = true) }
        binding.btnShare.setOnClickListener { export(syllabus, termFirstDay, openCalendar = false) }
    }

    private fun export(syllabus: Syllabus, termFirstDay: String, openCalendar: Boolean) {
        if (termFirstDay.isEmpty()) {
            Toast.makeText(this, "请先设置学期首日", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val file = File(cacheDir, "schedule.ics")
            file.writeText(SyllabusCalendarExporter.export(syllabus, termFirstDay))

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
