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
import kotlin.math.max

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

        /**
         * 成绩合并/对比键。课程代码为空的记录(正方个别跨校借读条目)在
         * 同学年同学期会互相碰撞,直接用 Triple(courseCode,year,term) 去重
         * 会把另一门课永久吞掉;掺入课名与学分区分。
         * 命名区别于本类私有的 firstSeen 字符串键 scoreKey()。
         */
        fun scoreMergeKey(s: Score): Triple<String, String, String> =
            if (s.courseCode.isBlank()) {
                Triple("${s.courseName}#${s.studyScore}", s.year, s.term)
            } else {
                Triple(s.courseCode, s.year, s.term)
            }

        private const val MAX_CHAT_HISTORY = 50
        private const val MAX_DISCUSSION_CACHE = 100
    }

    fun saveDiscussionComments(comments: List<com.ifafu.kyzz.data.model.Comment>) {
        val trimmed = comments.take(MAX_DISCUSSION_CACHE)
        prefs.edit()
            .putString("discussion_comments", gson.toJson(trimmed))
            .putLong("discussion_comments_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadDiscussionComments(): List<com.ifafu.kyzz.data.model.Comment>? {
        val json = prefs.getString("discussion_comments", null) ?: return null
        return try {
            gson.fromJson(json, object : TypeToken<List<com.ifafu.kyzz.data.model.Comment>>() {}.type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize discussion comments", e)
            null
        }
    }

    fun loadDiscussionCommentsTimestamp(): Long =
        prefs.getLong("discussion_comments_ts", 0L)

    fun isDiscussionCommentsStale(maxAgeMs: Long): Boolean {
        val timestamp = loadDiscussionCommentsTimestamp()
        return timestamp == 0L || System.currentTimeMillis() - timestamp > maxAgeMs
    }

    fun saveSyllabus(account: String, syllabus: Syllabus) {
        prefs.edit()
            .putString("syllabus_$account", gson.toJson(syllabus))
            .putLong("syllabus_${account}_ts", System.currentTimeMillis())
            .apply()
    }

    /**
     * 清理旧版本曾把不同学期写进同一主缓存的课表数据。
     * 只执行一次，避免升级后继续展示错位学期内容。
     *
     * schema 5：2.6.0 的周次解析回归曾把"周一第1,2节{第1-16周}"的周次误存成
     * "第2周"（正则组号写错），单节次课整门被丢。升级后清空课表缓存，
     * 强制按修复后的解析器重新拉取。
     */
    fun migrateSyllabusCacheIfNeeded(account: String) {
        val schemaKey = "syllabus_${account}_cache_schema"
        if (prefs.getInt(schemaKey, 0) >= 5) return
        prefs.edit().apply {
            for ((key, _) in prefs.all) {
                if (key == "syllabus_$account" || key.startsWith("syllabus_${account}_")) {
                    remove(key)
                }
            }
            putInt(schemaKey, 5)
            apply()
        }
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

    fun loadSyllabusTimestamp(account: String, yearTermKey: String): Long {
        val key = if (yearTermKey.isEmpty()) "syllabus_$account" else "syllabus_${account}_$yearTermKey"
        return prefs.getLong("${key}_ts", 0L)
    }

    fun markSyllabusUpcomingProbe(account: String) {
        prefs.edit()
            .putLong("syllabus_${account}_upcoming_probe_ts", System.currentTimeMillis())
            .apply()
    }

    fun isSyllabusUpcomingProbeStale(account: String, maxAgeMs: Long): Boolean {
        val timestamp = prefs.getLong("syllabus_${account}_upcoming_probe_ts", 0L)
        return timestamp == 0L || System.currentTimeMillis() - timestamp > maxAgeMs
    }

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

    /**
     * 选课/退课成功后把课表缓存标记为过期：只清时间戳不清内容——首页/课表页
     * 下次进入会重新拉取，而 Widget（只读缓存不下网络）仍能显示旧课表而不是
     * "暂无课表数据"，拉取成功后自动换新。
     */
    fun invalidateSyllabus(account: String) {
        prefs.edit().putLong("syllabus_${account}_ts", 0L).apply()
    }

    /**
     * 拉取结果比缓存"缩水"时做合并而非覆盖。教务查询 POST 超时会降级返回默认
     * 视图(通常只有本学期成绩)；大四学生缓存里有四年 40+ 门，若被 8 门的子集
     * 覆盖，次日全量拉回时其余课程会全部被误判为"新出分"弹出巨型通知。
     * 按 (课程代码, 学年, 学期) 合并：fresh 值优先，缓存独有条目补回。
     */
    fun mergeScoresIfShrunk(account: String, fresh: List<Score>): List<Score> {
        val cached = loadScores(account) ?: return fresh
        if (fresh.size >= cached.size) return fresh
        val freshKeys = fresh.map { scoreMergeKey(it) }.toSet()
        val merged = fresh + cached.filter { scoreMergeKey(it) !in freshKeys }
        Log.w(TAG, "成绩拉取(${fresh.size}门)少于缓存(${cached.size}门)，按学期合并保留 ${merged.size} 门")
        return merged
    }

    /**
     * "合并缩水 + 落盘" 原子化。cacheLock 此前只有 clearCache 在用，前台
     * （成绩页/选课页 viewModelScope）与后台（ScoreCheckWorker）的
     * load→merge→save 读改写序列完全无同步：12:00 定时检查与用户正在下拉刷新
     * 重叠时，旧快照回写会丢 firstSeen 时间戳（NEW 角标错乱）甚至回滚缓存。
     * synchronized 可重入，内部调用无死锁面。
     */
    fun mergeScoresIfShrunkAndSave(account: String, fresh: List<Score>): List<Score> =
        synchronized(cacheLock) {
            val merged = mergeScoresIfShrunk(account, fresh)
            saveScores(account, merged)
            merged
        }

    /**
     * 前台刷新的完整提交：缩水合并 + 落盘 + firstSeen 归并，三步一步完成，
     * 与后台 Worker / 登出清缓存互斥。
     */
    fun commitFreshScores(account: String, fresh: List<Score>): List<Score> =
        synchronized(cacheLock) {
            val merged = mergeScoresIfShrunk(account, fresh)
            saveScores(account, merged)
            mergeAndAssignFirstSeen(account, merged)
            merged
        }

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

    /**
     * 仅清某账号的宠物聊天记录。不并入 clearCache()：后者在学期切换时也会调用，
     * 聊天记录不随学期失效，只在登出/注销时需要清。
     */
    fun clearChatHistory(account: String) {
        prefs.edit().remove("chat_history_$account").apply()
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

    /**
     * 给页面统一展示缓存来源与更新时间，避免用户把缓存误认为刚刚从教务系统获取的数据。
     */
    fun cacheStatus(timestamp: Long, offline: Boolean = false): String {
        val prefix = if (offline) "网络不可用 · 显示缓存" else "显示缓存"
        if (timestamp <= 0L) return "$prefix · 更新时间未知"
        val elapsed = max(0L, System.currentTimeMillis() - timestamp)
        val age = when {
            elapsed < 60_000L -> "刚刚更新"
            elapsed < 60 * 60_000L -> "${elapsed / 60_000L}分钟前更新"
            elapsed < 24 * 60 * 60_000L -> "${elapsed / (60 * 60_000L)}小时前更新"
            else -> java.text.SimpleDateFormat(
                "M月d日 HH:mm更新",
                java.util.Locale.getDefault()
            ).format(java.util.Date(timestamp))
        }
        return "$prefix · $age"
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
        // 与 WeatherApi 一致按北京时间判"今天"，否则设备在其它时区时缓存会被
        // 提前判过期(或跨日窗口错误命中)
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()
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
        // 限制最近 50 条，避免长对话把超大 JSON 塞进 SharedPreferences（全量读改写易 ANR）
        val trimmed = if (messages.size > MAX_CHAT_HISTORY) messages.takeLast(MAX_CHAT_HISTORY) else messages
        prefs.edit()
            .putString("chat_history_$account", gson.toJson(trimmed.map { mapOf("content" to it.content, "isUser" to it.isUser) }))
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
        synchronized(cacheLock) {
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
    }

    /** 仅从缓存加载时间戳并赋值（不写入），用于离线/缓存展示时恢复排序。 */
    fun assignFirstSeenFromCache(account: String, scores: List<com.ifafu.kyzz.data.model.Score>) {
        if (scores.isEmpty()) return
        val map = loadScoreFirstSeenInternal(account)
        for (s in scores) {
            s.firstSeenTs = map[scoreKey(s)] ?: 0L
        }
    }

    /**
     * 是否已建立过"成绩首次见到"基线表。该表会被 clearCache() 清掉，
     * 可作为比 score_monitor_initialized_* 更可靠的"是否已初始化"信号——
     * 后者存放在 ifafu_user prefs，clearCache 清不到，学期切换清缓存后仍为 true，
     * 会让 ScoreCheckReceiver 把所有历史成绩误报为"新出分"。
     */
    fun hasScoreFirstSeenBaseline(account: String): Boolean =
        loadScoreFirstSeenInternal(account).isNotEmpty()

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
