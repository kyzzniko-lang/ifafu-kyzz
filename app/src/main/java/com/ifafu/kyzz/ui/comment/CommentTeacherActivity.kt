package com.ifafu.kyzz.ui.comment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.activity.viewModels
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ifafu.kyzz.data.api.CommentTeacherApi.EvalItem
import com.ifafu.kyzz.data.api.CommentTeacherApi.EvalMode
import com.ifafu.kyzz.data.api.CommentTeacherApi.ScoreLevel
import com.ifafu.kyzz.databinding.ActivityCommentTeacherBinding
import com.ifafu.kyzz.databinding.ItemEvalScoreBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import com.ifafu.kyzz.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CommentTeacherActivity : BaseActivity<ActivityCommentTeacherBinding>() {

    private val viewModel: CommentViewModel by viewModels()
    private val levelLabels = ScoreLevel.entries.map { it.label }

    override fun createBinding(): ActivityCommentTeacherBinding =
        ActivityCommentTeacherBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = "一键评教"

        setupModeSelection()
        setupSpinners()
        setupButtons()
        observeState()
    }

    private fun setupModeSelection() {
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbSemiAuto -> EvalMode.SemiAuto(
                    viewModel.semiMin.value ?: ScoreLevel.GOOD,
                    viewModel.semiMax.value ?: ScoreLevel.MEDIUM
                )
                R.id.rbManual -> EvalMode.Manual
                else -> EvalMode.FullAuto
            }
            viewModel.setEvalMode(mode)
            binding.layoutSemiAuto.visibility =
                if (checkedId == R.id.rbSemiAuto) View.VISIBLE else View.GONE
        }
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, levelLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.spinnerMin.adapter = adapter
        binding.spinnerMax.adapter = adapter
        binding.spinnerMin.setSelection(ScoreLevel.GOOD.ordinal) // 默认 良好
        binding.spinnerMax.setSelection(ScoreLevel.MEDIUM.ordinal) // 默认 中等

        binding.spinnerMin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                viewModel.setSemiMin(ScoreLevel.fromIndex(pos))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spinnerMax.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                viewModel.setSemiMax(ScoreLevel.fromIndex(pos))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener {
            viewModel.startComment()
        }
        binding.btnSave.setOnClickListener {
            viewModel.submitCurrentCourse(binding.etPerCourseComment.text?.toString()?.trim() ?: "")
        }
        binding.btnSubmitFinal.setOnClickListener {
            viewModel.submitFinal()
        }
    }

    private fun observeState() {
        viewModel.state.observe(this) { state ->
            when (state) {
                is CommentViewModel.CommentState.Idle -> {
                    binding.tvStatus.text = "点击下方按钮开始评教"
                    binding.progressBar.visibility = View.GONE
                    binding.btnStart.isEnabled = true
                    binding.layoutModeSelect.visibility = View.VISIBLE
                    binding.layoutManual.visibility = View.GONE

                    binding.btnStart.visibility = View.VISIBLE
                }
                is CommentViewModel.CommentState.Loading -> {
                    binding.tvStatus.text = state.message
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnStart.isEnabled = false
                    binding.layoutModeSelect.visibility = View.GONE
                    binding.layoutManual.visibility = View.GONE

                    binding.btnStart.visibility = View.VISIBLE
                }
                is CommentViewModel.CommentState.ManualInput -> {
                    binding.progressBar.visibility = View.GONE
                    binding.layoutModeSelect.visibility = View.GONE
                    binding.btnStart.visibility = View.GONE
                    binding.layoutManual.visibility = View.VISIBLE

                    binding.tvStatus.text = state.message
                }
                is CommentViewModel.CommentState.Success -> {
                    binding.tvStatus.text = state.message
                    binding.progressBar.visibility = View.GONE
                    binding.btnStart.isEnabled = false
                    binding.layoutModeSelect.visibility = View.GONE
                    binding.layoutManual.visibility = View.GONE

                    binding.btnStart.visibility = View.VISIBLE
                }
                is CommentViewModel.CommentState.Error -> {
                    binding.tvStatus.text = state.message
                    binding.progressBar.visibility = View.GONE
                    binding.btnStart.isEnabled = true
                    binding.layoutModeSelect.visibility = View.VISIBLE
                    binding.layoutManual.visibility = View.GONE

                    binding.btnStart.visibility = View.VISIBLE
                }
            }
        }

        viewModel.manualItems.observe(this) { items ->
            setupManualList(items)
        }
        viewModel.manualSelections.observe(this) { _ ->
            evalAdapter?.let {
                val newMap = viewModel.manualSelections.value ?: return@let
                it.updateSelections(newMap)
                it.notifyDataSetChanged()
            }
        }

        viewModel.courseLabels.observe(this) { labels ->
            val spinner = binding.spnCourses
            val currentAdapter = spinner.adapter as? android.widget.ArrayAdapter<String>
            if (currentAdapter != null) {
                currentAdapter.clear()
                currentAdapter.addAll(labels)
                currentAdapter.notifyDataSetChanged()
            } else if (labels.isNotEmpty()) {
                val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.setSelection(viewModel.currentCourseIndex.value ?: 0)
            }
        }
        viewModel.currentCourseIndex.observe(this) { idx ->
            if (binding.spnCourses.adapter != null && binding.spnCourses.selectedItemPosition != idx) {
                binding.spnCourses.setSelection(idx)
            }
        }
        binding.spnCourses.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var userInitiated = false
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val currentIdx = viewModel.currentCourseIndex.value ?: 0
                if (pos != currentIdx) {
                    viewModel.switchToCourse(pos)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        viewModel.currentTeacherName.observe(this) { name ->
            binding.tvTeacherName.text = name
            binding.tvTeacherName.visibility = if (name.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnWhitelist.text = if (viewModel.isWhitelisted) "⭐ 已关照" else "特别关照"
            binding.btnWhitelist.visibility = if (name.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnBlacklist.text = if (viewModel.isBlacklisted) "✅ 已拉黑" else "拉黑此教师"
            binding.btnBlacklist.visibility = if (name.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.btnWhitelist.setOnClickListener {
            val name = viewModel.currentTeacherName.value ?: return@setOnClickListener
            viewModel.toggleWhitelist(name)
        }
        binding.btnBlacklist.setOnClickListener {
            val name = viewModel.currentTeacherName.value ?: return@setOnClickListener
            viewModel.toggleBlacklist(name)
        }
    }

    private var evalAdapter: EvalItemAdapter? = null

    private fun setupManualList(items: List<EvalItem>) {
        val adapter = EvalItemAdapter(items, viewModel.manualSelections.value ?: mutableMapOf()) { fieldKey, level ->
            viewModel.setManualSelection(fieldKey, level)
        }
        evalAdapter = adapter
        binding.rvItems.layoutManager = LinearLayoutManager(this)
        binding.rvItems.adapter = adapter
    }

    // ── RecyclerView Adapter ──

    inner class EvalItemAdapter(
        private val items: List<EvalItem>,
        private var selections: MutableMap<String, ScoreLevel>,
        private val onSelectionChanged: (String, ScoreLevel) -> Unit
    ) : RecyclerView.Adapter<EvalItemAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemEvalScoreBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        fun updateSelections(newSelections: MutableMap<String, ScoreLevel>) {
            selections = newSelections
        }

        inner class ViewHolder(private val binding: ItemEvalScoreBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: EvalItem) {
                binding.tvItemDesc.text = item.description
                val currentLevel = selections[item.fieldKey] ?: ScoreLevel.GOOD
                val spinner = binding.spinnerScore

                if (spinner.adapter == null) {
                    val adapter = ArrayAdapter(
                        itemView.context,
                        android.R.layout.simple_spinner_item,
                        levelLabels
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinner.adapter = adapter
                }

                // 避免触发 listener
                spinner.onItemSelectedListener = null
                spinner.setSelection(currentLevel.ordinal)

                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        onSelectionChanged(item.fieldKey, ScoreLevel.fromIndex(pos))
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
        }
    }
}
