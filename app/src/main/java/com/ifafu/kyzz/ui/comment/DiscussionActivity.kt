package com.ifafu.kyzz.ui.comment

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Comment
import com.ifafu.kyzz.databinding.ActivityCommentBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DiscussionActivity : BaseActivity<ActivityCommentBinding>() {

    private val viewModel: DiscussionViewModel by viewModels()
    private var nicknameDialog: AlertDialog? = null

    override fun createBinding(): ActivityCommentBinding = ActivityCommentBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_change_nickname -> {
                    showNicknameDialog()
                    true
                }
                else -> false
            }
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.claude_terracotta)
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadComments(refresh = true) }
        binding.btnRetry.setOnClickListener { viewModel.loadComments(refresh = true) }

        binding.btnSend.setOnClickListener {
            val content = binding.etComment.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tag = viewModel.selectedTag.value?.let {
                if (it == DiscussionViewModel.Tag.ALL) "" else it.label
            } ?: ""
            viewModel.postComment(content, tag)
        }

        setupRecyclerView()
        setupTagChips()
        observeViewModel()

        viewModel.checkNickname()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = CommentAdapter()
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val totalItems = layoutManager.itemCount
                if (lastVisible >= totalItems - 3) {
                    viewModel.loadComments()
                }
            }
        })
    }

    private fun setupTagChips() {
        val tags = DiscussionViewModel.Tag.values()
        for (tag in tags) {
            val chip = Chip(this).apply {
                text = tag.label
                isCheckable = true
                isChecked = tag == DiscussionViewModel.Tag.ALL
                setOnClickListener { viewModel.setTag(tag) }
                setChipBackgroundColorResource(R.color.claude_bg_elevated)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                chipStrokeWidth = 1f
                setChipStrokeColorResource(R.color.claude_border)
            }
            binding.chipGroup.addView(chip)
        }
    }

    private fun observeViewModel() {
        viewModel.nicknameState.observe(this) { state ->
            when (state) {
                is DiscussionViewModel.NicknameState.NotSet -> showNicknameDialog()
                is DiscussionViewModel.NicknameState.Ready -> {
                    nicknameDialog?.dismiss()
                    viewModel.loadComments()
                }
                is DiscussionViewModel.NicknameState.Error -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.state.observe(this) { state ->
            when (state) {
                is DiscussionViewModel.DiscussionState.Loading -> {
                    binding.petLoading.root.startLoading()
                    binding.recyclerView.visibility = View.GONE
                    binding.errorLayout.visibility = View.GONE
                    binding.tvEmpty.visibility = View.GONE
                }
                is DiscussionViewModel.DiscussionState.LoadingMore -> {
                    // 保持当前列表显示
                }
                is DiscussionViewModel.DiscussionState.Success -> {
                    binding.petLoading.root.stopLoading()
                    binding.swipeRefresh.isRefreshing = false
                    if (state.comments.isEmpty()) {
                        binding.recyclerView.visibility = View.GONE
                        binding.tvEmpty.visibility = View.VISIBLE
                    } else {
                        binding.recyclerView.visibility = View.VISIBLE
                        binding.tvEmpty.visibility = View.GONE
                        (binding.recyclerView.adapter as? CommentAdapter)?.updateData(
                            state.comments, state.currentUserId
                        )
                    }
                }
                is DiscussionViewModel.DiscussionState.Error -> {
                    binding.petLoading.root.stopLoading()
                    binding.swipeRefresh.isRefreshing = false
                    binding.errorLayout.visibility = View.VISIBLE
                    binding.tvError.text = state.message
                }
            }
        }

        viewModel.postState.observe(this) { state ->
            when (state) {
                is DiscussionViewModel.PostState.Loading -> {
                    binding.btnSend.isEnabled = false
                }
                is DiscussionViewModel.PostState.Success -> {
                    binding.btnSend.isEnabled = true
                    binding.etComment.text?.clear()
                    binding.recyclerView.scrollToPosition(0)
                }
                is DiscussionViewModel.PostState.Error -> {
                    binding.btnSend.isEnabled = true
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.deleteState.observe(this) { state ->
            when (state) {
                is DiscussionViewModel.DeleteState.Error -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
    }

    private fun showNicknameDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_nickname, null)
        val etNickname = dialogView.findViewById<TextInputEditText>(R.id.etNickname)

        val currentNickname = (viewModel.nicknameState.value as? DiscussionViewModel.NicknameState.Ready)?.nickname
        if (!currentNickname.isNullOrEmpty()) {
            etNickname.setText(currentNickname)
        }

        nicknameDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .show()

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener {
            if (viewModel.nicknameState.value is DiscussionViewModel.NicknameState.NotSet) {
                finish()
            } else {
                nicknameDialog?.dismiss()
            }
        }

        dialogView.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            when {
                nickname.isEmpty() -> etNickname.error = "请输入昵称"
                nickname.length < 2 -> etNickname.error = "昵称至少2个字符"
                else -> viewModel.saveNickname(nickname)
            }
        }
    }

    inner class CommentAdapter : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {
        private var items: List<Comment> = emptyList()
        private var currentUserId: String = ""

        fun updateData(comments: List<Comment>, userId: String) {
            items = comments
            currentUserId = userId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                radius = 14f
                cardElevation = 0f
                strokeWidth = 1
                strokeColor = resources.getColor(R.color.claude_border, null)
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
            }
            card.addView(content)
            return CommentViewHolder(card, content)
        }

        override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
            val comment = items[position]
            holder.content.removeAllViews()

            // 头部：昵称 + 时间 + 删除按钮
            val header = LinearLayout(holder.itemView.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvNickname = TextView(holder.itemView.context).apply {
                text = comment.nickname
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTime = TextView(holder.itemView.context).apply {
                text = formatTime(comment.createdAt)
                setTextAppearance(R.style.ClaudeCaption)
                typeface = resources.getFont(R.font.claude_serif)
            }

            header.addView(tvNickname)
            header.addView(tvTime)

            if (comment.authorId == currentUserId) {
                val ivDelete = ImageView(holder.itemView.context).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setColorFilter(resources.getColor(R.color.claude_text_tertiary, null))
                    setPadding(8, 4, 0, 4)
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@DiscussionActivity)
                            .setTitle("删除评论")
                            .setMessage("确定要删除这条评论吗？")
                            .setPositiveButton("删除") { _, _ -> viewModel.deleteComment(comment.objectId) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                val params = LinearLayout.LayoutParams(48, 48).apply { marginStart = 8 }
                header.addView(ivDelete, params)
            }

            holder.content.addView(header)

            // 话题标签
            if (comment.tag.isNotEmpty()) {
                holder.content.addView(TextView(holder.itemView.context).apply {
                    text = comment.tag
                    textSize = 10f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 8f
                        setColor(0x1AD4724A.toInt())
                    }
                    setPadding(12, 4, 12, 4)
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = 8
                    layoutParams = lp
                })
            }

            // 内容
            val tvContent = TextView(holder.itemView.context).apply {
                text = comment.content
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                setPadding(0, 12, 0, 0)
            }
            holder.content.addView(tvContent)

            // 底部：点赞按钮
            val likeRow = LinearLayout(holder.itemView.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 0)
            }
            val isLiked = comment.likes.contains(currentUserId)
            val likeBtn = TextView(holder.itemView.context).apply {
                text = if (comment.likes.isNotEmpty()) "♥ ${comment.likes.size}" else "♡"
                textSize = 13f
                setTextColor(resources.getColor(
                    if (isLiked) R.color.claude_terracotta else R.color.claude_text_hint, null
                ))
                typeface = resources.getFont(R.font.claude_serif)
                setOnClickListener { viewModel.likeComment(comment) }
            }
            likeRow.addView(likeBtn)
            holder.content.addView(likeRow)
        }

        override fun getItemCount() = items.size

        inner class CommentViewHolder(
            itemView: View,
            val content: LinearLayout
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun formatTime(createdAt: String): String {
        if (createdAt.isEmpty()) return ""
        return try {
            // LeanCloud 返回格式: 2024-01-15T10:30:00.000Z
            val date = createdAt.substring(0, 10)
            val time = createdAt.substring(11, 16)
            "$date $time"
        } catch (e: Exception) {
            createdAt
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nicknameDialog?.dismiss()
    }
}
