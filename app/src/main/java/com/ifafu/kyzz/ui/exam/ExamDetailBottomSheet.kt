package com.ifafu.kyzz.ui.exam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.util.ExamDates
import com.ifafu.kyzz.databinding.DialogExamDetailSheetBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 考试详情底部弹层：点击考试卡片后展示单场考试的完整信息。
 * 在卡片摘要之外补充解析后的日期/星期/时段、开考倒计时和同场冲突提示。
 */
class ExamDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogExamDetailSheetBinding? = null
    private val binding get() = _binding!!

    private var exam = Exam()
    private var conflictWith = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            val json = args.getString(ARG_EXAM, null)
            if (json != null) {
                try {
                    exam = com.google.gson.Gson().fromJson(json, Exam::class.java) ?: Exam()
                } catch (_: Exception) {
                    exam = Exam()
                }
            }
            conflictWith = args.getString(ARG_CONFLICT, "") ?: ""
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogExamDetailSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvExamName.text = exam.name.ifEmpty { "未命名考试" }

        setupCountdown()
        setupRows()
        setupConflict()

        binding.btnDismiss.setOnClickListener { dismiss() }
    }

    /** 倒计时状态：未来=赤陶橙、当天/进行中=警示橙、已结束=灰。 */
    private fun setupCountdown() {
        val dates = ExamDates.allDates(exam.datetime)
        if (dates.isEmpty()) {
            binding.countdownSection.visibility = View.GONE
            return
        }
        val state = ExamDates.countdownState(exam.datetime)
        // 星期几按对应日期的真实星期显示：未开始取首场，当天取今天
        val weekdaySource = if (state is ExamDates.CountdownState.Today) {
            java.util.Date()
        } else {
            java.util.Date(dates.minOf { it.timeInMillis })
        }
        val weekday = SimpleDateFormat("EEEE", Locale.CHINA).format(weekdaySource)

        binding.tvCountdown.text = when (state) {
            is ExamDates.CountdownState.Finished -> "已结束 ${state.daysAgo} 天"
            is ExamDates.CountdownState.Today -> "就是今天 · $weekday"
            is ExamDates.CountdownState.Upcoming -> "距开考还有 ${state.daysLeft} 天 · $weekday"
            is ExamDates.CountdownState.InProgress -> "已开考 · 距结束还有 ${state.daysToEnd} 天"
            ExamDates.CountdownState.Unknown -> ""
        }
        // setBackgroundColor 需要 ARGB 色值而非资源 ID——未来态的浅色底必须
        // 先 resolve，直接传 R.color 会被当成裸色值渲染成任意颜色
        val bg = when (state) {
            is ExamDates.CountdownState.Finished -> 0x1A9C958E
            is ExamDates.CountdownState.Today, is ExamDates.CountdownState.InProgress -> 0x1AB7791F
            else -> resources.getColor(R.color.claude_terracotta_100, null)
        }
        val fg = when (state) {
            is ExamDates.CountdownState.Finished -> R.color.claude_text_tertiary
            is ExamDates.CountdownState.Today, is ExamDates.CountdownState.InProgress -> R.color.claude_warning
            else -> R.color.claude_terracotta
        }
        binding.tvCountdown.setBackgroundColor(bg)
        binding.tvCountdown.setTextColor(resources.getColor(fg, null))
    }

    /**
     * 结构化展示行。教务 datetime 形态多样（"2026-05-08(周五)18:30-20:30"、
     * "2026年5月8日…"、跨天"6-29~6-30"），解析失败时回退显示原始时间串，
     * 保证任何形态都有可读内容。
     */
    private fun setupRows() {
        val dates = ExamDates.allDates(exam.datetime)
        if (dates.isEmpty()) {
            binding.tvDate.text = exam.datetime.ifEmpty { "-" }
            binding.rowPeriod.visibility = View.GONE
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateText = dates.sortedBy { it.timeInMillis }
                .joinToString(" ~ ") { fmt.format(it.time) }
            binding.tvDate.text = dateText

            val periods = Regex("(\\d{1,2}:\\d{2})\\s*[-~]\\s*(\\d{1,2}:\\d{2})")
                .findAll(exam.datetime)
                .joinToString("、") { "${it.groupValues[1]}-${it.groupValues[2]}" }
            if (periods.isNotEmpty()) {
                binding.tvPeriod.text = periods
            } else {
                binding.rowPeriod.visibility = View.GONE
            }
        }

        bindOrHide(binding.rowAddress, binding.tvAddress, exam.address)
        bindOrHide(binding.rowSeat, binding.tvSeat, exam.seatNumber)
        bindOrHide(binding.rowCampus, binding.tvCampus, exam.campus)
        bindOrHide(binding.rowCourseCode, binding.tvCourseCode, exam.id)
    }

    private fun bindOrHide(row: View, value: TextView, text: String) {
        if (text.isEmpty()) {
            row.visibility = View.GONE
        } else {
            row.visibility = View.VISIBLE
            value.text = text
        }
    }

    private fun setupConflict() {
        if (conflictWith.isEmpty()) {
            binding.conflictSection.visibility = View.GONE
        } else {
            binding.conflictSection.visibility = View.VISIBLE
            binding.tvConflict.text = "⚠ 该时段同时安排了：$conflictWith，请注意时间冲突。"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_EXAM = "exam_json"
        private const val ARG_CONFLICT = "conflict_with"

        /** @param conflictWith 同一时段其它考试名（顿号分隔），无冲突传空串 */
        fun newInstance(exam: Exam, conflictWith: String = ""): ExamDetailBottomSheet {
            return ExamDetailBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_EXAM, com.google.gson.Gson().toJson(exam))
                    putString(ARG_CONFLICT, conflictWith)
                }
            }
        }
    }
}
