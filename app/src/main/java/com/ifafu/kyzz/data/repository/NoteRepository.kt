package com.ifafu.kyzz.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.model.Note

class NoteRepository(context: Context) {

    private val prefs = context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadNotes(): List<Note> {
        val json = prefs.getString("notes", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<Note>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }

    fun saveNotes(notes: List<Note>) {
        prefs.edit().putString("notes", gson.toJson(notes)).apply()
    }

    fun getPetVisibleNotes(): List<Note> {
        return loadNotes().filter { it.isPetVisible }
    }

    fun getCategories(): List<String> {
        return loadNotes().map { it.category }.filter { it.isNotEmpty() }.distinct()
    }

    fun deleteNote(id: String) {
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
