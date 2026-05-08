package com.ifafu.kyzz.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.FragmentHomeBinding
import com.ifafu.kyzz.ui.elective.ElectiveCourseActivity
import com.ifafu.kyzz.ui.login.LoginActivity
import com.ifafu.kyzz.ui.syllabus.GridSyllabusActivity
import com.ifafu.kyzz.ui.toolbox.KyzzToolboxActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeUser()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUser()
    }

    private fun setupViews() {
        binding.cardGridSyllabus.setOnClickListener {
            startActivity(Intent(requireContext(), GridSyllabusActivity::class.java))
        }
        binding.cardElective.setOnClickListener {
            startActivity(Intent(requireContext(), ElectiveCourseActivity::class.java))
        }
        binding.cardKyzzToolbox.setOnClickListener {
            startActivity(Intent(requireContext(), KyzzToolboxActivity::class.java))
        }
        binding.cardLogout.setOnClickListener { showLogoutConfirm() }
    }

    private fun observeUser() {
        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user.isLogin) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val greeting = when {
                    hour < 12 -> getString(R.string.greeting_morning, user.name)
                    hour < 18 -> getString(R.string.greeting_afternoon, user.name)
                    else -> getString(R.string.greeting_evening, user.name)
                }
                binding.tvWelcome.text = greeting
            }
            setDateInfo()
        }
        viewModel.currentWeek.observe(viewLifecycleOwner) { week ->
            if (week > 0) {
                binding.chipWeek.text = getString(R.string.chip_week, week)
                binding.chipWeek.visibility = View.VISIBLE
            } else {
                binding.chipWeek.visibility = View.GONE
            }
        }
        viewModel.todayCourses.observe(viewLifecycleOwner) { courses ->
            showTodayCourses(courses)
            binding.chipCoursesCount.text = if (courses.isNotEmpty()) {
                getString(R.string.chip_courses_today, courses.size)
            } else {
                getString(R.string.chip_no_courses_today)
            }
        }
        viewModel.nextExam.observe(viewLifecycleOwner) { countdown ->
            showExamCountdown(countdown)
            binding.chipExamCountdown.text = if (countdown != null && countdown.daysLeft >= 0) {
                getString(R.string.chip_exam_days, countdown.daysLeft)
            } else {
                getString(R.string.chip_no_exam)
            }
        }
    }

    private fun setDateInfo() {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> getString(R.string.monday)
            Calendar.TUESDAY -> getString(R.string.tuesday)
            Calendar.WEDNESDAY -> getString(R.string.wednesday)
            Calendar.THURSDAY -> getString(R.string.thursday)
            Calendar.FRIDAY -> getString(R.string.friday)
            Calendar.SATURDAY -> getString(R.string.saturday)
            Calendar.SUNDAY -> getString(R.string.sunday)
            else -> ""
        }
        binding.tvDateInfo.text = getString(R.string.date_format, month, day, dayOfWeek)
    }

    private fun showExamCountdown(countdown: MainViewModel.ExamCountdown?) {
        if (countdown == null) {
            binding.cardExamCountdown.visibility = View.GONE
            return
        }
        binding.cardExamCountdown.visibility = View.VISIBLE
        binding.cardExamCountdown.alpha = 1f
        binding.cardExamCountdown.startAnimation(
            AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_in)
        )
        val container = binding.examCountdownContainer
        container.removeAllViews()

        val exam = countdown.exam
        val countdownText = when {
            countdown.daysLeft < 0 -> "已结束"
            countdown.daysLeft == 0 -> "今天"
            countdown.daysLeft == 1 -> "明天"
            else -> "${countdown.daysLeft}天后"
        }

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = TextView(requireContext()).apply {
            text = exam.name
            setTextAppearance(R.style.ClaudeBody)
            setTextColor(resources.getColor(R.color.claude_text_primary, null))
            typeface = resources.getFont(R.font.claude_serif)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countdownTv = TextView(requireContext()).apply {
            text = countdownText
            textSize = 16f
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            typeface = resources.getFont(R.font.claude_serif)
        }
        row.addView(name)
        row.addView(countdownTv)
        container.addView(row)

        val detail = TextView(requireContext()).apply {
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
            binding.emptyCoursesState.visibility = View.VISIBLE
            binding.emptyCoursesState.alpha = 1f
            binding.emptyCoursesState.startAnimation(
                AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_in)
            )
            binding.tvTodaySummary.text = getString(R.string.chip_no_courses_today)
            return
        }
        binding.emptyCoursesState.visibility = View.GONE
        binding.tvTodaySection.visibility = View.VISIBLE
        binding.cardTodayCourses.visibility = View.VISIBLE
        binding.cardTodayCourses.alpha = 1f
        binding.cardTodayCourses.startAnimation(
            AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_in)
        )
        binding.tvTodaySummary.text = "今天有 ${courses.size} 节课"

        val container = binding.todayCoursesContainer
        container.removeAllViews()

        for ((i, course) in courses.withIndex()) {
            if (i > 0) {
                container.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { topMargin = 6; bottomMargin = 6 }
                    setBackgroundColor(resources.getColor(R.color.claude_border, null))
                })
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(requireContext()).apply {
                text = course.name
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val info = TextView(requireContext()).apply {
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
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.confirm_logout)
            .setPositiveButton(R.string.nav_logout) { _, _ ->
                viewModel.logout()
                startActivity(Intent(requireContext(), LoginActivity::class.java))
                requireActivity().finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
