package com.ifafu.kyzz.ui.studentinfo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.observe
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.databinding.ActivityStudentInfoBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.UiState
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class StudentInfoActivity : BaseActivity<ActivityStudentInfoBinding>() {

    private val viewModel: StudentInfoViewModel by viewModels()

    override fun createBinding(): ActivityStudentInfoBinding = ActivityStudentInfoBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.loadStudentInfo(forceRefresh = true) }

        viewModel.state.observe(this) { state ->
            when (state) {
                is UiState.Idle -> {}
                is UiState.Loading -> showLoading()
                is UiState.Success -> {
                    binding.offlineBanner.root.visibility = View.GONE
                    showInfo(state.data)
                }
                is UiState.Cached -> {
                    binding.offlineBanner.root.visibility = View.VISIBLE
                    binding.offlineBanner.tvOfflineMessage.text = state.staleMessage
                    showInfo(state.data)
                }
                is UiState.Error -> showError(state.message)
            }
        }

        viewModel.loadStudentInfo()
    }

    private fun showLoading() {
        binding.offlineBanner.root.visibility = View.GONE
        binding.petLoading.root.startLoading()
        binding.scrollContent.visibility = View.GONE
        binding.errorLayout.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.offlineBanner.root.visibility = View.GONE
        binding.petLoading.root.stopLoading()
        binding.scrollContent.visibility = View.GONE
        binding.errorLayout.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun showInfo(info: StudentInfo) {
        binding.petLoading.root.stopLoading()
        binding.scrollContent.visibility = View.VISIBLE
        binding.errorLayout.visibility = View.GONE
        binding.contentLayout.removeAllViews()

        addSection("基本信息", listOf(
            "姓名" to info.name,
            "学号" to info.account,
            "性别" to info.gender,
            "出生日期" to info.birthday,
            "民族" to info.nation,
            "政治面貌" to info.politicalStatus,
            "籍贯" to info.nativePlace,
            "来源地区" to info.originRegion,
            "来源省" to info.originProvince,
            "身份证号" to info.idNumber
        ))

        addSection("学籍信息", listOf(
            "学院" to info.college,
            "专业名称" to info.major,
            "行政班" to info.adminClass,
            "学制" to info.duration,
            "学历层次" to info.educationLevel,
            "学籍状态" to info.status,
            "当前所在级" to info.currentGrade,
            "入学日期" to info.enrollmentDate,
            "毕业中学" to info.graduationSchool,
            "考生号" to info.candidateNumber
        ))

        addSection("联系方式", listOf(
            "联系电话" to info.phone,
            "宿舍号" to info.dormitory,
            "邮政编码" to info.postalCode,
            "家庭地址" to info.homeAddress,
            "家庭所在地" to info.homeLocation
        ))
    }

    private fun addSection(title: String, items: List<Pair<String, String>>) {
        val sectionTitle = TextView(this).apply {
            text = title
            setTextAppearance(R.style.ClaudeSubtitle)
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            typeface = resources.getFont(R.font.claude_serif)
            setPadding(0, 20, 0, 8)
        }
        binding.contentLayout.addView(sectionTitle)

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            radius = 14f
            cardElevation = 0f
            strokeColor = resources.getColor(R.color.claude_border, null)
            strokeWidth = 1
            setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
        }

        for ((label, value) in items) {
            if (value.isEmpty()) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val labelView = TextView(this).apply {
                text = label
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }

            val valueView = TextView(this).apply {
                text = maskSensitiveValue(label, value)
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                gravity = Gravity.END
                if (label == "身份证号" || label == "联系电话") {
                    setOnLongClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                        android.widget.Toast.makeText(
                            this@StudentInfoActivity,
                            "已复制$label",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }
            }

            row.addView(labelView)
            row.addView(valueView)
            content.addView(row)
        }

        card.addView(content)
        binding.contentLayout.addView(card)
    }

    private fun maskSensitiveValue(label: String, value: String): String {
        return when {
            label == "联系电话" && value.length >= 7 ->
                value.take(3) + "****" + value.takeLast(4)
            label == "身份证号" && value.length >= 10 ->
                value.take(4) + "**********" + value.takeLast(4)
            else -> value
        }
    }
}
