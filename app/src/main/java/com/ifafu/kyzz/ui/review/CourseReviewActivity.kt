package com.ifafu.kyzz.ui.review

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.observe
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.CourseReview
import com.ifafu.kyzz.databinding.ActivityCourseReviewBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CourseReviewActivity : BaseActivity<ActivityCourseReviewBinding>() {

    override fun createBinding() = ActivityCourseReviewBinding.inflate(layoutInflater)
    private val viewModel: CourseReviewViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val courseName = intent.getStringExtra("course_name") ?: ""
        if (courseName.isNotEmpty()) {
            binding.etSearch.setText(courseName)
        }

        setupRecyclerView()
        setupSearch()
        setupFab()
        observeState()

        viewModel.loadReviews()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ReviewAdapter(emptyList())
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.filterReviews(s?.toString() ?: "")
            }
        })
    }

    private fun setupFab() {
        binding.fabWrite.setOnClickListener { showWriteReviewDialog() }
    }

    private fun observeState() {
        viewModel.state.observe(this) { state ->
            when (state) {
                is CourseReviewViewModel.State.Loading -> {
                    binding.petLoading.root.startLoading()
                    binding.contentLayout.visibility = View.GONE
                    binding.errorLayout.visibility = View.GONE
                }
                is CourseReviewViewModel.State.Success -> {
                    binding.petLoading.root.stopLoading()
                    binding.contentLayout.visibility = View.VISIBLE
                    binding.errorLayout.visibility = View.GONE
                }
                is CourseReviewViewModel.State.Error -> {
                    binding.petLoading.root.stopLoading()
                    binding.contentLayout.visibility = View.GONE
                    binding.errorLayout.visibility = View.VISIBLE
                    binding.tvError.text = state.message
                }
                else -> {}
            }
        }

        viewModel.reviews.observe(this) { reviews ->
            val adapter = binding.recyclerView.adapter as ReviewAdapter
            adapter.updateData(reviews)
            binding.emptyLayout.visibility = if (reviews.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnRetry.setOnClickListener { viewModel.loadReviews() }
    }

    private fun showWriteReviewDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_write_review, null)
        val etCourseName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCourseName)
        val etTeacher = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTeacher)
        val seekDifficulty = dialogView.findViewById<android.widget.SeekBar>(R.id.seekDifficulty)
        val seekGrading = dialogView.findViewById<android.widget.SeekBar>(R.id.seekGrading)
        val seekAttendance = dialogView.findViewById<android.widget.SeekBar>(R.id.seekAttendance)
        val etComment = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etComment)
        val btnSubmit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSubmit)

        // Pre-fill course name from search
        val search = binding.etSearch.text?.toString()?.trim() ?: ""
        if (search.isNotEmpty()) etCourseName.setText(search)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSubmit.setOnClickListener {
            val courseName = etCourseName.text?.toString()?.trim() ?: ""
            val teacher = etTeacher.text?.toString()?.trim() ?: ""
            if (courseName.isEmpty()) {
                etCourseName.error = "请输入课程名称"
                return@setOnClickListener
            }
            viewModel.postReview(
                courseName = courseName,
                teacher = teacher,
                difficulty = seekDifficulty.progress + 1,
                grading = seekGrading.progress + 1,
                attendance = seekAttendance.progress + 1,
                comment = etComment.text?.toString()?.trim() ?: ""
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    inner class ReviewAdapter(private var data: List<CourseReview>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<ReviewAdapter.VH>() {

        fun updateData(newData: List<CourseReview>) {
            data = newData
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
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
            val review = data[position]
            holder.content.removeAllViews()

            // Title row: course name + teacher
            holder.content.addView(TextView(this@CourseReviewActivity).apply {
                text = if (review.teacher.isNotEmpty()) "${review.courseName} · ${review.teacher}" else review.courseName
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_terracotta, null))
                typeface = resources.getFont(R.font.claude_serif)
            })

            // Rating bars row
            val ratingRow = LinearLayout(this@CourseReviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val dp = resources.displayMetrics.density
            val labels = listOf("难度${review.difficulty}", "给分${review.grading}", "点名${review.attendance}")
            val colors = listOf(
                resources.getColor(R.color.claude_terracotta, null),
                resources.getColor(R.color.claude_success, null),
                resources.getColor(R.color.claude_warning, null)
            )
            for (i in labels.indices) {
                ratingRow.addView(TextView(this@CourseReviewActivity).apply {
                    text = labels[i]
                    textSize = 10f
                    setTextColor(colors[i])
                    typeface = resources.getFont(R.font.claude_serif)
                    setPadding(0, 4, (8 * dp).toInt(), 4)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 8f
                        setColor(colors[i].and(0x00FFFFFF).or(0x1A000000.toInt()))
                    }
                })
            }
            holder.content.addView(ratingRow)

            // Comment
            if (review.comment.isNotEmpty()) {
                holder.content.addView(TextView(this@CourseReviewActivity).apply {
                    text = review.comment
                    setTextAppearance(R.style.ClaudeCaption)
                    setPadding(0, 6, 0, 0)
                    typeface = resources.getFont(R.font.claude_serif)
                })
            }

            // Footer: nickname + date
            holder.content.addView(TextView(this@CourseReviewActivity).apply {
                text = "${review.nickname} · ${review.createdAt.take(10)}"
                textSize = 10f
                setTextColor(resources.getColor(R.color.claude_text_hint, null))
                typeface = resources.getFont(R.font.claude_serif)
                setPadding(0, 4, 0, 0)
            })
        }

        override fun getItemCount() = data.size

        inner class VH(itemView: View, val content: LinearLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }
}
