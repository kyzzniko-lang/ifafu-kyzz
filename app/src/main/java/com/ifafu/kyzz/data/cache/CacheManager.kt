package com.ifafu.kyzz.data.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    companion object {
        private const val TAG = "CacheManager"
    }

    fun saveSyllabus(account: String, syllabus: Syllabus) {
        prefs.edit()
            .putString("syllabus_$account", gson.toJson(syllabus))
            .putLong("syllabus_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadSyllabus(account: String): Syllabus? {
        val json = prefs.getString("syllabus_$account", null) ?: return null
        return try {
            gson.fromJson(json, Syllabus::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize Syllabus for $account", e)
            null
        }
    }

    fun loadSyllabusTimestamp(account: String): Long =
        prefs.getLong("syllabus_${account}_ts", 0L)

    fun saveSyllabus(account: String, syllabus: Syllabus, yearTermKey: String) {
        val key = if (yearTermKey.isEmpty()) "syllabus_$account" else "syllabus_${account}_$yearTermKey"
        prefs.edit()
            .putString(key, gson.toJson(syllabus))
            .putLong("${key}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadSyllabus(account: String, yearTermKey: String): Syllabus? {
        val key = if (yearTermKey.isEmpty()) "syllabus_$account" else "syllabus_${account}_$yearTermKey"
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(json, Syllabus::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize Syllabus for $account key=$yearTermKey", e)
            null
        }
    }

    fun saveScores(account: String, scores: List<Score>) {
        prefs.edit()
            .putString("scores_$account", gson.toJson(scores))
            .putLong("scores_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    fun saveScores(account: String, scores: List<Score>, yearTermKey: String) {
        val key = if (yearTermKey.isEmpty()) "scores_$account" else "scores_${account}_$yearTermKey"
        prefs.edit()
            .putString(key, gson.toJson(scores))
            .putLong("${key}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadScores(account: String): List<Score>? {
        val json = prefs.getString("scores_$account", null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<List<Score>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize Scores for $account", e)
            null
        }
    }

    fun loadScores(account: String, yearTermKey: String): List<Score>? {
        val key = if (yearTermKey.isEmpty()) "scores_$account" else "scores_${account}_$yearTermKey"
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<List<Score>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize Scores for $account key=$yearTermKey", e)
            null
        }
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
        return try {
            gson.fromJson(json, ExamTable::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize ExamTable for $account", e)
            null
        }
    }

    fun loadExamTableTimestamp(account: String): Long =
        prefs.getLong("exams_${account}_ts", 0L)

    fun clearCache(account: String) {
        prefs.edit().apply {
            remove("syllabus_$account"); remove("syllabus_${account}_ts")
            for ((key, _) in prefs.all) {
                if (key.startsWith("syllabus_${account}_") && key != "syllabus_${account}_ts") {
                    remove(key)
                }
            }
            remove("scores_$account"); remove("scores_${account}_ts")
            for ((key, _) in prefs.all) {
                if (key.startsWith("scores_${account}_") && key != "scores_${account}_ts") {
                    remove(key)
                }
            }
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
        return try {
            gson.fromJson(json, StudentInfo::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize StudentInfo for $account", e)
            null
        }
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
        return try {
            gson.fromJson(json, TrainingPlan::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize TrainingPlan for $account", e)
            null
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize GradeExams for $account", e)
            null
        }
    }

    fun loadGradeExamsTimestamp(account: String): Long =
        prefs.getLong("grade_exams_${account}_ts", 0L)

    fun isCacheStale(account: String, key: String, maxAgeMs: Long = 24 * 60 * 60 * 1000L): Boolean {
        val ts = prefs.getLong("${key}_${account}_ts", 0L)
        return ts == 0L || System.currentTimeMillis() - ts > maxAgeMs
    }

    fun saveWeather(campus: String, json: String) {
        prefs.edit()
            .putString("weather_${campus}", json)
            .putLong("weather_${campus}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadWeather(campus: String): String? {
        val json = prefs.getString("weather_$campus", null) ?: return null
        val ts = prefs.getLong("weather_${campus}_ts", 0L)
        if (System.currentTimeMillis() - ts >= 30 * 60 * 1000L) return null
        val today = java.time.LocalDate.now().toString()
        val cachedDate = try {
            com.google.gson.JsonParser.parseString(json).asJsonObject?.get("date")?.asString
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse weather cached date for $campus", e)
            null
        }
        if (cachedDate != today) return null
        return json
    }

    /** 返回过期缓存，供网络失败时降级使用 */
    fun loadWeatherFallback(campus: String): String? {
        return prefs.getString("weather_$campus", null)
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

    fun saveChatHistory(account: String, messages: List<com.ifafu.kyzz.data.api.PetChatApi.ChatMessage>) {
        prefs.edit()
            .putString("chat_history_$account", gson.toJson(messages.map { mapOf("content" to it.content, "isUser" to it.isUser) }))
            .apply()
    }

    fun loadChatHistory(account: String): List<com.ifafu.kyzz.data.api.PetChatApi.ChatMessage> {
        val json = prefs.getString("chat_history_$account", null) ?: return emptyList()
        return try {
            val list = gson.fromJson(json, object : TypeToken<List<Map<String, Any>>>() {}.type) as List<Map<String, Any>>
            list.map { com.ifafu.kyzz.data.api.PetChatApi.ChatMessage(it["content"] as? String ?: "", it["isUser"] as? Boolean ?: false) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize ChatHistory for $account", e)
            emptyList()
        }
    }

    fun saveExamProgress(account: String, progress: List<com.ifafu.kyzz.data.model.ExamProgress>) {
        prefs.edit().putString("exam_progress_$account", gson.toJson(progress)).apply()
    }

    fun loadExamProgress(account: String): List<com.ifafu.kyzz.data.model.ExamProgress> {
        val json = prefs.getString("exam_progress_$account", null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<com.ifafu.kyzz.data.model.ExamProgress>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize ExamProgress for $account", e)
            emptyList()
        }
    }
}
