package com.ifafu.kyzz.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityMainBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.elective.ElectiveCourseActivity
import com.ifafu.kyzz.ui.exam.ExamActivity
import com.ifafu.kyzz.ui.login.LoginActivity
import com.ifafu.kyzz.ui.score.ScoreActivity
import com.ifafu.kyzz.ui.syllabus.GridSyllabusActivity
import com.ifafu.kyzz.ui.syllabus.SyllabusActivity
import com.ifafu.kyzz.ui.toolbox.KyzzToolboxActivity
import com.ifafu.kyzz.ui.toolbox.ToolboxActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val viewModel: MainViewModel by viewModels()
    private var firstClickBack: Long = 0

    override fun createBinding(): ActivityMainBinding = ActivityMainBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupViews()
        observeUser()
        scheduleCourseReminder()
    }

    private fun scheduleCourseReminder() {
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager

        val courseIntent = android.content.Intent(this, com.ifafu.kyzz.service.CourseReminderReceiver::class.java)
        val coursePending = android.app.PendingIntent.getBroadcast(
            this, 0, courseIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val courseCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 30); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, courseCal.timeInMillis, android.app.AlarmManager.INTERVAL_DAY, coursePending)

        val scoreIntent = android.content.Intent(this, com.ifafu.kyzz.service.ScoreCheckReceiver::class.java)
        val scorePending = android.app.PendingIntent.getBroadcast(
            this, 1, scoreIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val scoreCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, scoreCal.timeInMillis, android.app.AlarmManager.INTERVAL_DAY, scorePending)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUser()
        if (!viewModel.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setupViews() {
        binding.cardSyllabus.setOnClickListener {
            startActivity(Intent(this, SyllabusActivity::class.java))
        }
        binding.cardGridSyllabus.setOnClickListener {
            startActivity(Intent(this, GridSyllabusActivity::class.java))
        }
        binding.cardScore.setOnClickListener {
            startActivity(Intent(this, ScoreActivity::class.java))
        }
        binding.cardExam.setOnClickListener {
            startActivity(Intent(this, ExamActivity::class.java))
        }
        binding.cardElective.setOnClickListener {
            startActivity(Intent(this, ElectiveCourseActivity::class.java))
        }
        binding.cardToolbox.setOnClickListener {
            startActivity(Intent(this, ToolboxActivity::class.java))
        }
        binding.cardKyzzToolbox.setOnClickListener {
            startActivity(Intent(this, KyzzToolboxActivity::class.java))
        }
        binding.cardLogout.setOnClickListener { showLogoutConfirm() }
    }

    private fun observeUser() {
        viewModel.user.observe(this) { user ->
            if (user.isLogin) {
                binding.tvWelcome.text = getString(R.string.main_welcome, user.name)
            }
        }
        viewModel.todayCourses.observe(this) { courses ->
            showTodayCourses(courses)
        }
        viewModel.nextExam.observe(this) { countdown ->
            showExamCountdown(countdown)
        }
    }

    private fun showExamCountdown(countdown: MainViewModel.ExamCountdown?) {
        if (countdown == null) {
            binding.cardExamCountdown.visibility = View.GONE
            return
        }
        binding.cardExamCountdown.visibility = View.VISIBLE
        val container = binding.examCountdownContainer
        container.removeAllViews()

        val exam = countdown.exam
        val countdownText = when {
            countdown.daysLeft < 0 -> "已结束"
            countdown.daysLeft == 0 -> "今天"
            countdown.daysLeft == 1 -> "明天"
            else -> "${countdown.daysLeft}天后"
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(this).apply {
            text = exam.name
            setTextAppearance(R.style.ClaudeBody)
            setTextColor(resources.getColor(R.color.claude_text_primary, null))
            typeface = resources.getFont(R.font.claude_serif)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countdownTv = TextView(this).apply {
            text = countdownText
            textSize = 16f
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            typeface = resources.getFont(R.font.claude_serif)
        }
        row.addView(name)
        row.addView(countdownTv)
        container.addView(row)

        val detail = TextView(this).apply {
            text = "${exam.datetime}  ${exam.address}"
            setTextAppearance(R.style.ClaudeCaption)
            typeface = resources.getFont(R.font.claude_serif)
            setPadding(0, 4, 0, 0)
        }
        container.addView(detail)
    }

    private fun showTodayCourses(courses: List<MainViewModel.TodayCourse>) {
        if (courses.isEmpty()) {
            binding.cardTodayCourses.visibility = View.GONE
            binding.tvTodaySection.visibility = View.GONE
            binding.tvTodaySummary.text = "今天没有课程"
            return
        }
        binding.tvTodaySection.visibility = View.VISIBLE
        binding.cardTodayCourses.visibility = View.VISIBLE
        binding.tvTodaySummary.text = "今天有 ${courses.size} 节课"

        val container = binding.todayCoursesContainer
        container.removeAllViews()

        for ((i, course) in courses.withIndex()) {
            if (i > 0) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 6; bottomMargin = 6 }
                    setBackgroundColor(resources.getColor(R.color.claude_border, null))
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = course.name
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val info = TextView(this).apply {
                text = "第${course.begin}-${course.end}节 ${course.address}"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }
            row.addView(name)
            row.addView(info)
            container.addView(row)
        }
    }

    private fun showLogoutConfirm() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_logout)
            .setPositiveButton(R.string.nav_logout) { _, _ ->
                viewModel.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (System.currentTimeMillis() - firstClickBack > 2000) {
                Toast.makeText(this, "再次按下返回键退出程序", Toast.LENGTH_SHORT).show()
                firstClickBack = System.currentTimeMillis()
            } else {
                finishAffinity()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
