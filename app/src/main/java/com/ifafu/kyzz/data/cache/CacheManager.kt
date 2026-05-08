package com.ifafu.kyzz.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ifafu.kyzz.data.model.ExamTable
import com.ifafu.kyzz.data.model.GradeExam
import com.ifafu.kyzz.data.model.Score
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
        prefs.edit()
            .putString("syllabus_$account", gson.toJson(syllabus))
            .putLong("syllabus_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadSyllabus(account: String): Syllabus? {
        val json = prefs.getString("syllabus_$account", null) ?: return null
        return try { gson.fromJson(json, Syllabus::class.java) } catch (_: Exception) { null }
    }

    fun loadSyllabusTimestamp(account: String): Long =
        prefs.getLong("syllabus_${account}_ts", 0L)

    fun saveScores(account: String, scores: List<Score>) {
        prefs.edit()
            .putString("scores_$account", gson.toJson(scores))
            .putLong("scores_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadScores(account: String): List<Score>? {
        val json = prefs.getString("scores_$account", null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<List<Score>>() {}.type)
        } catch (_: Exception) { null }
    }

    fun loadScoresTimestamp(account: String): Long =
        prefs.getLong("scores_${account}_ts", 0L)

    fun saveExamTable(account: String, examTable: ExamTable) {
        prefs.edit()
            .putString("exams_$account", gson.toJson(examTable))
            .putLong("exams_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadExamTable(account: String): ExamTable? {
        val json = prefs.getString("exams_$account", null) ?: return null
        return try { gson.fromJson(json, ExamTable::class.java) } catch (_: Exception) { null }
    }

    fun loadExamTableTimestamp(account: String): Long =
        prefs.getLong("exams_${account}_ts", 0L)

    fun clearCache(account: String) {
        prefs.edit().apply {
            remove("syllabus_$account"); remove("syllabus_${account}_ts")
            remove("scores_$account"); remove("scores_${account}_ts")
            remove("exams_$account"); remove("exams_${account}_ts")
            remove("student_info_$account"); remove("student_info_${account}_ts")
            remove("training_plan_$account"); remove("training_plan_${account}_ts")
            remove("grade_exams_$account"); remove("grade_exams_${account}_ts")
            apply()
        }
    }

    fun saveStudentInfo(account: String, info: StudentInfo) {
        prefs.edit()
            .putString("student_info_$account", gson.toJson(info))
            .putLong("student_info_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadStudentInfo(account: String): StudentInfo? {
        val json = prefs.getString("student_info_$account", null) ?: return null
        return try { gson.fromJson(json, StudentInfo::class.java) } catch (_: Exception) { null }
    }

    fun loadStudentInfoTimestamp(account: String): Long =
        prefs.getLong("student_info_${account}_ts", 0L)

    fun saveTrainingPlan(account: String, plan: TrainingPlan) {
        prefs.edit()
            .putString("training_plan_$account", gson.toJson(plan))
            .putLong("training_plan_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadTrainingPlan(account: String): TrainingPlan? {
        val json = prefs.getString("training_plan_$account", null) ?: return null
        return try { gson.fromJson(json, TrainingPlan::class.java) } catch (_: Exception) { null }
    }

    fun loadTrainingPlanTimestamp(account: String): Long =
        prefs.getLong("training_plan_${account}_ts", 0L)

    fun saveGradeExams(account: String, exams: List<GradeExam>) {
        prefs.edit()
            .putString("grade_exams_$account", gson.toJson(exams))
            .putLong("grade_exams_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadGradeExams(account: String): List<GradeExam>? {
        val json = prefs.getString("grade_exams_$account", null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<List<GradeExam>>() {}.type)
        } catch (_: Exception) { null }
    }

    fun loadGradeExamsTimestamp(account: String): Long =
        prefs.getLong("grade_exams_${account}_ts", 0L)

    fun isCacheStale(account: String, key: String, maxAgeMs: Long = 24 * 60 * 60 * 1000L): Boolean {
        val ts = prefs.getLong("${key}_${account}_ts", 0L)
        return ts == 0L || System.currentTimeMillis() - ts > maxAgeMs
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun getCacheSize(): Long {
        var size = 0L
        for ((_, value) in prefs.all) {
            size += when (value) {
                is String -> value.toByteArray().size.toLong()
                is Long -> 8L
                is Int -> 4L
                is Boolean -> 1L
                else -> 0L
            }
        }
        return size
    }
}
