package com.ifafu.kyzz.ui.calculator

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.SeekBar
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityScoreCalculatorBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class ScoreCalculatorActivity : BaseActivity<ActivityScoreCalculatorBinding>() {

    override fun createBinding() = ActivityScoreCalculatorBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSeekBars()
        setupInputWatcher()
        recalculate()
    }

    private fun setupSeekBars() {
        binding.seekRegularWeight.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvRegularWeight.text = "$progress%"
                    binding.seekExamWeight.progress = 100 - progress
                    binding.tvExamWeight.text = "${100 - progress}%"
                    recalculate()
                }
            }
        })

        binding.seekExamWeight.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvExamWeight.text = "$progress%"
                    binding.seekRegularWeight.progress = 100 - progress
                    binding.tvRegularWeight.text = "${100 - progress}%"
                    recalculate()
                }
            }
        })

        binding.seekRegularScore.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.etRegularScore.setText(progress.toString())
                    recalculate()
                }
            }
        })
    }

    private fun setupInputWatcher() {
        binding.etRegularScore.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val score = s?.toString()?.toFloatOrNull()?.coerceIn(0f, 100f) ?: return
                binding.seekRegularScore.progress = score.toInt()
                recalculate()
            }
        })
    }

    private fun recalculate() {
        val regularWeight = binding.seekRegularWeight.progress.toFloat() / 100f
        val examWeight = binding.seekExamWeight.progress.toFloat() / 100f
        val regularScore = binding.etRegularScore.text?.toString()?.toFloatOrNull()

        if (regularScore == null || regularScore < 0 || regularScore > 100) {
            binding.tvRequiredScore.text = "--"
            binding.tvVerdict.text = "请输入平时成绩"
            binding.tvVerdict.setTextColor(getColor(R.color.claude_text_tertiary))
            binding.tvExtraInfo.text = ""
            return
        }
        if (examWeight <= 0f) {
            // 期末权重为 0，平时分直接决定
            val total = regularScore * regularWeight
            binding.tvRequiredScore.text = "--"
            binding.tvVerdict.text = if (total >= 60) "平时分已够及格！" else "平时分不足以及格"
            binding.tvVerdict.setTextColor(getColor(R.color.claude_error))
            binding.tvExtraInfo.text = "总评成绩 = 平时 ${"%.1f".format(total)} 分"
            return
        }

        // 期末需要分数 = (60 - 平时分 × 平时权重) / 期末权重
        val required = (60f - regularScore * regularWeight) / examWeight

        binding.tvRequiredScore.text = String.format(Locale.getDefault(), "%.1f", required)

        // 判定
        when {
            required <= 0f -> {
                binding.tvVerdict.text = "稳了，不用考都能过！"
                binding.tvVerdict.setTextColor(getColor(R.color.claude_success))
            }
            required <= 60f -> {
                binding.tvVerdict.text = "稳了"
                binding.tvVerdict.setTextColor(getColor(R.color.claude_success))
            }
            required <= 80f -> {
                binding.tvVerdict.text = "稳住，复习一下就行"
                binding.tvVerdict.setTextColor(getColor(R.color.claude_warning))
            }
            required <= 100f -> {
                binding.tvVerdict.text = "有点危险，加油！"
                binding.tvVerdict.setTextColor(getColor(R.color.claude_error))
            }
            else -> {
                binding.tvVerdict.text = "非常危险！需要超常发挥"
                binding.tvVerdict.setTextColor(getColor(R.color.claude_error))
            }
        }

        // 即使期末 0 分，总评也有 XX 分
        val worstCase = regularScore * regularWeight
        binding.tvExtraInfo.text = "即使期末考 0 分，总评也有 ${"%.1f".format(worstCase)} 分"

        // 进度条权重
        val safePortion = (regularScore * regularWeight).coerceIn(0f, 100f)
        binding.barSafe.layoutParams = (binding.barSafe.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
            weight = safePortion.coerceAtLeast(1f)
        }
        binding.barWarning.layoutParams = (binding.barWarning.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
            weight = ((60f - safePortion).coerceIn(0f, 100f)).coerceAtLeast(1f)
        }
        binding.barDanger.layoutParams = (binding.barDanger.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
            weight = ((100f - safePortion - (60f - safePortion).coerceIn(0f, 100f)).coerceIn(0f, 100f)).coerceAtLeast(1f)
        }
    }

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
