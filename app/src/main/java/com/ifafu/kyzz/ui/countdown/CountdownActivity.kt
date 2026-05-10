package com.ifafu.kyzz.ui.countdown

import android.app.DatePickerDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.CountdownEvent
import com.ifafu.kyzz.databinding.ActivityCountdownBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class CountdownActivity : BaseActivity<ActivityCountdownBinding>() {

    override fun createBinding() = ActivityCountdownBinding.inflate(layoutInflater)

    private val prefs by lazy { getSharedPreferences("countdown_prefs", MODE_PRIVATE) }
    private val gson = Gson()
    private var events = mutableListOf<CountdownEvent>()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadEvents()
        setupRecyclerView()

        binding.fabAdd.setOnClickListener { showAddDialog() }
    }

    private fun loadEvents() {
        val json = prefs.getString("events", null)
        events = if (json != null) {
            try { gson.fromJson(json, object : TypeToken<MutableList<CountdownEvent>>() {}.type) }
            catch (_: Exception) { mutableListOf() }
        } else { mutableListOf() }
    }

    private fun saveEvents() {
        prefs.edit().putString("events", gson.toJson(events)).apply()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = CountdownAdapter()
        refreshList()
    }

    private fun refreshList() {
        binding.tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        (binding.recyclerView.adapter as? CountdownAdapter)?.updateData(events)
    }

    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_countdown, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etEventName)
        val tvDate = dialogView.findViewById<TextView>(R.id.tvSelectedDate)
        val rowDate = dialogView.findViewById<android.view.View>(R.id.rowDate)

        var selectedDate = ""

        rowDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, day ->
                selectedDate = String.format("%04d-%02d-%02d", year, month + 1, day)
                tvDate.text = selectedDate
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""
                if (name.isEmpty() || selectedDate.isEmpty()) return@setPositiveButton
                events.add(CountdownEvent(UUID.randomUUID().toString(), name, selectedDate))
                saveEvents()
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteEvent(id: String) {
        events.removeAll { it.id == id }
        saveEvents()
        refreshList()
    }

    inner class CountdownAdapter : RecyclerView.Adapter<CountdownAdapter.VH>() {
        private var data: List<CountdownEvent> = emptyList()

        fun updateData(newData: List<CountdownEvent>) {
            data = newData
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
                radius = 14f; cardElevation = 0f
                strokeColor = resources.getColor(R.color.claude_border, null)
                strokeWidth = 1
                setCardBackgroundColor(resources.getColor(R.color.claude_bg_elevated, null))
            }
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 16, 20, 16)
            }
            card.addView(content)
            return VH(card, content)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val event = data[position]
            holder.content.removeAllViews()

            val textCol = LinearLayout(holder.content.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val days = calcDays(event.date)
            val daysText = when {
                days < 0 -> "已过 ${-days} 天"
                days == 0L -> "就是今天！"
                else -> "${days} 天后"
            }

            textCol.addView(TextView(holder.content.context).apply {
                text = event.name
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
            })
            textCol.addView(TextView(holder.content.context).apply {
                text = "${event.date}  ${daysText}"
                textSize = 12f
                setTextColor(resources.getColor(
                    if (days in 0..7) R.color.claude_terracotta else R.color.claude_text_hint, null
                ))
                typeface = resources.getFont(R.font.claude_serif)
                setPadding(0, 4, 0, 0)
            })

            holder.content.addView(textCol)

            holder.content.addView(TextView(holder.content.context).apply {
                text = daysText
                textSize = 20f
                setTextColor(resources.getColor(
                    if (days in 0..7) R.color.claude_terracotta else R.color.claude_text_primary, null
                ))
                typeface = resources.getFont(R.font.claude_serif)
            })

            holder.itemView.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@CountdownActivity)
                    .setTitle("删除")
                    .setMessage("删除「${event.name}」？")
                    .setPositiveButton("删除") { _, _ -> deleteEvent(event.id) }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount() = data.size

        inner class VH(itemView: View, val content: LinearLayout) : RecyclerView.ViewHolder(itemView)
    }

    private fun calcDays(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val target = sdf.parse(dateStr) ?: return -1
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val diff = (target.time - today.time.time) / (24 * 60 * 60 * 1000)
            diff
        } catch (_: Exception) { -1 }
    }
}
