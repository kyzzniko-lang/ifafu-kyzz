package com.ifafu.kyzz.ui.feedback

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.ifafu.kyzz.databinding.ActivityCrashFeedbackBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class CrashFeedbackActivity : BaseActivity<ActivityCrashFeedbackBinding>() {

    private val viewModel: CrashFeedbackViewModel by viewModels()

    override fun createBinding(): ActivityCrashFeedbackBinding =
        ActivityCrashFeedbackBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        viewModel.loadCrashInfo()

        viewModel.crashInfo.observe(this) { info ->
            if (info == null) {
                binding.tvSummary.text = "（无崩溃记录）"
                binding.tvTrace.text = ""
                binding.btnSubmit.isEnabled = false
                return@observe
            }
            binding.tvSummary.text = formatSummary(info)
            binding.tvTrace.text = info.trace
        }

        binding.btnCopy.setOnClickListener {
            val info = viewModel.crashInfo.value
            val text = if (info != null) {
                "${formatSummary(info)}\n\n${info.trace}"
            } else {
                "（无崩溃记录）"
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("iFAFU crash log", text))
            Toast.makeText(this, "崩溃日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        binding.btnSubmit.setOnClickListener {
            val description = binding.etDescription.text?.toString().orEmpty()
            viewModel.submit(description)
        }

        viewModel.submitState.observe(this) { state ->
            when (state) {
                is CrashFeedbackViewModel.SubmitState.Idle -> {
                    binding.tvStatus.visibility = View.GONE
                    binding.btnSubmit.isEnabled = true
                }
                is CrashFeedbackViewModel.SubmitState.Submitting -> {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "正在提交..."
                    binding.btnSubmit.isEnabled = false
                }
                is CrashFeedbackViewModel.SubmitState.Success -> {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "✓ 反馈已提交，感谢你的帮助！"
                    binding.btnSubmit.isEnabled = false
                    Toast.makeText(this, "反馈成功，感谢支持", Toast.LENGTH_SHORT).show()
                }
                is CrashFeedbackViewModel.SubmitState.Error -> {
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = state.message
                    binding.btnSubmit.isEnabled = true
                }
            }
        }
    }

    private fun formatSummary(info: com.ifafu.kyzz.core.crash.CrashInfo): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(info.time))
        return buildString {
            append("类型: ").append(if (info.type == "anr") "无响应(ANR)" else "崩溃")
            append("\n时间: ").append(time)
            append("\n版本: ").append(info.appVersion)
            append("\n设备: ").append(info.deviceInfo)
            append("\n线程: ").append(info.threadName)
            append("\n错误: ").append(info.message)
        }
    }
}
