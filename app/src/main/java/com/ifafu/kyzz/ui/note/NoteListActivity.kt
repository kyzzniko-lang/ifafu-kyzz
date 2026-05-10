package com.ifafu.kyzz.ui.note

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Note
import com.ifafu.kyzz.data.repository.NoteRepository
import com.ifafu.kyzz.databinding.ActivityNoteListBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteListActivity : BaseActivity<ActivityNoteListBinding>() {

    override fun createBinding() = ActivityNoteListBinding.inflate(layoutInflater)

    private val repo by lazy { NoteRepository(this) }
    private var allNotes = listOf<Note>()
    private var filteredNotes = listOf<Note>()
    private var selectedCategory = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, NoteEditActivity::class.java))
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })

        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        allNotes = repo.loadNotes().sortedByDescending { it.updatedAt }
        setupChips()
        applyFilter()
    }

    private fun setupChips() {
        binding.chipGroup.removeAllViews()
        // "全部" chip
        val allChip = Chip(this).apply {
            text = "全部"
            isClickable = true
            isCheckable = true
            setChipBackgroundColorResource(R.color.claude_terracotta)
            setTextColor(resources.getColor(android.R.color.white, null))
            id = View.generateViewId()
        }
        allChip.setOnClickListener {
            selectedCategory = ""
            applyFilter()
            highlightChip(allChip)
        }
        binding.chipGroup.addView(allChip)

        // 收藏
        val favChip = Chip(this).apply {
            text = "收藏"
            isClickable = true
            isCheckable = true
            id = View.generateViewId()
        }
        favChip.setOnClickListener {
            selectedCategory = "__favorite__"
            applyFilter()
            highlightChip(favChip)
        }
        binding.chipGroup.addView(favChip)

        // 动态分类
        for (cat in repo.getCategories()) {
            val chip = Chip(this).apply {
                text = cat
                isClickable = true
                isCheckable = true
                id = View.generateViewId()
            }
            chip.setOnClickListener {
                selectedCategory = cat
                applyFilter()
                highlightChip(chip)
            }
            binding.chipGroup.addView(chip)
        }
        highlightChip(allChip)
    }

    private fun highlightChip(active: Chip) {
        for (i in 0 until binding.chipGroup.childCount) {
            val chip = binding.chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip === active) {
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.claude_terracotta, null)
                )
                chip.setTextColor(resources.getColor(android.R.color.white, null))
            } else {
                chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.claude_bg, null)
                )
                chip.setTextColor(resources.getColor(R.color.claude_text_secondary, null))
            }
        }
    }

    private fun applyFilter() {
        val query = binding.etSearch.text?.toString()?.trim()?.lowercase() ?: ""
        filteredNotes = allNotes.filter { note ->
            val matchCategory = when {
                selectedCategory.isEmpty() -> true
                selectedCategory == "__favorite__" -> note.isFavorite
                else -> note.category == selectedCategory
            }
            val matchQuery = query.isEmpty() ||
                note.title.lowercase().contains(query) ||
                note.content.lowercase().contains(query)
            matchCategory && matchQuery
        }
        binding.tvEmpty.visibility = if (filteredNotes.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (filteredNotes.isEmpty()) View.GONE else View.VISIBLE
        (binding.recyclerView.adapter as? NoteAdapter)?.updateData(filteredNotes)
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = NoteAdapter()
    }

    inner class NoteAdapter : RecyclerView.Adapter<NoteAdapter.VH>() {
        private var data: List<Note> = emptyList()

        fun updateData(newData: List<Note>) {
            data = newData
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
            }
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val note = data[position]
            holder.container.removeAllViews()

            val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

            // 第一行：标题 + 收藏标记
            val row1 = LinearLayout(this@NoteListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row1.addView(TextView(this@NoteListActivity).apply {
                text = note.title.ifEmpty { "无标题" }
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
            })
            if (note.isFavorite) {
                row1.addView(TextView(this@NoteListActivity).apply {
                    text = "★"
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                })
            }
            if (note.audioPath.isNotEmpty()) {
                row1.addView(TextView(this@NoteListActivity).apply {
                    text = " 🎤"
                    textSize = 14f
                })
            }
            holder.container.addView(row1)

            // 第二行：内容预览
            if (note.content.isNotEmpty()) {
                holder.container.addView(TextView(this@NoteListActivity).apply {
                    text = note.content.take(80)
                    textSize = 13f
                    setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    maxLines = 2
                    setPadding(0, 4, 0, 0)
                })
            }

            // 第三行：分类 + 时间
            val row3 = LinearLayout(this@NoteListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 0)
            }
            if (note.category.isNotEmpty()) {
                row3.addView(TextView(this@NoteListActivity).apply {
                    text = note.category
                    textSize = 11f
                    setTextColor(resources.getColor(R.color.claude_terracotta, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    setPadding(8, 2, 8, 2)
                    setBackgroundResource(R.drawable.bg_chip_outline)
                })
            }
            row3.addView(TextView(this@NoteListActivity).apply {
                text = sdf.format(Date(note.updatedAt))
                textSize = 11f
                setTextColor(resources.getColor(R.color.claude_text_hint, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END }
                gravity = Gravity.END
            })
            holder.container.addView(row3)

            // 点击编辑
            holder.container.setOnClickListener {
                startActivity(
                    Intent(this@NoteListActivity, NoteEditActivity::class.java)
                        .putExtra("note_id", note.id)
                )
            }

            // 长按删除
            holder.container.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@NoteListActivity)
                    .setTitle("删除笔记")
                    .setMessage("删除「${note.title.ifEmpty { "无标题" }}」？")
                    .setPositiveButton("删除") { _, _ ->
                        repo.deleteNote(note.id)
                        loadData()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = data.size

        inner class VH(val container: LinearLayout) : RecyclerView.ViewHolder(container)
    }
}
