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
    private val cacheLock = Any()

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
        synchronized(cacheLock) {
            val keysToRemove = mutableListOf<String>()
            for ((key, _) in prefs.all) {
                when {
                    key == "syllabus_$account" || key == "syllabus_${account}_ts" -> keysToRemove.add(key)
                    key.startsWith("syllabus_${account}_") -> keysToRemove.add(key)
                    // 成绩缓存主数据键 + 按学期分键 + 时间戳，全部清理
                    // （历史 bug：仅清理 _ts 键，导致 scores_$account 主键残留旧学期脏数据）
                    key == "scores_$account" -> keysToRemove.add(key)
                    key == "scores_${account}_ts" -> keysToRemove.add(key)
                    key.startsWith("scores_${account}_") -> keysToRemove.add(key)
                    key == "exams_$account" || key == "exams_${account}_ts" -> keysToRemove.add(key)
                    key == "student_info_$account" || key == "student_info_${account}_ts" -> keysToRemove.add(key)
                    key == "training_plan_$account" || key == "training_plan_${account}_ts" -> keysToRemove.add(key)
                    key == "grade_exams_$account" || key == "grade_exams_${account}_ts" -> keysToRemove.add(key)
                    key == "exam_progress_$account" -> keysToRemove.add(key)
                    // 成绩首次见到时间记录（用于成绩页按出分先后排序）
                    key == "score_first_seen_$account" -> keysToRemove.add(key)
                }
            }
            prefs.edit().apply {
                for (key in keysToRemove) {
                    remove(key)
                }
                apply()
            }
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

    // ==================== 成绩首次见到时间（出分先后排序用） ====================

    /**
     * 成绩唯一标识：courseCode + year + term（与 ScoreCheckReceiver 去重 key 同源）。
     * 教务系统不提供成绩录入时间，这里记录 App 本地首次见到该成绩的时间戳，
     * 用于成绩页按"出分先后"排序与标记最新成绩。
     */
    private fun scoreKey(s: com.ifafu.kyzz.data.model.Score): String =
        "${s.courseCode}|${s.year}|${s.term}"

    /**
     * 合并新拉取的成绩到本地首次见到时间表，并为每条成绩赋值 firstSeenTs。
     *
     * NEW 标记规则：只有"用户当前在读学期"的成绩才会被记为当前时间（进入 48h NEW 窗口）；
     * 其它所有学期的成绩一律记为 0（窗口外，永不显示 NEW）。
     *
     * 当前在读学期由 [com.ifafu.kyzz.data.util.TermResolver.inferCurrentTerm] 按手机当前
     * 日期推断（如 2026 年 2-7 月 → 2025-2026 学年第二学期），并与爬取到的成绩数据复核：
     * 若推断学期在成绩里已有数据，则用它；若推断学期还一门分都没出（如刚开学），则退回
     * 数据里最大的学期作为"最近有分学期"，避免新学期还没出分时把上学期标 NEW。
     */
    fun mergeAndAssignFirstSeen(account: String, scores: List<com.ifafu.kyzz.data.model.Score>) {
        if (scores.isEmpty()) return
        val existing = loadScoreFirstSeenInternal(account).toMutableMap()
        val now = System.currentTimeMillis()

        // 1. 用手机日期推断当前在读学期
        val inferred = com.ifafu.kyzz.data.util.TermResolver.inferCurrentTerm()
        // 2. 复核：推断学期是否在爬取数据里有成绩
        val hasInferredScores = scores.any { it.year == inferred.year && it.term == inferred.term }
        // 3. 确定"当前学期"：有分用推断值；没分退回数据里最大的学期（最近有分学期）
        val (curYear, curTerm) = if (hasInferredScores) {
            inferred.year to inferred.term
        } else {
            val latestYear = scores.map { it.year }.maxOrNull() ?: return
            val latestTerm = scores.filter { it.year == latestYear }
                .map { it.term }.maxOrNull() ?: return
            latestYear to latestTerm
        }

        for (s in scores) {
            val key = scoreKey(s)
            val isCurrentTerm = s.year == curYear && s.term == curTerm
            if (key in existing) {
                val old = existing.getValue(key)
                // 自愈：非当前学期的成绩不应有窗口内时间戳（旧版本误标残留），纠正为 0
                s.firstSeenTs = if (!isCurrentTerm && old > 0L) {
                    existing[key] = 0L; 0L
                } else {
                    old
                }
            } else {
                // 仅当前学期的成绩记 now（进入 NEW 窗口），其它学期一律记 0（永不 NEW）
                val ts = if (isCurrentTerm) now else 0L
                existing[key] = ts
                s.firstSeenTs = ts
            }
        }
        saveScoreFirstSeenInternal(account, existing)
    }

    /** 仅从缓存加载时间戳并赋值（不写入），用于离线/缓存展示时恢复排序。 */
    fun assignFirstSeenFromCache(account: String, scores: List<com.ifafu.kyzz.data.model.Score>) {
        if (scores.isEmpty()) return
        val map = loadScoreFirstSeenInternal(account)
        for (s in scores) {
            s.firstSeenTs = map[scoreKey(s)] ?: 0L
        }
    }

    private fun loadScoreFirstSeenInternal(account: String): Map<String, Long> {
        val json = prefs.getString("score_first_seen_$account", null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<MutableMap<String, Long>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize score_first_seen for $account", e)
            emptyMap()
        }
    }

    private fun saveScoreFirstSeenInternal(account: String, map: Map<String, Long>) {
        prefs.edit().putString("score_first_seen_$account", gson.toJson(map)).apply()
    }
}
