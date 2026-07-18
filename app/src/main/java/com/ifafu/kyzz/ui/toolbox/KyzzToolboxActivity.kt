package com.ifafu.kyzz.ui.toolbox

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.ifafu.kyzz.BuildConfig
import com.ifafu.kyzz.data.api.FeedbackWorkerApi
import com.ifafu.kyzz.databinding.ActivityKyzzToolboxBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class KyzzToolboxActivity : BaseActivity<ActivityKyzzToolboxBinding>() {

    @Inject lateinit var feedbackWorkerApi: FeedbackWorkerApi

    override fun createBinding(): ActivityKyzzToolboxBinding =
        ActivityKyzzToolboxBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSubmit.setOnClickListener {
            val title = binding.etTitle.text?.toString()?.trim().orEmpty()
            val content = binding.etContent.text?.toString()?.trim().orEmpty()

            when {
                title.length < 4 -> {
                    Toast.makeText(this, "标题至少需要4个字", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                content.length < 10 -> {
                    Toast.makeText(this, "反馈内容至少需要10个字", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            binding.btnSubmit.isEnabled = false
            binding.btnSubmit.text = "提交中…"

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    submitToGitHub(title, content)
                }
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "提交反馈"
                if (result.first) {
                    Toast.makeText(this@KyzzToolboxActivity, "反馈提交成功，感谢你的建议！", Toast.LENGTH_SHORT).show()
                    binding.etTitle.text?.clear()
                    binding.etContent.text?.clear()
                } else {
                    Toast.makeText(this@KyzzToolboxActivity, result.second, Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.cardGrabElective.setOnClickListener {
            Toast.makeText(this, "正在实现中，敬请期待", Toast.LENGTH_SHORT).show()
        }

        binding.cardVirtualLocation.setOnClickListener {
            startActivity(Intent(this, MockLocationActivity::class.java))
        }
    }

    private fun submitToGitHub(title: String, body: String): Pair<Boolean, String> {
        return try {
            val response = feedbackWorkerApi.post(
                "/issue",
                mapOf(
                    "title" to title,
                    "description" to body,
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "deviceInfo" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}",
                    "contact" to "未提供"
                )
            )
            if (response?.get("ok")?.asBoolean == true) {
                true to ""
            } else {
                false to (response?.get("message")?.asString ?: "提交失败，请稍后重试")
            }
        } catch (_: Exception) {
            false to "网络异常，请检查网络连接后重试"
        }
    }
}
