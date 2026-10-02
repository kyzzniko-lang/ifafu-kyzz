package com.ifafu.kyzz.ui.toolbox

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
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
import com.ifafu.kyzz.ui.syllabus.SyllabusActivity
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

        fun open(cls: Class<*>) = startActivity(Intent(requireContext(), cls))

        // 按用途分组：21 个入口平铺成同质长列表时很难找，分组 + 图标让功能一眼可辨
        val groups = listOf(
            "教务查询" to listOf(
                ToolboxItem("一维课表", "列表形式查看课表", R.drawable.ic_tool_list) { open(SyllabusActivity::class.java) },
                ToolboxItem("成绩预测", "预测期末需要多少分及格", R.drawable.ic_tool_calculator) { open(ScoreCalculatorActivity::class.java) },
                ToolboxItem("选修学分查询", "查看选修学分完成情况", R.drawable.ic_tool_award) { open(ElectiveScoreActivity::class.java) },
                ToolboxItem("选课情况查询", "查看全部选课记录", R.drawable.ic_tool_checklist) { open(CourseSelectionActivity::class.java) },
                ToolboxItem("补考考试查询", "查询补考考试安排", R.drawable.ic_tool_retry) { open(MakeupExamActivity::class.java) },
                ToolboxItem("等级考试查询", "查询等级考试成绩", R.drawable.ic_tool_certificate) { open(GradeExamActivity::class.java) },
                ToolboxItem("培养计划", "查看个人培养方案", R.drawable.ic_tool_book) { open(TrainingPlanActivity::class.java) },
                ToolboxItem("教师评价", "快速完成教师评价", R.drawable.ic_tool_clipboard) { open(CommentTeacherActivity::class.java) }
            ),
            "校园" to listOf(
                ToolboxItem("校园讨论", "匿名讨论，畅所欲言", R.drawable.ic_tool_chat) { open(DiscussionActivity::class.java) },
                ToolboxItem("课程评价", "查看课程评价，避坑选课", R.drawable.ic_tool_star) { open(CourseReviewActivity::class.java) },
                ToolboxItem("校园地图", "查看校园常用地点", R.drawable.ic_tool_map) { open(CampusMapActivity::class.java) },
                ToolboxItem("网上报名", "查看报名项目", R.drawable.ic_tool_form) { open(OnlineRegistrationActivity::class.java) }
            ),
            "效率" to listOf(
                ToolboxItem("番茄钟", "专注计时，宠物获得经验", R.drawable.ic_tool_timer) { open(PomodoroTimerActivity::class.java) },
                ToolboxItem("目标倒计时", "四六级、考研、期末倒计时", R.drawable.ic_tool_flag) { open(CountdownActivity::class.java) },
                ToolboxItem("备忘录", "快捷笔记，录音，AI归类", R.drawable.ic_tool_note) { open(NoteListActivity::class.java) },
                ToolboxItem("成绩单截图", "生成精美成绩单图片", R.drawable.ic_tool_image) { open(ScoreCardActivity::class.java) },
                ToolboxItem("课表导入日历", "一键导出课表到系统日历", R.drawable.ic_tool_calendar) { open(CalendarExportActivity::class.java) }
            ),
            "账号" to listOf(
                ToolboxItem("个人信息", "查看学籍基本信息", R.drawable.ic_tool_person) { open(StudentInfoActivity::class.java) },
                ToolboxItem("密码修改", "修改教务系统密码", R.drawable.ic_tool_key) { open(PasswordModifyActivity::class.java) },
                ToolboxItem("设置", "通知、缓存、账号管理", R.drawable.ic_settings) { open(SettingsActivity::class.java) },
                ToolboxItem("关于", "关于 iFAFU", R.drawable.ic_tool_info) { open(AboutActivity::class.java) }
            )
        )

        groups.forEachIndexed { index, (label, items) ->
            binding.groupContainer.addView(createGroupLabel(label, isFirst = index == 0))
            binding.groupContainer.addView(createGroupCard(items))
        }
    }

    data class ToolboxItem(
        val title: String,
        val subtitle: String,
        @DrawableRes val iconRes: Int,
        val onClick: () -> Unit
    )

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun createGroupLabel(label: String, isFirst: Boolean): TextView =
        TextView(requireContext()).apply {
            text = label
            typeface = ResourcesCompat.getFont(context, R.font.claude_serif)
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(resources.getColor(R.color.claude_terracotta, null))
            setPadding(dp(4f), dp(if (isFirst) 12f else 24f), 0, dp(8f))
        }

    /** 一组入口放进同一张卡片，行间用与文字列对齐的缩进细线分隔（与首页"快捷入口"一致）。 */
    private fun createGroupCard(items: List<ToolboxItem>): MaterialCardView {
        val ctx = requireContext()
        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            radius = dp(14f).toFloat()
            cardElevation = 0f
            strokeColor = resources.getColor(R.color.claude_border_light, null)
            strokeWidth = dp(1f)
            setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, item ->
            if (i > 0) {
                column.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                        marginStart = dp(66f)
                    }
                    setBackgroundColor(resources.getColor(R.color.claude_divider, null))
                })
            }
            column.addView(createRow(item))
        }
        card.addView(column)
        return card
    }

    private fun createRow(item: ToolboxItem): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener { item.onClick() }
        }

        val iconTile = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f))
            setBackgroundResource(R.drawable.bg_shortcut_icon)
        }
        iconTile.addView(ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER)
            setImageResource(item.iconRes)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.claude_terracotta)
            contentDescription = null
        })

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14f)
                marginEnd = dp(8f)
            }
        }
        texts.addView(TextView(ctx).apply {
            text = item.title
            typeface = ResourcesCompat.getFont(ctx, R.font.claude_serif)
            textSize = 15f
            setTextColor(resources.getColor(R.color.claude_text_primary, null))
        })
        texts.addView(TextView(ctx).apply {
            text = item.subtitle
            typeface = android.graphics.Typeface.SANS_SERIF
            textSize = 12f
            setTextColor(resources.getColor(R.color.claude_text_tertiary, null))
            setPadding(0, dp(2f), 0, 0)
        })

        row.addView(iconTile)
        row.addView(texts)
        row.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f))
            setImageResource(R.drawable.ic_chevron_right)
            alpha = 0.6f
            contentDescription = null
        })
        return row
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
