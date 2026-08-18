package com.ifafu.kyzz.ui.score

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.databinding.DialogScoreDetailSheetBinding

class ScoreDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogScoreDetailSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogScoreDetailSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val score = Score(
            year = args.getString(ARG_YEAR).orEmpty(),
            term = args.getString(ARG_TERM).orEmpty(),
            courseCode = args.getString(ARG_CODE).orEmpty(),
            courseName = args.getString(ARG_NAME).orEmpty(),
            courseType = args.getString(ARG_TYPE).orEmpty(),
            courseOwner = args.getString(ARG_OWNER).orEmpty(),
            studyScore = args.getFloat(ARG_CREDITS),
            score = args.getFloat(ARG_SCORE),
            isDelayExam = args.getBoolean(ARG_DELAY),
            makeupScore = args.getFloat(ARG_MAKEUP),
            isRestudy = args.getBoolean(ARG_RESTUDY),
            institute = args.getString(ARG_INSTITUTE).orEmpty(),
            scorePoint = args.getFloat(ARG_GPA),
            comment = args.getString(ARG_COMMENT).orEmpty(),
            makeupComment = args.getString(ARG_MAKEUP_COMMENT).orEmpty()
        )

        binding.tvDetailCourseName.text = score.courseName.ifBlank { "未命名课程" }
        binding.tvDetailCourseCode.text = score.courseCode.ifBlank { "课程代码未提供" }
        binding.tvDetailScore.text = score.score.takeIf { it > 0f }?.let(::formatNumber) ?: "--"
        binding.tvDetailGpa.text = score.scorePoint.takeIf { it > 0f }?.let { String.format("%.2f", it) } ?: "--"

        val scoreColor = when {
            score.score <= 0f -> R.color.claude_text_hint
            score.score < 60f -> R.color.claude_error
            else -> R.color.claude_success
        }
        binding.tvDetailScore.setTextColor(requireContext().getColor(scoreColor))

        addInfo("学年学期", listOf(score.year, score.term.takeIf { it.isNotBlank() }?.let { "第${it}学期" }).filterNotNull().joinToString(" · "))
        addInfo("课程类型", score.courseType)
        addInfo("课程归属", score.courseOwner)
        addInfo("开课学院", score.institute)
        addInfo("学分", score.studyScore.takeIf { it > 0f }?.let(::formatNumber))

        val statuses = mutableListOf<String>()
        if (score.makeupScore > 0f) statuses += "补考 ${formatNumber(score.makeupScore)}"
        if (score.isDelayExam) statuses += "缓考"
        if (score.isRestudy) statuses += "重修"
        if (statuses.isNotEmpty()) {
            binding.statusSection.visibility = View.VISIBLE
            statuses.forEach { addStatus(it) }
        }

        val notes = buildList {
            if (score.comment.isNotBlank()) add("成绩备注：${score.comment}")
            if (score.makeupComment.isNotBlank()) add("补考备注：${score.makeupComment}")
        }
        if (notes.isNotEmpty()) {
            binding.notesSection.visibility = View.VISIBLE
            binding.tvDetailNotes.text = notes.joinToString("\n")
        }

        binding.btnDetailDismiss.setOnClickListener { dismiss() }
    }

    private fun addInfo(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.claude_text_hint))
        })
        row.addView(TextView(requireContext()).apply {
            text = value
            textSize = 13f
            gravity = android.view.Gravity.END
            setTextColor(requireContext().getColor(R.color.claude_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        binding.courseInfoContainer.addView(row)
    }

    private fun addStatus(text: String) {
        binding.statusContainer.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(requireContext().getColor(R.color.claude_terracotta_dark))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(100).toFloat()
                setColor(requireContext().getColor(R.color.claude_terracotta_100))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        })
    }

    private fun formatNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else String.format("%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_YEAR = "year"
        private const val ARG_TERM = "term"
        private const val ARG_CODE = "code"
        private const val ARG_NAME = "name"
        private const val ARG_TYPE = "type"
        private const val ARG_OWNER = "owner"
        private const val ARG_CREDITS = "credits"
        private const val ARG_SCORE = "score"
        private const val ARG_DELAY = "delay"
        private const val ARG_MAKEUP = "makeup"
        private const val ARG_RESTUDY = "restudy"
        private const val ARG_INSTITUTE = "institute"
        private const val ARG_GPA = "gpa"
        private const val ARG_COMMENT = "comment"
        private const val ARG_MAKEUP_COMMENT = "makeup_comment"

        fun newInstance(score: Score): ScoreDetailBottomSheet = ScoreDetailBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_YEAR, score.year)
                putString(ARG_TERM, score.term)
                putString(ARG_CODE, score.courseCode)
                putString(ARG_NAME, score.courseName)
                putString(ARG_TYPE, score.courseType)
                putString(ARG_OWNER, score.courseOwner)
                putFloat(ARG_CREDITS, score.studyScore)
                putFloat(ARG_SCORE, score.score)
                putBoolean(ARG_DELAY, score.isDelayExam)
                putFloat(ARG_MAKEUP, score.makeupScore)
                putBoolean(ARG_RESTUDY, score.isRestudy)
                putString(ARG_INSTITUTE, score.institute)
                putFloat(ARG_GPA, score.scorePoint)
                putString(ARG_COMMENT, score.comment)
                putString(ARG_MAKEUP_COMMENT, score.makeupComment)
            }
        }
    }
}
