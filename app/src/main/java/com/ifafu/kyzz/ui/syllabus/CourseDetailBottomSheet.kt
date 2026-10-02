package com.ifafu.kyzz.ui.syllabus

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Course
import com.ifafu.kyzz.databinding.DialogCourseDetailSheetBinding

/**
 * 课程自定义颜色变化的回调通道。实现方为宿主 [GridSyllabusActivity]，
 * 由 BottomSheet 在 onAttach 时发现——lambda 存字段的方式在旋转重建后
 * （系统以无参构造恢复、字段为 null）会静默丢失回调，自定义颜色不落盘。
 */
interface OnCourseColorChangedListener {
    fun onCourseColorChanged(courseName: String, colorHex: String)
}

class CourseDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogCourseDetailSheetBinding? = null
    private val binding get() = _binding!!

    private var courses: List<Course> = emptyList()
    private var courseColor: Int = 0
    private var colorChangedListener: OnCourseColorChangedListener? = null

    private val timeMap = mapOf(
        1 to "08:00-08:45", 2 to "08:50-09:35", 3 to "09:55-10:40",
        4 to "10:45-11:30", 5 to "11:35-12:20", 6 to "14:00-14:45",
        7 to "14:50-15:35", 8 to "15:50-16:35", 9 to "16:40-17:25",
        10 to "18:25-19:10", 11 to "19:15-20:00", 12 to "20:05-20:50"
    )

    private val colorPalette = listOf(
        "#B65D3A", "#5B7A5E", "#8A6A3D", "#4F6B87", "#8C5267",
        "#3F7472", "#975A3E", "#665E8C", "#6E7340", "#9C5450",
        "#4E6A5B", "#7A6250", "#566F8E", "#8F6638", "#6E5A78",
        "#C4693F", "#3E5F75", "#7D4F5E", "#5F7A4A", "#A0663F",
        "#4A5568", "#85574A", "#5A7D7C", "#6B5B4B"
    )

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        // 每次附着（含旋转后系统重建）都从宿主重新发现监听器，
        // 保证回调通道可重建；宿主未实现时保持 null，与旧行为一致地忽略。
        colorChangedListener = context as? OnCourseColorChangedListener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            courseColor = args.getInt(ARG_COLOR, 0)
            val json = args.getString(ARG_COURSES, null)
            if (json != null) {
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<Course>>() {}.type
                    courses = com.google.gson.Gson().fromJson(json, type) ?: emptyList()
                } catch (_: Exception) { courses = emptyList() }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogCourseDetailSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val course = courses.firstOrNull() ?: return
        val isConflict = courses.size > 1

        binding.colorBar.setBackgroundColor(courseColor)
        binding.tvCourseName.text = if (isConflict) "冲突: ${courses.joinToString("、") { it.name }}" else course.name
        binding.tvTeacher.text = if (isConflict) courses.joinToString("\n") { it.teacher.ifEmpty { "-" } } else course.teacher.ifEmpty { "-" }
        binding.tvAddress.text = if (isConflict) courses.joinToString("\n") { it.address.ifEmpty { "-" } } else course.address.ifEmpty { "-" }

        val weekDays = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        binding.tvTime.text = if (isConflict) {
            courses.joinToString("\n") { c ->
                val dayText = if (c.weekDay in 1..7) weekDays[c.weekDay] else ""
                val oddText = when (c.oddOrTwice) { 1 -> " 单周"; 2 -> " 双周"; else -> "" }
                val clockTime = buildClockTime(c.begin, c.end)
                "第${c.weekBegin}-${c.weekEnd}周 $dayText 第${c.begin}-${c.end}节 $clockTime$oddText"
            }
        } else {
            val dayText = if (course.weekDay in 1..7) weekDays[course.weekDay] else ""
            val oddText = when (course.oddOrTwice) { 1 -> " 单周"; 2 -> " 双周"; else -> "" }
            val clockTime = buildClockTime(course.begin, course.end)
            "第${course.weekBegin}-${course.weekEnd}周 $dayText 第${course.begin}-${course.end}节 $clockTime$oddText"
        }

        if (!isConflict && course.examDate.isNotEmpty()) {
            binding.examSection.visibility = View.VISIBLE
            binding.tvExamDate.text = course.examDate
            binding.tvExamTime.text = course.examTime.ifEmpty { "-" }
            binding.tvExamAddress.text = course.examAddress.ifEmpty { "-" }
        }

        if (!isConflict) {
            binding.btnChangeColor.visibility = View.VISIBLE
        }

        binding.btnChangeColor.setOnClickListener {
            binding.colorPickerScroll.visibility = if (binding.colorPickerScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        setupColorPicker(course.name)

        binding.btnDismiss.setOnClickListener { dismiss() }
    }

    private fun buildClockTime(begin: Int, end: Int): String {
        val startTime = timeMap[begin]?.substringBefore("-") ?: ""
        val endTime = timeMap[end]?.substringAfter("-") ?: ""
        return if (startTime.isNotEmpty() && endTime.isNotEmpty()) "($startTime-$endTime)" else ""
    }

    private fun setupColorPicker(courseName: String) {
        binding.colorPickerContainer.removeAllViews()
        val size = (36 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (colorHex in colorPalette) {
            val chip = Chip(requireContext()).apply {
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    marginStart = margin
                    marginEnd = margin
                }
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.parseColor(colorHex))
                text = ""
                chipMinHeight = 0f
                setEnsureMinTouchTargetSize(false)
                setOnClickListener {
                    colorChangedListener?.onCourseColorChanged(courseName, colorHex)
                    binding.colorBar.setBackgroundColor(Color.parseColor(colorHex))
                }
            }
            binding.colorPickerContainer.addView(chip)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_COURSES = "courses_json"
        private const val ARG_COLOR = "course_color"

        fun newInstance(
            courses: List<Course>,
            color: Int
        ): CourseDetailBottomSheet {
            return CourseDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_COLOR, color)
                    putString(ARG_COURSES, com.google.gson.Gson().toJson(courses))
                }
            }
        }
    }
}
