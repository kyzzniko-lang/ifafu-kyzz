package com.ifafu.kyzz.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.model.Exam
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.model.Score
import com.ifafu.kyzz.data.model.ScoreTable
import com.ifafu.kyzz.data.model.StudentInfo
import com.ifafu.kyzz.data.model.Syllabus
import com.ifafu.kyzz.data.model.TrainingPlan
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
            remove("student_info_$account")
            remove("training_plan_$account")
            remove("grade_exams_$account")
            apply()
        }
    }

    fun saveStudentInfo(account: String, info: StudentInfo) {
        val json = gson.toJson(info)
        prefs.edit().putString("student_info_$account", json).apply()
    }

    fun loadStudentInfo(account: String): StudentInfo? {
        val json = prefs.getString("student_info_$account", null) ?: return null
        return try { gson.fromJson(json, StudentInfo::class.java) } catch (_: Exception) { null }
    }

    fun saveTrainingPlan(account: String, plan: TrainingPlan) {
        val json = gson.toJson(plan)
        prefs.edit().putString("training_plan_$account", json).apply()
    }

    fun loadTrainingPlan(account: String): TrainingPlan? {
        val json = prefs.getString("training_plan_$account", null) ?: return null
        return try { gson.fromJson(json, TrainingPlan::class.java) } catch (_: Exception) { null }
    }

    fun saveGradeExams(account: String, exams: List<GradeExam>) {
        val json = gson.toJson(exams)
        prefs.edit().putString("grade_exams_$account", json).apply()
    }

    fun loadGradeExams(account: String): List<GradeExam>? {
        val json = prefs.getString("grade_exams_$account", null) ?: return null
        return try {
            val type = object : TypeToken<List<GradeExam>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { null }
    }
}
