package com.ifafu.kyzz.ui.toolbox

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.ActivityToolboxBinding
import com.ifafu.kyzz.ui.about.AboutActivity
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.ui.comment.CommentTeacherActivity
import com.ifafu.kyzz.ui.comment.DiscussionActivity
import com.ifafu.kyzz.ui.score.ElectiveScoreActivity
import com.ifafu.kyzz.ui.studentinfo.StudentInfoActivity
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class ToolboxActivity : BaseActivity<ActivityToolboxBinding>() {

    private val viewModel: ToolboxViewModel by viewModels()

    override fun createBinding(): ActivityToolboxBinding = ActivityToolboxBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val items = listOf(
            ToolboxItem("校园讨论", "匿名讨论，畅所欲言", "", "") { startActivity(Intent(this, DiscussionActivity::class.java)) },
            ToolboxItem("教师评价", "快速完成教师评价", "xsjxpj.aspx", "N121401") { startActivity(Intent(this, CommentTeacherActivity::class.java)) },
            ToolboxItem("选修学分查询", "查看选修学分完成情况", "", "") { startActivity(Intent(this, ElectiveScoreActivity::class.java)) },
            ToolboxItem("选课情况查询", "查看全部选课记录", "xsxkqk.aspx", "N121615") { startActivity(Intent(this, com.ifafu.kyzz.ui.query.CourseSelectionActivity::class.java)) },
            ToolboxItem("补考考试查询", "查询补考考试安排", "xsbkkscx.aspx", "N121617") { startActivity(Intent(this, com.ifafu.kyzz.ui.query.MakeupExamActivity::class.java)) },
            ToolboxItem("培养计划", "查看个人培养方案", "pyjh.aspx", "N121607") { startActivity(Intent(this, TrainingPlanActivity::class.java)) },
            ToolboxItem("个人信息", "查看学籍基本信息", "xsgrxx.aspx", "N121501") { startActivity(Intent(this, StudentInfoActivity::class.java)) },
            ToolboxItem("密码修改", "修改教务系统密码", "mmxg.aspx", "N121502") { startActivity(Intent(this, PasswordModifyActivity::class.java)) },
            ToolboxItem("等级考试查询", "查询等级考试成绩", "xsdjkscx.aspx", "N121606") { startActivity(Intent(this, GradeExamActivity::class.java)) },
            ToolboxItem("网上报名", "查看报名项目", "bmxmb2.aspx", "N121303") { startActivity(Intent(this, OnlineRegistrationActivity::class.java)) },
            ToolboxItem("设置学期首日", "手动设置开学第一周的日期", "", "") { showTermFirstDayPicker() },
            ToolboxItem("切换账号", "管理多个教务账号", "", "") { showAccountSwitcher() },
            ToolboxItem("深色模式", "切换深色/浅色主题", "", "") { toggleDarkMode() },
            ToolboxItem("关于", "关于 iFAFU", "", "") { startActivity(Intent(this, AboutActivity::class.java)) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ToolboxAdapter(items)
    }

    data class ToolboxItem(
        val title: String,
        val subtitle: String,
        val page: String,
        val gnmkdm: String,
        val onClick: (ToolboxItem) -> Unit
    )

    inner class ToolboxAdapter(private val items: List<ToolboxItem>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<ToolboxAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 14f
                cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                isClickable = true
                isFocusable = true
            }

            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 16, 20, 16)
            }

            val icon = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(36, 36)
                setBackgroundResource(R.drawable.bg_icon_circle)
            }

            val textContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(parent.context).apply {
                typeface = resources.getFont(R.font.claude_serif)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                textSize = 15f
            }

            val subtitle = TextView(parent.context).apply {
                setTextAppearance(R.style.ClaudeCaption)
            }

            textContainer.addView(title)
            textContainer.addView(subtitle)

            val arrow = ImageView(parent.context).apply {
                setImageResource(android.R.drawable.ic_media_play)
                layoutParams = LinearLayout.LayoutParams(16, 16)
                imageTintList = androidx.core.content.ContextCompat.getColorStateList(context, R.color.claude_text_tertiary)
                alpha = 0.3f
            }

            val spacer = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(20, 1)
            }

            content.addView(icon)
            content.addView(spacer)
            content.addView(textContainer)
            content.addView(arrow)
            card.addView(content)
            return ViewHolder(card, title, subtitle)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle
            holder.itemView.setOnClickListener { item.onClick(item) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(
            itemView: View,
            val title: TextView,
            val subtitle: TextView
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }

    private fun showTermFirstDayPicker() {
        val cal = Calendar.getInstance()
        val existing = viewModel.termFirstDay
        if (existing.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sdf.parse(existing)?.let { cal.time = it }
            } catch (_: Exception) {}
        }

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                viewModel.saveTermFirstDay(sdf.format(selected.time))
                Toast.makeText(this, "学期首日已设置为 $year-${month + 1}-$dayOfMonth", Toast.LENGTH_SHORT).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showAccountSwitcher() {
        val profiles = viewModel.getAccountProfiles()
        val currentAccount = viewModel.getCurrentAccount()
        val items = profiles.map { p ->
            val tag = if (p.account == currentAccount) " (当前)" else ""
            "${p.name}${tag}\n${p.account}"
        }.toMutableList()
        items.add("+ 添加新账号")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("切换账号")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < profiles.size) {
                    val profile = profiles[which]
                    if (profile.account != currentAccount) {
                        viewModel.switchAccount(profile)
                        Toast.makeText(this, "已切换到 ${profile.name}", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this, com.ifafu.kyzz.ui.login.LoginActivity::class.java))
                        finish()
                    }
                } else {
                    startActivity(Intent(this, com.ifafu.kyzz.ui.login.LoginActivity::class.java))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleDarkMode() {
        val currentMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        val newMode = if (currentMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        } else {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        getSharedPreferences("ifafu_user", MODE_PRIVATE).edit().putInt("dark_mode", newMode).apply()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newMode)
    }
}
