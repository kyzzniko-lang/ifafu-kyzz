package com.ifafu.kyzz.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ifafu.kyzz.data.model.Note

class NoteRepository(context: Context) {

    private val prefs = context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        // 笔记以"全量 JSON 覆盖写"保存,且列表页/编辑页/首页各自 new 一个实例。
        // 锁必须挂在类上:实例锁拦不住多个实例并发的读-改-写,后写者会把
        // 先写者的整笔修改覆盖掉(丢笔记)。
        private val fileLock = Any()
    }

    fun loadNotes(): List<Note> = synchronized(fileLock) {
        val json = prefs.getString("notes", null) ?: return emptyList()
        return try {
            val array = JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                ?: return emptyList()
            array.mapNotNull { item ->
                try {
                    if (!item.isJsonObject) return@mapNotNull null
                    val note = gson.fromJson(item, Note::class.java)
                    if (note.id.isNullOrBlank() || note.content.isNullOrBlank()) return@mapNotNull null
                    note.copy(title = note.title.orEmpty(), audioPath = note.audioPath.orEmpty(),
                        category = note.category.orEmpty())
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveNotes(notes: List<Note>) {
        synchronized(fileLock) {
            prefs.edit().putString("notes", gson.toJson(notes)).apply()
        }
    }

    fun getPetVisibleNotes(): List<Note> {
        return loadNotes().filter { it.isPetVisible }
    }

    fun getCategories(): List<String> {
        return loadNotes().map { it.category }.filter { it.isNotEmpty() }.distinct()
    }

    /** 原子新增或更新一条笔记：读-改-写整体持锁，调用方不得再自行 load→save 拼装。 */
    fun upsertNote(note: Note) {
        synchronized(fileLock) {
            val notes = loadNotes().toMutableList()
            val existingIndex = notes.indexOfFirst { it.id == note.id }
            if (existingIndex >= 0) notes[existingIndex] = note else notes.add(note)
            saveNotes(notes)
        }
    }

    fun deleteNote(id: String) {
        synchronized(fileLock) {
            val notes = loadNotes().toMutableList()
            val note = notes.find { it.id == id }
            notes.removeAll { it.id == id }
            saveNotes(notes)
            // 删除录音文件
            if (note != null && note.audioPath.isNotEmpty()) {
                try { java.io.File(note.audioPath).delete() } catch (_: Exception) {}
            }
        }
    }
}
