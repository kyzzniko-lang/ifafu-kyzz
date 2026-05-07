package com.ifafu.kyzz.ui.toolbox

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.ifafu.kyzz.databinding.ActivityKyzzToolboxBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
class KyzzToolboxActivity : BaseActivity<ActivityKyzzToolboxBinding>() {

    private val repoOwner = "26651lll"
    private val repoName = "ifafu-kyzz"
    private val githubToken = ""

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

        binding.cardGrabPE.setOnClickListener {
            Toast.makeText(this, "正在实现中，敬请期待", Toast.LENGTH_SHORT).show()
        }
    }

    private val okHttpClient by lazy { OkHttpClient() }

    private fun submitToGitHub(title: String, body: String): Boolean {
        if (githubToken.isEmpty()) return false
        return try {
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("labels", JSONArray().put("feedback"))
            }
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repoOwner/$repoName/issues")
                .header("Authorization", "token $githubToken")
                .header("Accept", "application/vnd.github.v3+json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
}
