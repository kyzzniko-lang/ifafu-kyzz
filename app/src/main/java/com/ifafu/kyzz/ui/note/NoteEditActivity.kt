package com.ifafu.kyzz.ui.note

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ifafu.kyzz.R
import com.ifafu.kyzz.data.api.PetChatApi
import com.ifafu.kyzz.data.model.Note
import com.ifafu.kyzz.data.repository.NoteRepository
import com.ifafu.kyzz.databinding.ActivityNoteEditBinding
import com.ifafu.kyzz.ui.base.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NoteEditActivity : BaseActivity<ActivityNoteEditBinding>() {

    override fun createBinding() = ActivityNoteEditBinding.inflate(layoutInflater)

    private val repo by lazy { NoteRepository(this) }
    private var editingNoteId: String? = null
    private var currentAudioPath = ""
    // 编辑进入时笔记引用的原音频：重录写新临时文件，保存成功才删它，
    // 放弃编辑则原样保留（旧实现 MediaRecorder 直接覆盖原文件，无法恢复）。
    private var originalAudioPath = ""
    // 新建笔记时固定复用同一个草稿 id：每次点录音换一个新 UUID 会让"录一段→重录"
    // 各写一个文件，旧文件从此无引用也无人清理，几年累积下来泄漏数十 MB。
    private var draftAudioId: String? = null
    // 本次会话新录、且尚未随笔记保存的音频；放弃编辑退出时删除，避免孤儿文件。
    private var unsavedNewAudioPath: String? = null
    // "删除音频"暂不落盘，保存时才真正删（放弃编辑可恢复）。
    private var pendingDeleteAudioPath: String? = null
    private var recorder: MediaRecorder? = null
    private var isRecording = false
    private var player: MediaPlayer? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { finish() }

        editingNoteId = intent.getStringExtra("note_id")

        // 编辑模式显示删除菜单
        if (editingNoteId != null) {
            binding.toolbar.inflateMenu(R.menu.menu_note_edit)
            binding.toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_delete_note) {
                    deleteCurrentNote()
                    true
                } else false
            }
        }

        // 加载已有笔记
        if (editingNoteId != null) {
            val note = repo.loadNotes().find { it.id == editingNoteId }
            if (note != null) {
                binding.etTitle.setText(note.title)
                binding.etContent.setText(note.content)
                binding.etCategory.setText(note.category)
                binding.switchFavorite.isChecked = note.isFavorite
                binding.switchPetVisible.isChecked = note.isPetVisible
                currentAudioPath = note.audioPath
                originalAudioPath = note.audioPath
                updateAudioUI()
            }
        } else {
            // 新建时读取全局宠物可见设置
            val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
            binding.switchPetVisible.isChecked = prefs.getBoolean("pet_see_notes", false)
        }

        // 录音
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else checkAndStartRecording()
        }
        binding.btnPlay.setOnClickListener { playAudio() }
        binding.btnDeleteAudio.setOnClickListener { deleteAudio() }

        // AI归类
        binding.btnAiCategory.setOnClickListener { aiCategorize() }

        // 保存
        binding.btnSave.setOnClickListener { saveNote() }
    }

    private fun checkAndStartRecording() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val audioDir = File(filesDir, "notes_audio")
        audioDir.mkdirs()
        // 编辑模式写入新临时文件而不是笔记当前引用的文件：MediaRecorder 直接
        // 覆盖原录音，放弃编辑后原音频就永久丢失（新建模式复用草稿 id 是为了
        // 防孤儿文件，编辑模式必须换 id 才能保住原文件）。
        val recordId = if (editingNoteId != null) {
            "${editingNoteId}_rec_${System.currentTimeMillis()}"
        } else {
            draftAudioId ?: UUID.randomUUID().toString().also { draftAudioId = it }
        }
        // 重录时清掉上一段未保存的临时录音（编辑模式每段都是新文件名），
        // 避免孤儿文件累积
        unsavedNewAudioPath?.takeIf { it != File(audioDir, "$recordId.3gp").absolutePath }?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
        currentAudioPath = File(audioDir, "$recordId.3gp").absolutePath

        recorder = MediaRecorder()
        try {
            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(currentAudioPath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 麦克风被占用或存储不可用时 prepare()/start() 会抛异常
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            currentAudioPath = ""
            android.widget.Toast.makeText(this, "录音启动失败：麦克风可能被占用", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 编辑与新建模式的会话录音都需要"未保存退出即清理"
        // （编辑模式录的是临时文件，清理不会碰笔记原音频）
        unsavedNewAudioPath = currentAudioPath
        isRecording = true
        binding.btnRecord.text = "停止"
        binding.tvRecordStatus.text = "录音中..."
        binding.btnPlay.visibility = android.view.View.GONE
        binding.btnDeleteAudio.visibility = android.view.View.GONE
    }

    private fun stopRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        finally {
            try {
                recorder?.release()
            } catch (_: Exception) {}
        }
        recorder = null
        isRecording = false
        binding.btnRecord.text = "重录"
        updateAudioUI()
    }

    private fun updateAudioUI() {
        if (currentAudioPath.isNotEmpty() && File(currentAudioPath).exists()) {
            binding.tvRecordStatus.text = "已有录音"
            binding.btnPlay.visibility = android.view.View.VISIBLE
            binding.btnDeleteAudio.visibility = android.view.View.VISIBLE
        } else {
            binding.tvRecordStatus.text = "点击按钮开始录音"
            binding.btnPlay.visibility = android.view.View.GONE
            binding.btnDeleteAudio.visibility = android.view.View.GONE
        }
    }

    private fun playAudio() {
        if (currentAudioPath.isEmpty() || !File(currentAudioPath).exists()) return
        player?.release()
        player = null
        // 先局部构造再赋值：apply 块内 setDataSource/prepare 抛异常时（半截录音
        // 文件/占用），新实例没赋给 player 也没 release，codec 资源泄漏到 GC
        val mp = MediaPlayer()
        try {
            mp.setDataSource(currentAudioPath)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { binding.btnPlay.text = "播放" }
            player = mp
            binding.btnPlay.text = "播放中..."
        } catch (_: Exception) {
            try { mp.release() } catch (_: Exception) {}
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteAudio() {
        // 不立即删磁盘文件：误点删除后放弃编辑时还要能恢复（否则笔记引用的音频
        // 已被物理删除，列表 🎤 标记还在、播放却永远无声）。标记待删，保存时
        // 才真正删除；重新录音会覆盖同一路径，保存时按路径比对跳过误删。
        if (currentAudioPath.isNotEmpty()) {
            pendingDeleteAudioPath = currentAudioPath
        }
        currentAudioPath = ""
        updateAudioUI()
    }

    private fun saveNote() {
        val content = binding.etContent.text?.toString()?.trim() ?: ""
        if (content.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        val title = binding.etTitle.text?.toString()?.trim()?.ifEmpty {
            content.take(20).replace('\n', ' ')
        } ?: content.take(20)
        val category = binding.etCategory.text?.toString()?.trim() ?: ""
        val now = System.currentTimeMillis()
        val id = editingNoteId ?: UUID.randomUUID().toString()

        val note = Note(
            id = id,
            title = title,
            content = content,
            audioPath = currentAudioPath,
            category = category,
            isFavorite = binding.switchFavorite.isChecked,
            isPetVisible = binding.switchPetVisible.isChecked,
            createdAt = if (editingNoteId != null) {
                repo.loadNotes().find { it.id == editingNoteId }?.createdAt ?: now
            } else now,
            updatedAt = now
        )

        repo.upsertNote(note)
        // 音频已随笔记持久化，不再是"未保存的会话录音"
        unsavedNewAudioPath = null
        // 之前"删除音频"标记的文件此刻才真正删除；若用户删后又重录（新录音
        // 随本次保存），路径与 note.audioPath 相同的情况不存在（重录总是新文件），
        // 保留路径比对防御旧数据
        pendingDeleteAudioPath?.takeIf { it != note.audioPath }?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
        pendingDeleteAudioPath = null
        // 编辑模式：新录音替换了笔记原音频，原文件此刻才删除（放弃编辑时
        // 不会走到这里，原文件得以保留）
        if (editingNoteId != null && originalAudioPath.isNotEmpty() && originalAudioPath != note.audioPath) {
            try { File(originalAudioPath).delete() } catch (_: Exception) {}
        }

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun deleteCurrentNote() {
        if (editingNoteId == null) return
        val title = binding.etTitle.text?.toString()?.trim()?.ifEmpty { "无标题" } ?: "无标题"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("删除笔记")
            .setMessage("删除「$title」？")
            .setPositiveButton("删除") { _, _ ->
                repo.deleteNote(editingNoteId!!)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun aiCategorize() {
        val content = binding.etContent.text?.toString()?.trim() ?: ""
        if (content.isEmpty()) {
            Toast.makeText(this, "请先输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnAiCategory.isEnabled = false
        binding.btnAiCategory.text = "分析中..."

        lifecycleScope.launch {
            try {
                val account = getSharedPreferences("ifafu_user", MODE_PRIVATE)
                    .getString("account", "") ?: ""
                val chatApi = PetChatApi(
                    PetChatApi.deviceIdOf(account, this@NoteEditActivity.applicationContext),
                    this@NoteEditActivity.applicationContext
                )
                val dummyPet = com.ifafu.kyzz.data.model.Pet()
                val title = binding.etTitle.text?.toString()?.trim() ?: ""
                val noteText = if (title.isNotEmpty()) "$title\n$content" else content
                val userMsg = "请帮我给以下笔记内容分类，只返回1-2个分类标签名，用逗号分隔，不要其他内容：\n${noteText.take(300)}"
                val result = withContext(Dispatchers.IO) {
                    // strict 模式：限流/失败返回空串。此前失败文案（如"明天再来
                    // 聊吧~"）不以喵/呜开头，会被当成分类结果写进输入框并保存
                    chatApi.chat(userMsg, dummyPet, emptyList(), strict = true)
                }
                if (isFinishing) return@launch
                if (result.isNotEmpty()) {
                    // 清理结果，只保留标签部分
                    val cleaned = result.replace(Regex("[「」\"\\[\\]]"), "")
                        .split("\n").firstOrNull()?.trim() ?: result.trim()
                    binding.etCategory.setText(cleaned)
                    Toast.makeText(this@NoteEditActivity, "已建议：$cleaned", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@NoteEditActivity, "AI归类失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (!isFinishing) {
                    Toast.makeText(this@NoteEditActivity, "AI归类失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (!isFinishing) {
                    binding.btnAiCategory.isEnabled = true
                    binding.btnAiCategory.text = "AI归类"
                }
            }
        }
    }

    override fun onDestroy() {
        // Remove listeners before releasing to prevent callbacks on destroyed binding
        player?.setOnCompletionListener(null)
        player?.release()
        player = null
        if (isRecording) {
            try { recorder?.stop() } catch (_: Exception) {}
        }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        // 新建笔记录了音但没保存就退出：删掉这段无主录音，避免 notes_audio 目录
        // 随时间累积孤儿文件（重录已通过复用 draftAudioId 覆盖同一文件）。
        unsavedNewAudioPath?.let { path ->
            try { File(path).delete() } catch (_: Exception) {}
        }
        unsavedNewAudioPath = null
        super.onDestroy()
    }
}
