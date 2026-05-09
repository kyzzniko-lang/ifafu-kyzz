package com.ifafu.kyzz.ui.query

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.api.StudentQueryApi
import com.ifafu.kyzz.data.model.CourseSelection
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.databinding.ActivityCourseSelectionBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CourseSelectionActivity : BaseActivity<ActivityCourseSelectionBinding>() {

    private val viewModel: CourseSelectionViewModel by viewModels()

    override fun createBinding() = ActivityCourseSelectionBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.title = "选课情况查询"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is State.Loading -> {
                    binding.petLoading.root.startLoading()
                    binding.recyclerView.visibility = View.GONE
                    binding.errorLayout.visibility = View.GONE
                }
                is State.Success -> {
                    binding.petLoading.root.stopLoading()
                    binding.recyclerView.visibility = View.VISIBLE
                    binding.errorLayout.visibility = View.GONE
                }
                is State.Error -> {
                    binding.petLoading.root.stopLoading()
                    binding.recyclerView.visibility = View.GONE
                    binding.errorLayout.visibility = View.VISIBLE
                    binding.tvError.text = state.message
                }
            }
        }

        viewModel.data.observe(this) { list ->
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = Adapter(list)
        }

        viewModel.load()
    }

    sealed class State {
        object Loading : State()
        object Success : State()
        data class Error(val message: String) : State()
    }

    inner class Adapter(private val items: List<CourseSelection>) :
        RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 14f; cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
            }
            card.addView(content)
            return VH(card, content)
        }

        private fun makeTag(text: String, bgColor: Int, textColor: Int): TextView {
            return TextView(this@CourseSelectionActivity).apply {
                this.text = text
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
                setTextColor(textColor)
                setPadding(12, 4, 12, 4)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 6 }
            }
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.content.removeAllViews()

            // Top row: name + tags
            val topRow = LinearLayout(this@CourseSelectionActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(this@CourseSelectionActivity).apply {
                text = item.courseName
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // 重修 tag
            if (item.studyMark.contains("重修")) {
                topRow.addView(makeTag("重修",
                    resources.getColor(R.color.claude_error, null),
                    resources.getColor(R.color.white, null)))
            }

            holder.content.addView(topRow)

            // 是否选修 status row
            val isSelected = item.selected == "是"
            val statusRow = LinearLayout(this@CourseSelectionActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 0)
            }
            statusRow.addView(TextView(this@CourseSelectionActivity).apply {
                text = "是否选修"
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
                setTextColor(resources.getColor(R.color.claude_text_tertiary, null))
            })
            statusRow.addView(makeTag(
                if (isSelected) "是" else "否",
                if (isSelected) resources.getColor(R.color.claude_success, null)
                else resources.getColor(R.color.claude_border, null),
                if (isSelected) resources.getColor(R.color.white, null)
                else resources.getColor(R.color.claude_text_secondary, null)
            ))
            holder.content.addView(statusRow)

            // Code + nature
            val codeText = buildString {
                if (item.courseCode.isNotBlank()) append(item.courseCode)
                if (item.courseNature.isNotBlank()) {
                    if (isNotEmpty()) append("  |  ")
                    append(item.courseNature)
                }
            }
            if (codeText.isNotBlank()) {
                holder.content.addView(TextView(this@CourseSelectionActivity).apply {
                    text = codeText
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }

            // Teacher + credits
            val infoText = buildString {
                if (item.teacher.isNotBlank()) append("教师: ${item.teacher}")
                if (item.credits.isNotBlank()) {
                    if (isNotEmpty()) append("  |  ")
                    append("学分: ${item.credits}")
                }
                if (item.weekHours.isNotBlank()) {
                    if (isNotEmpty()) append("  |  ")
                    append("周学时: ${item.weekHours}")
                }
            }
            if (infoText.isNotBlank()) {
                holder.content.addView(TextView(this@CourseSelectionActivity).apply {
                    text = infoText
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }

            // Time + location
            if (item.time.isNotBlank()) {
                holder.content.addView(TextView(this@CourseSelectionActivity).apply {
                    text = "时间: ${item.time}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }
            if (item.location.isNotBlank()) {
                holder.content.addView(TextView(this@CourseSelectionActivity).apply {
                    text = "地点: ${item.location}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }
        }

        override fun getItemCount() = items.size

        inner class VH(val card: MaterialCardView, val content: LinearLayout) :
            RecyclerView.ViewHolder(card)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CourseSelectionActivity::class.java))
        }
    }
}

@HiltViewModel
class CourseSelectionViewModel @Inject constructor(
    private val studentQueryApi: StudentQueryApi,
    private val userRepository: UserRepository
) : ReloginViewModel() {

    val state = MutableLiveData<CourseSelectionActivity.State>(CourseSelectionActivity.State.Loading)
    val data = MutableLiveData<List<CourseSelection>>()

    fun load() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            state.value = CourseSelectionActivity.State.Error("未登录")
            return
        }
        state.value = CourseSelectionActivity.State.Loading
        viewModelScope.launch {
            val result = studentQueryApi.getCourseSelections(
                userRepository.host, user.token, user.account, user.name
            )
            if (result.success && result.data != null) {
                data.value = result.data
                state.value = CourseSelectionActivity.State.Success
            } else {
                state.value = CourseSelectionActivity.State.Error(result.message)
            }
        }
    }
}
