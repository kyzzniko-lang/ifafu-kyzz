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

class CourseDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogCourseDetailSheetBinding? = null
    private val binding get() = _binding!!

    private var courses: List<Course> = emptyList()
    private var courseColor: Int = 0
    private var onColorChanged: ((String, String) -> Unit)? = null

    private val colorPalette = listOf(
        "#D4724A", "#2D7A4F", "#B7791F", "#C53030", "#4A6FA5",
        "#6B4C9A", "#2B8A8A", "#8B5A2B", "#5B6BBF", "#CC5577",
        "#7B68EE", "#20B2AA", "#CD853F", "#708090", "#DA70D6",
        "#E74C3C", "#3498DB", "#2ECC71", "#F39C12", "#9B59B6",
        "#1ABC9C", "#E67E22", "#34495E", "#16A085"
    )

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
                "第${c.weekBegin}-${c.weekEnd}周 $dayText 第${c.begin}-${c.end}节$oddText"
            }
        } else {
            val dayText = if (course.weekDay in 1..7) weekDays[course.weekDay] else ""
            val oddText = when (course.oddOrTwice) { 1 -> " 单周"; 2 -> " 双周"; else -> "" }
            "第${course.weekBegin}-${course.weekEnd}周 $dayText 第${course.begin}-${course.end}节$oddText"
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
                    onColorChanged?.invoke(courseName, colorHex)
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
        fun newInstance(
            courses: List<Course>,
            color: Int,
            onColorChanged: (courseName: String, colorHex: String) -> Unit
        ): CourseDetailBottomSheet {
            return CourseDetailBottomSheet().apply {
                this.courses = courses
                this.courseColor = color
                this.onColorChanged = onColorChanged
            }
        }
    }
}
