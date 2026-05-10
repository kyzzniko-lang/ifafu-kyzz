package com.ifafu.kyzz.ui.scorecard

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.databinding.ActivityScoreCardBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import java.io.File
import java.io.FileOutputStream

class ScoreCardActivity : BaseActivity<ActivityScoreCardBinding>() {

    override fun createBinding() = ActivityScoreCardBinding.inflate(layoutInflater)

    private var scores: List<Score> = emptyList()
    private var userName = ""
    private var institute = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val userPrefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
        userName = userPrefs.getString("name", "") ?: ""
        institute = userPrefs.getString("institute", "") ?: ""
        val account = userPrefs.getString("account", "") ?: ""

        val cachePrefs = getSharedPreferences("ifafu_cache", MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val json = cachePrefs.getString("scores_$account", null)
        scores = if (json != null) {
            try {
                gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<Score>>() {}.type)
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        if (scores.isEmpty()) {
            binding.tvTotalCredits.text = "--"
            binding.tvAvgScore.text = "--"
            binding.tvAvgGpa.text = "--"
            binding.btnSave.isEnabled = false
            binding.btnShare.isEnabled = false
            return
        }

        setupSummary()
        setupRecyclerView()

        binding.btnSave.setOnClickListener { saveImage() }
        binding.btnShare.setOnClickListener { shareImage() }
    }

    private fun setupSummary() {
        val totalCredits = scores.sumOf { it.studyScore.toDouble() }.toFloat()
        val avgScore = scores.map { it.score.toDouble() }.average().toFloat()
        val avgGpa = scores.map { it.scorePoint.toDouble() }.average().toFloat()

        binding.tvTotalCredits.text = "%.1f".format(totalCredits)
        binding.tvAvgScore.text = "%.1f".format(avgScore)
        binding.tvAvgGpa.text = "%.2f".format(avgGpa)
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ScoreAdapter(scores)
    }

    private fun generateBitmap(): Bitmap {
        val dp = resources.displayMetrics.density
        val width = 800
        val rowHeight = 40
        val headerHeight = 120
        val footerHeight = 80
        val padding = 40
        val height = headerHeight + scores.size * rowHeight + footerHeight + padding * 2

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgColor = Color.parseColor("#FAF9F7")
        val terracotta = Color.parseColor("#C75B39")
        val textColor = Color.parseColor("#2D2A26")
        val hintColor = Color.parseColor("#9C958E")

        canvas.drawColor(bgColor)

        // Header
        val headerPaint = Paint().apply { color = terracotta; isAntiAlias = true }
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight.toFloat(), headerPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE; textSize = 36f; isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("iFAFU 成绩单", padding.toFloat(), 50f, titlePaint)

        val subtitlePaint = Paint().apply { color = Color.parseColor("#CCFFFFFF"); textSize = 20f; isAntiAlias = true }
        canvas.drawText("$userName  $institute", padding.toFloat(), 85f, subtitlePaint)

        // Column headers
        val headerRowY = headerHeight + padding
        val colPaint = Paint().apply { color = terracotta; textSize = 18f; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("课程", padding.toFloat(), headerRowY.toFloat(), colPaint)
        canvas.drawText("学分", 420f, headerRowY.toFloat(), colPaint)
        canvas.drawText("分数", 520f, headerRowY.toFloat(), colPaint)
        canvas.drawText("绩点", 620f, headerRowY.toFloat(), colPaint)

        // Divider
        val linePaint = Paint().apply { color = Color.parseColor("#E8E5E0"); strokeWidth = 1f }
        canvas.drawLine(padding.toFloat(), headerRowY + 10f, (width - padding).toFloat(), headerRowY + 10f, linePaint)

        // Rows
        val rowPaint = Paint().apply { color = textColor; textSize = 16f; isAntiAlias = true }
        scores.forEachIndexed { i, score ->
            val y = (headerRowY + 40 + i * rowHeight).toFloat()
            val name = score.courseName.take(12)
            canvas.drawText(name, padding.toFloat(), y, rowPaint)
            canvas.drawText("%.1f".format(score.studyScore), 420f, y, rowPaint)
            canvas.drawText("%.1f".format(score.score), 520f, y, rowPaint)
            canvas.drawText("%.1f".format(score.scorePoint), 620f, y, rowPaint)
        }

        // Footer
        val footerY = headerRowY + 40 + scores.size * rowHeight + 20f
        canvas.drawLine(padding.toFloat(), footerY, (width - padding).toFloat(), footerY, linePaint)

        val avgScore = scores.map { it.score.toDouble() }.average()
        val avgGpa = scores.map { it.scorePoint.toDouble() }.average()
        val totalCredits = scores.sumOf { it.studyScore.toDouble() }

        val footerPaint = Paint().apply { color = hintColor; textSize = 14f; isAntiAlias = true }
        canvas.drawText("共${scores.size}门课  总学分${"%.1f".format(totalCredits)}  平均分${"%.1f".format(avgScore)}  绩点${"%.2f".format(avgGpa)}", padding.toFloat(), footerY + 30f, footerPaint)

        val watermarkPaint = Paint().apply { color = Color.parseColor("#30C75B39"); textSize = 12f; isAntiAlias = true }
        canvas.drawText("由 iFAFU KYZZ 生成", padding.toFloat(), footerY + 55f, watermarkPaint)

        return bitmap
    }

    private fun saveImage() {
        try {
            val bitmap = generateBitmap()
            val filename = "成绩单_${System.currentTimeMillis()}.png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/iFAFU")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                val file = File(dir, "iFAFU/$filename")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(this, "已保存到 ${file.absolutePath}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareImage() {
        try {
            val bitmap = generateBitmap()
            val file = File(cacheDir, "score_card_share.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享成绩单"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class ScoreAdapter(private val data: List<Score>) : RecyclerView.Adapter<ScoreAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(20, 12, 20, 12)
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            }
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val score = data[position]
            holder.row.removeAllViews()

            holder.row.addView(TextView(this@ScoreCardActivity).apply {
                text = score.courseName
                setTextAppearance(R.style.ClaudeBody)
                setTextColor(resources.getColor(R.color.claude_text_primary, null))
                typeface = resources.getFont(R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })

            val metaItems = listOf(
                "%.1f".format(score.studyScore),
                "%.0f".format(score.score),
                "%.1f".format(score.scorePoint)
            )
            metaItems.forEach { text ->
                holder.row.addView(TextView(this@ScoreCardActivity).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                    typeface = resources.getFont(R.font.claude_serif)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        override fun getItemCount() = data.size

        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)
    }
}
