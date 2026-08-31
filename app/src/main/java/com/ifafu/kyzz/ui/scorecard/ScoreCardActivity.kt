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
import com.ifafu.kyzz.data.api.ScoreApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.repository.UserRepository
import com.ifafu.kyzz.data.util.GpaCalculator
import com.ifafu.kyzz.databinding.ActivityScoreCardBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class ScoreCardActivity : BaseActivity<ActivityScoreCardBinding>() {

    override fun createBinding() = ActivityScoreCardBinding.inflate(layoutInflater)

    @Inject lateinit var scoreApi: ScoreApi
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var cacheManager: CacheManager

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

        binding.btnSave.setOnClickListener { saveImage() }
        binding.btnShare.setOnClickListener { shareImage() }

        if (scores.isEmpty()) {
            // 首次安装登录后缓存尚未落库是常态:旧逻辑读死一次缓存,用户在后台
            // 首次拉取完成前进入只会看到空数据,退出重进才恢复。这里展示加载态
            // 并主动走与成绩页相同的拉取链路,完成后自动渲染。
            binding.tvTotalCredits.text = "…"
            binding.tvAvgScore.text = "…"
            binding.tvAvgGpa.text = "…"
            binding.btnSave.isEnabled = false
            binding.btnShare.isEnabled = false
            fetchScoresThenRender()
            return
        }

        setupSummary()
        setupRecyclerView()
    }

    private fun setupSummary() {
        // 总学分与 GPA/平均分同口径（重修去重、剔除免修/缺考）：直接对原始列表求和，
        // 挂科+重修两行会把学分双计，与同卡片上已归一的 GPA 数字互相矛盾。
        val totalCredits = GpaCalculator.computeTotalCredits(scores)
        // 与成绩页/首页统一口径：GPA 用 GpaCalculator（学分加权、剔除 0 绩点课），
        // 平均分用学分加权平均；此前直接对原始 scorePoint 求算术平均，
        // 同一学期的绩点会和成绩页显示的不一致。
        val avgScore = GpaCalculator.computeWeightedAvg(scores)
        val avgGpa = GpaCalculator.computeGpa(scores)

        binding.tvTotalCredits.text = "%.1f".format(totalCredits)
        binding.tvAvgScore.text = "%.1f".format(avgScore)
        binding.tvAvgGpa.text = "%.2f".format(avgGpa)
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = ScoreAdapter(scores)
    }

    private companion object {
        /** 单次主动拉取上限,与成绩页刷新同级:弱网重登链路最长约 90s。 */
        const val SCORE_FETCH_TIMEOUT_MS = 120_000L
    }

    /**
     * 缓存为空时的补拉:复用成绩页同一条链路(getAllScores 含重登自愈),
     * 结果经 commitFreshScores 原子落盘后重读缓存渲染。数据到达即填充,
     * 失败回落到 "--" 并提示,不阻塞用户退出。
     */
    private fun fetchScoresThenRender() {
        lifecycleScope.launch {
            // 拉取前快照账号：链路含重登自愈最长可达 120s，期间用户登出/切号的话，
            // 拉回的 A 账号成绩不能写进（甚至覆盖）B 账号的缓存键。与
            // ScoreCheckWorker 的账号守卫同口径。
            val accountAtStart = userRepository.getUser().account
            val fetched = try {
                withTimeoutOrNull(SCORE_FETCH_TIMEOUT_MS) {
                    val user = userRepository.getUser()
                    scoreApi.getAllScores(
                        userRepository.host, user.token, user.account, user.name
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (isFinishing || isDestroyed) return@launch

            val account = userRepository.getUser().account
            if (account != accountAtStart) {
                android.util.Log.i("ScoreCardActivity", "账号在拉取期间变更，丢弃本次结果")
                return@launch
            }
            // 空列表不落盘(与成绩页/后台检查同口径),避免把异常页解析的空集
            // 写成"真实无成绩"基线;提交后重读,拿到合并与 firstSeen 归一的数据
            if (!fetched.isNullOrEmpty()) {
                cacheManager.commitFreshScores(account, fetched)
            }
            val fresh = cacheManager.loadScores(account).orEmpty()
            if (fresh.isNotEmpty()) {
                scores = fresh
                setupSummary()
                setupRecyclerView()
                binding.btnSave.isEnabled = true
                binding.btnShare.isEnabled = true
            } else {
                binding.tvTotalCredits.text = "--"
                binding.tvAvgScore.text = "--"
                binding.tvAvgGpa.text = "--"
                Toast.makeText(this@ScoreCardActivity, "成绩加载失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
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
            // 免修/缺考等课程 score 为负数哨兵，展示为 -- 而不是 -1.0/-2.0
            canvas.drawText(if (score.score > 0) "%.1f".format(score.score) else "--", 520f, y, rowPaint)
            canvas.drawText(if (score.scorePoint > 0) "%.1f".format(score.scorePoint) else "--", 620f, y, rowPaint)
        }

        // Footer
        val footerY = headerRowY + 40 + scores.size * rowHeight + 20f
        canvas.drawLine(padding.toFloat(), footerY, (width - padding).toFloat(), footerY, linePaint)

        val avgScore = GpaCalculator.computeWeightedAvg(scores)
        val avgGpa = GpaCalculator.computeGpa(scores)
        val totalCredits = GpaCalculator.computeTotalCredits(scores)

        val footerPaint = Paint().apply { color = hintColor; textSize = 14f; isAntiAlias = true }
        canvas.drawText("共${scores.size}门课  总学分${"%.1f".format(totalCredits)}  平均分${"%.1f".format(avgScore)}  绩点${"%.2f".format(avgGpa)}", padding.toFloat(), footerY + 30f, footerPaint)

        val watermarkPaint = Paint().apply { color = Color.parseColor("#30C75B39"); textSize = 12f; isAntiAlias = true }
        canvas.drawText("由 iFAFU KYZZ 生成", padding.toFloat(), footerY + 55f, watermarkPaint)

        return bitmap
    }

    private fun saveImage() {
        // 大四成绩单 50+ 门课：Bitmap 约 800x4300（ARGB_8888 ~14MB）+ PNG 压缩 +
        // 写盘，主线程做会冻结 UI 数秒甚至 ANR，全部挪到 IO 线程。
        setActionsEnabled(false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bitmap = generateBitmap()
                    val filename = "成绩单_${System.currentTimeMillis()}.png"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/iFAFU")
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri == null) Result.failure(IllegalStateException("无法写入相册"))
                        else {
                            // openOutputStream 可能为 null（存储满/权限异常）：
                            // 静默跳过压缩却报"已保存成功"会在相册留下 0 字节文件。
                            val stream = contentResolver.openOutputStream(uri)
                            if (stream == null) {
                                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                                bitmap.recycle()
                                Result.failure(IllegalStateException("无法打开相册写入流"))
                            } else {
                                stream.use {
                                    val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    if (!ok) throw IllegalStateException("图片压缩写入失败")
                                }
                                bitmap.recycle()
                                Result.success("已保存到相册")
                            }
                        }
                    } else {
                        // Android 9 及以下没有 WRITE_EXTERNAL_STORAGE 权限（manifest
                        // 未声明），公共 Pictures 目录必然 Permission denied。改写
                        // 应用专属外部目录：无需任何权限，路径在提示里告知用户。
                        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                        val file = File(dir, "iFAFU/$filename")
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        Result.success("已保存到 ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            setActionsEnabled(true)
            result.fold(
                onSuccess = { Toast.makeText(this@ScoreCardActivity, it, Toast.LENGTH_SHORT).show() },
                onFailure = { Toast.makeText(this@ScoreCardActivity, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show() }
            )
        }
    }

    private fun shareImage() {
        setActionsEnabled(false)
        lifecycleScope.launch {
            val file: File? = withContext(Dispatchers.IO) {
                try {
                    val bitmap = generateBitmap()
                    val f = File(cacheDir, "score_card_share.png")
                    FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    f
                } catch (_: Exception) {
                    null
                }
            }
            setActionsEnabled(true)
            if (file == null) {
                Toast.makeText(this@ScoreCardActivity, "分享失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@ScoreCardActivity, "${packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享成绩单"))
            } catch (e: Exception) {
                Toast.makeText(this@ScoreCardActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.btnSave.isEnabled = enabled
        binding.btnShare.isEnabled = enabled
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
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })

            val metaItems = listOf(
                "%.1f".format(score.studyScore),
                if (score.score > 0) "%.0f".format(score.score) else "--",
                if (score.scorePoint > 0) "%.1f".format(score.scorePoint) else "--"
            )
            metaItems.forEach { text ->
                holder.row.addView(TextView(this@ScoreCardActivity).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(resources.getColor(R.color.claude_text_secondary, null))
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.claude_serif)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        override fun getItemCount() = data.size

        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)
    }
}
