package com.ifafu.kyzz.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.model.ScoreTable
import com.ifafu.kyzz.data.model.Syllabus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ifafu_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSyllabus(account: String, syllabus: Syllabus) {
        val json = gson.toJson(syllabus)
        prefs.edit().putString("syllabus_$account", json).apply()
    }

    fun loadSyllabus(account: String): Syllabus? {
        val json = prefs.getString("syllabus_$account", null) ?: return null
        return try {
            gson.fromJson(json, Syllabus::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun saveScores(account: String, scores: List<Score>) {
        val json = gson.toJson(scores)
        prefs.edit().putString("scores_$account", json).apply()
    }

    fun loadScores(account: String): List<Score>? {
        val json = prefs.getString("scores_$account", null) ?: return null
        return try {
            val type = object : TypeToken<List<Score>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun saveExamTable(account: String, examTable: ExamTable) {
        val json = gson.toJson(examTable)
        prefs.edit().putString("exams_$account", json).apply()
    }

    fun loadExamTable(account: String): ExamTable? {
        val json = prefs.getString("exams_$account", null) ?: return null
        return gson.fromJson(json, ExamTable::class.java)
    }

    fun clearCache(account: String) {
        prefs.edit().apply {
            remove("syllabus_$account")
            remove("scores_$account")
            remove("exams_$account")
            apply()
        }
    }
}
