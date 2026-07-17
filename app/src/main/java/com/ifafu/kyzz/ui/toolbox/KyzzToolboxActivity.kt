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

    override fun createBinding(): ActivityKyzzToolboxBinding = ActivityKyzzToolboxBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnSubmit.setOnClickListener {
            val title = binding.etTitle.text?.toString()?.trim() ?: ""
            val content = binding.etContent.text?.toString()?.trim() ?: ""

            if (title.isEmpty() || content.isEmpty()) {
                Toast.makeText(this, "请填写标题和内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnSubmit.isEnabled = false
            binding.btnSubmit.text = "提交中..."

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    submitToGitHub(title, content)
                }
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "提交反馈"
                if (success) {
                    Toast.makeText(this@KyzzToolboxActivity, "反馈提交成功，感谢你的建议！", Toast.LENGTH_SHORT).show()
                    binding.etTitle.text?.clear()
                    binding.etContent.text?.clear()
                } else {
                    Toast.makeText(this@KyzzToolboxActivity, "提交失败，请检查网络", Toast.LENGTH_SHORT).show()
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

    private fun submitToGitHub(title: String, body: String): Boolean =
        feedbackWorkerApi.post(
            "/issue",
            mapOf(
                "title" to title,
                "description" to body,
                "appVersion" to BuildConfig.VERSION_NAME,
                "deviceInfo" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}",
                "contact" to "未提供"
            )
        )?.get("ok")?.asBoolean == true
}
