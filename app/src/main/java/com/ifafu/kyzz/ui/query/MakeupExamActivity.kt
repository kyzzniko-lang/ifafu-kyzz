package com.ifafu.kyzz.ui.query

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.ifafu.kyzz.data.model.MakeupExam
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.databinding.ActivityMakeupExamBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.base.ReloginViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MakeupExamActivity : BaseActivity<ActivityMakeupExamBinding>() {

    private val viewModel: MakeupExamViewModel by viewModels()

    override fun createBinding() = ActivityMakeupExamBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.title = "补考考试查询"
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

    inner class Adapter(private val items: List<MakeupExam>) :
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

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.content.removeAllViews()

            // Course name
            holder.content.addView(TextView(this@MakeupExamActivity).apply {
                text = item.courseName
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
            })

            // Exam time
            if (item.examTime.isNotBlank()) {
                holder.content.addView(TextView(this@MakeupExamActivity).apply {
                    text = "时间: ${item.examTime}"
                    setTextAppearance(R.style.ClaudeCaption)
                    typeface = resources.getFont(R.font.claude_serif)
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                })
            }

            // Location + seat
            val locText = buildString {
                if (item.examLocation.isNotBlank()) append("地点: ${item.examLocation}")
                if (item.seatNumber.isNotBlank()) {
                    if (isNotEmpty()) append("  |  ")
                    append("座位号: ${item.seatNumber}")
                }
            }
            if (locText.isNotBlank()) {
                holder.content.addView(TextView(this@MakeupExamActivity).apply {
                    text = locText
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
            context.startActivity(Intent(context, MakeupExamActivity::class.java))
        }
    }
}

@HiltViewModel
class MakeupExamViewModel @Inject constructor(
    private val studentQueryApi: StudentQueryApi,
    private val userRepository: UserRepository
) : ReloginViewModel() {

    val state = MutableLiveData<MakeupExamActivity.State>(MakeupExamActivity.State.Loading)
    val data = MutableLiveData<List<MakeupExam>>()

    fun load() {
        val user = userRepository.getUser()
        if (!user.isLogin) {
            state.value = MakeupExamActivity.State.Error("未登录")
            return
        }
        state.value = MakeupExamActivity.State.Loading
        viewModelScope.launch {
            val result = studentQueryApi.getMakeupExams(
                userRepository.host, user.token, user.account, user.name
            )
            if (result.success && result.data != null) {
                data.value = result.data!!
                state.value = MakeupExamActivity.State.Success
            } else {
                state.value = MakeupExamActivity.State.Error(result.message)
            }
        }
    }
}
