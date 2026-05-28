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
                updateAudioUI()
            }
        } else {
            // 新建时读取全局宠物可见设置
            val prefs = getSharedPreferences("ifafu_user", MODE_PRIVATE)
            binding.switchPetVisible.isChecked = prefs.getBoolean("pet_see_notes", true)
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
        val id = editingNoteId ?: UUID.randomUUID().toString()
        val audioDir = File(filesDir, "notes_audio")
        audioDir.mkdirs()
        currentAudioPath = File(audioDir, "$id.3gp").absolutePath

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(currentAudioPath)
            prepare()
            start()
        }
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
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(currentAudioPath)
                prepare()
                start()
            }
            player?.setOnCompletionListener {
                binding.btnPlay.text = "播放"
            }
            binding.btnPlay.text = "播放中..."
        } catch (_: Exception) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteAudio() {
        if (currentAudioPath.isNotEmpty()) {
            try { File(currentAudioPath).delete() } catch (_: Exception) {}
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

        val notes = repo.loadNotes().toMutableList()
        val existingIndex = notes.indexOfFirst { it.id == id }
        if (existingIndex >= 0) notes[existingIndex] = note else notes.add(note)
        repo.saveNotes(notes)

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
                val chatApi = PetChatApi()
                val dummyPet = com.ifafu.kyzz.data.model.Pet()
                val title = binding.etTitle.text?.toString()?.trim() ?: ""
                val noteText = if (title.isNotEmpty()) "$title\n$content" else content
                val userMsg = "请帮我给以下笔记内容分类，只返回1-2个分类标签名，用逗号分隔，不要其他内容：\n${noteText.take(300)}"
                val result = withContext(Dispatchers.IO) {
                    chatApi.chat(userMsg, dummyPet, emptyList())
                }
                if (result.isNotEmpty() && !result.startsWith("喵") && !result.startsWith("呜")) {
                    // 清理结果，只保留标签部分
                    val cleaned = result.replace(Regex("[「」\"\\[\\]]"), "")
                        .split("\n").firstOrNull()?.trim() ?: result.trim()
                    binding.etCategory.setText(cleaned)
                    Toast.makeText(this@NoteEditActivity, "已建议：$cleaned", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@NoteEditActivity, "AI归类失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NoteEditActivity, "AI归类失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnAiCategory.isEnabled = true
                binding.btnAiCategory.text = "AI归类"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            try { recorder?.stop() } catch (_: Exception) {}
        }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
