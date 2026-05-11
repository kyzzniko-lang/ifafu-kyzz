package com.ifafu.kyzz.ui.toolbox

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.databinding.FragmentToolboxBinding
import com.ifafu.kyzz.ui.about.AboutActivity
import com.ifafu.kyzz.ui.calendar.CalendarExportActivity
import com.ifafu.kyzz.ui.calculator.ScoreCalculatorActivity
import com.ifafu.kyzz.ui.comment.CommentTeacherActivity
import com.ifafu.kyzz.ui.review.CourseReviewActivity
import com.ifafu.kyzz.ui.comment.DiscussionActivity
import com.ifafu.kyzz.ui.query.CourseSelectionActivity
import com.ifafu.kyzz.ui.query.MakeupExamActivity
import com.ifafu.kyzz.ui.countdown.CountdownActivity
import com.ifafu.kyzz.ui.map.CampusMapActivity
import com.ifafu.kyzz.ui.note.NoteListActivity
import com.ifafu.kyzz.ui.score.ElectiveScoreActivity
import com.ifafu.kyzz.ui.scorecard.ScoreCardActivity
import com.ifafu.kyzz.ui.settings.SettingsActivity
import com.ifafu.kyzz.ui.timer.PomodoroTimerActivity
import com.ifafu.kyzz.ui.studentinfo.StudentInfoActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ToolboxFragment : Fragment() {

    private var _binding: FragmentToolboxBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ToolboxViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val items = listOf(
            ToolboxItem("一维课表", "列表形式查看课表", "", "") { startActivity(Intent(requireContext(), com.ifafu.kyzz.ui.syllabus.SyllabusActivity::class.java)) },
            ToolboxItem("校园讨论", "匿名讨论，畅所欲言", "", "") { startActivity(Intent(requireContext(), DiscussionActivity::class.java)) },
            ToolboxItem("课程评价", "查看课程评价，避坑选课", "", "") { startActivity(Intent(requireContext(), CourseReviewActivity::class.java)) },
            ToolboxItem("教师评价", "快速完成教师评价", "xsjxpj.aspx", "N121401") { startActivity(Intent(requireContext(), CommentTeacherActivity::class.java)) },
            ToolboxItem("成绩预测", "预测期末需要多少分及格", "", "") { startActivity(Intent(requireContext(), ScoreCalculatorActivity::class.java)) },
            ToolboxItem("番茄钟", "专注计时，宠物获得经验", "", "") { startActivity(Intent(requireContext(), PomodoroTimerActivity::class.java)) },
            ToolboxItem("目标倒计时", "四六级、考研、期末倒计时", "", "") { startActivity(Intent(requireContext(), CountdownActivity::class.java)) },
            ToolboxItem("校园地图", "查看校园常用地点", "", "") { startActivity(Intent(requireContext(), CampusMapActivity::class.java)) },
            ToolboxItem("成绩单截图", "生成精美成绩单图片", "", "") { startActivity(Intent(requireContext(), ScoreCardActivity::class.java)) },
            ToolboxItem("课表导入日历", "一键导出课表到系统日历", "", "") { startActivity(Intent(requireContext(), CalendarExportActivity::class.java)) },
            ToolboxItem("备忘录", "快捷笔记，录音，AI归类", "", "") { startActivity(Intent(requireContext(), NoteListActivity::class.java)) },
            ToolboxItem("选修学分查询", "查看选修学分完成情况", "", "") { startActivity(Intent(requireContext(), ElectiveScoreActivity::class.java)) },
            ToolboxItem("选课情况查询", "查看全部选课记录", "xsxkqk.aspx", "N121615") { startActivity(Intent(requireContext(), CourseSelectionActivity::class.java)) },
            ToolboxItem("补考考试查询", "查询补考考试安排", "xsbkkscx.aspx", "N121617") { startActivity(Intent(requireContext(), MakeupExamActivity::class.java)) },
            ToolboxItem("培养计划", "查看个人培养方案", "pyjh.aspx", "N121607") { startActivity(Intent(requireContext(), TrainingPlanActivity::class.java)) },
            ToolboxItem("个人信息", "查看学籍基本信息", "xsgrxx.aspx", "N121501") { startActivity(Intent(requireContext(), StudentInfoActivity::class.java)) },
            ToolboxItem("密码修改", "修改教务系统密码", "mmxg.aspx", "N121502") { startActivity(Intent(requireContext(), PasswordModifyActivity::class.java)) },
            ToolboxItem("等级考试查询", "查询等级考试成绩", "xsdjkscx.aspx", "N121606") { startActivity(Intent(requireContext(), GradeExamActivity::class.java)) },
            ToolboxItem("网上报名", "查看报名项目", "bmxmb2.aspx", "N121303") { startActivity(Intent(requireContext(), OnlineRegistrationActivity::class.java)) },
            ToolboxItem("设置", "通知、缓存、账号管理", "", "") { startActivity(Intent(requireContext(), SettingsActivity::class.java)) },
            ToolboxItem("关于", "关于 iFAFU", "", "") { startActivity(Intent(requireContext(), AboutActivity::class.java)) }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 14f; cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
                isClickable = true; isFocusable = true
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

            content.addView(icon)
            content.addView(View(parent.context).apply { layoutParams = LinearLayout.LayoutParams(20, 1) })
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

        inner class ViewHolder(itemView: View, val title: TextView, val subtitle: TextView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
