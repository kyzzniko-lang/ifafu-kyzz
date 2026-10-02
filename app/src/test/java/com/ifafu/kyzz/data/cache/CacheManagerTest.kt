package com.ifafu.kyzz.data.cache

import android.app.Application
import com.ifafu.kyzz.data.model.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 缓存屧行为测试：读写往返、缩水合并防覆盖、按账号隔离清理。
 * 这些逻辑直接决定"用户会不会丢成绩数据"，值得单测兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheManagerTest {

    private lateinit var cache: CacheManager

    @Before
    fun setup() {
        cache = CacheManager(RuntimeEnvironment.getApplication())
        cache.clearAll()
    }

    private fun score(code: String, name: String, year: String = "1999", term: String = "1") =
        Score(year = year, term = term, courseCode = code, courseName = name, score = 88f)

    @Test
    fun `成绩读写往返`() {
        cache.saveScores("30001", listOf(score("CS01", "高等数学"), score("CS02", "线性代数")))
        val loaded = cache.loadScores("30001")
        assertNotNull(loaded)
        assertEquals(listOf("高等数学", "线性代数"), loaded!!.map { it.courseName })
        assertTrue(cache.loadScoresTimestamp("30001") > 0)
    }

    @Test
    fun `按学期分键写入不污染主缓存`() {
        cache.saveScores("30001", listOf(score("CS01", "主缓存成绩")))
        cache.saveScores("30001", listOf(score("CS02", "学期成绩")), "1999-2")
        assertEquals(listOf("主缓存成绩"), cache.loadScores("30001")!!.map { it.courseName })
        assertEquals(listOf("学期成绩"), cache.loadScores("30001", "1999-2")!!.map { it.courseName })
    }

    @Test
    fun `缩水拉取合并保留缓存独有条目`() {
        cache.saveScores("30001", listOf(score("CS01", "高等数学"), score("CS02", "线性代数"), score("CS03", "大学物理")))
        // 模拟教务超时降级：只返回 1 门子集，fresh 分数已更新为 92
        val fresh = listOf(score("CS01", "高等数学").copy(score = 92f))
        val merged = cache.mergeScoresIfShrunk("30001", fresh)
        assertEquals(3, merged.size)
        assertEquals(92f, merged.first { it.courseCode == "CS01" }.score, 0.01f)
        assertTrue(merged.any { it.courseCode == "CS03" })
    }

    @Test
    fun `非缩水拉取直接采用 fresh`() {
        cache.saveScores("30001", listOf(score("CS01", "高等数学")))
        val fresh = listOf(score("CS01", "高等数学"), score("CS02", "新出分课程"))
        val merged = cache.mergeScoresIfShrunk("30001", fresh)
        assertEquals(fresh, merged)
    }

    @Test
    fun `无课程代码成绩按课名加学分去重`() {
        // 跨校借读条目 courseCode 为空，Triple 去重需掺入课名防止互相吞并
        val a = Score(courseName = "跨校课程甲", studyScore = 2f, year = "1999", term = "1")
        val b = Score(courseName = "跨校课程乙", studyScore = 2f, year = "1999", term = "1")
        val keyA = CacheManager.scoreMergeKey(a)
        val keyB = CacheManager.scoreMergeKey(b)
        assertTrue(keyA != keyB)
    }

    @Test
    fun `clearCache 只清当前账号不清他人缓存`() {
        cache.saveScores("30001", listOf(score("CS01", "高等数学")))
        cache.saveScores("30002", listOf(score("CS02", "他人成绩")))
        cache.saveSyllabus("30001", com.ifafu.kyzz.data.model.Syllabus())
        cache.saveChatHistory("30001", listOf(com.ifafu.kyzz.data.api.PetChatApi.ChatMessage("hi", true)))

        cache.clearCache("30001")

        assertNull(cache.loadScores("30001"))
        assertNull(cache.loadSyllabus("30001"))
        // 聊天记录按设计不随 clearCache 清除（仅登出时由 clearChatHistory 清），
        // 防止学期切换清缓存误删用户与宠物的对话历史
        assertEquals(1, cache.loadChatHistory("30001").size)
        // 他账号数据完好
        assertEquals(listOf("他人成绩"), cache.loadScores("30002")!!.map { it.courseName })
    }

    @Test
    fun `聊天历史超限裁剪到最近 50 条`() {
        val messages = (1..60).map { com.ifafu.kyzz.data.api.PetChatApi.ChatMessage("msg$it", it % 2 == 0) }
        cache.saveChatHistory("30001", messages)
        val loaded = cache.loadChatHistory("30001")
        assertEquals(50, loaded.size)
        assertEquals("msg11", loaded.first().content)
        assertEquals("msg60", loaded.last().content)
    }

    @Test
    fun `损坏的缓存 JSON 返回 null 而非崩溃`() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("ifafu_cache", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("scores_30001", "{broken json").apply()
        assertNull(cache.loadScores("30001"))
    }

    private fun currentScore(code: String, value: Float = 88f): Score {
        val term = com.ifafu.kyzz.data.util.TermResolver.inferCurrentTerm()
        return score(code, code, term.year, term.term).copy(score = value)
    }

    @Test
    fun `首次已出分基线后反复刷新不产生 NEW`() {
        cache.mergeAndAssignFirstSeen("30001", listOf(currentScore("CS01"), currentScore("CS02")))
        repeat(3) {
            val refreshed = listOf(currentScore("CS01"), currentScore("CS02"))
            cache.mergeAndAssignFirstSeen("30001", refreshed)
            assertTrue(refreshed.all { it.firstSeenTs == 0L })
        }
    }

    @Test
    fun `未出分占位转有效分数只记录一次首见时间`() {
        cache.mergeAndAssignFirstSeen("30001", listOf(currentScore("CS01", 0f)))
        val published = currentScore("CS01", 92f)
        cache.mergeAndAssignFirstSeen("30001", listOf(published))
        assertTrue(published.firstSeenTs > 0L)
        val refreshed = currentScore("CS01", 92f)
        cache.mergeAndAssignFirstSeen("30001", listOf(refreshed))
        assertEquals(published.firstSeenTs, refreshed.firstSeenTs)
    }

    @Test
    fun `历史学期补录不会因当前学期尚未出分而标 NEW`() {
        cache.mergeAndAssignFirstSeen("30001", listOf(score("OLD1", "旧课程")))
        val historical = score("OLD2", "历史补录")
        cache.mergeAndAssignFirstSeen("30001", listOf(score("OLD1", "旧课程"), historical))
        assertEquals(0L, historical.firstSeenTs)
    }

    @Test
    fun `基线创建后新出现当前学期课程会标 NEW`() {
        cache.mergeAndAssignFirstSeen("30001", listOf(currentScore("CS01")))
        val added = currentScore("CS02")
        cache.mergeAndAssignFirstSeen("30001", listOf(currentScore("CS01"), added))
        assertTrue(added.firstSeenTs > 0L)
    }

    @Test
    fun `未出分缓存展示不会暴露内部哨兵时间`() {
        cache.mergeAndAssignFirstSeen("30001", listOf(currentScore("CS01", 0f)))
        val cached = currentScore("CS01", 0f)
        cache.assignFirstSeenFromCache("30001", listOf(cached))
        assertEquals(0L, cached.firstSeenTs)
    }

    @Test
    fun `晚到的后台成绩不能覆盖同账号前台刷新`() {
        cache.saveScores("30001", listOf(score("CS01", "课程").copy(score = 80f)))
        val beforeRequest = cache.getScoresSnapshot("30001")
        cache.commitFreshScores("30001", listOf(score("CS01", "课程").copy(score = 95f)))
        assertNull(cache.commitFreshScoresIfUnchanged("30001", listOf(score("CS01", "课程").copy(score = 80f)), beforeRequest.revision))
        assertEquals(95f, cache.loadScores("30001")!!.single().score, 0.01f)
    }

    @Test
    fun `清缓存后旧成绩响应不能重建已清的数据`() {
        val beforeRequest = cache.getScoresSnapshot("30001")
        cache.clearCache("30001")
        assertNull(cache.commitFreshScoresIfUnchanged("30001", listOf(score("CS01", "旧响应")), beforeRequest.revision))
        assertNull(cache.loadScores("30001"))
    }

    @Test
    fun `清空所有缓存也会拒绝未完成的空缓存请求`() {
        val beforeRequest = cache.getScoresSnapshot("30001")
        cache.clearAll()
        assertNull(cache.commitFreshScoresIfUnchanged("30001", listOf(score("CS01", "旧响应")), beforeRequest.revision))
    }

    @Test
    fun `未变更缓存允许后台原子提交并返回原基线`() {
        cache.saveScores("30001", listOf(score("CS01", "课程").copy(score = 80f)))
        val beforeRequest = cache.getScoresSnapshot("30001")
        val result = cache.commitFreshScoresIfUnchanged("30001", listOf(score("CS01", "课程").copy(score = 95f)), beforeRequest.revision)
        assertNotNull(result)
        assertEquals(80f, result!!.first!!.single().score, 0.01f)
        assertEquals(95f, result.second.single().score, 0.01f)
        assertTrue(cache.getScoresSnapshot("30001").revision > beforeRequest.revision)
    }

    @Test
    fun `原先空缓存被前台填充后拒绝旧响应`() {
        val beforeRequest = cache.getScoresSnapshot("30001")
        cache.saveScores("30001", listOf(score("CS01", "最新").copy(score = 95f)))
        assertNull(cache.commitFreshScoresIfUnchanged("30001", listOf(score("CS01", "旧响应").copy(score = 80f)), beforeRequest.revision))
        assertEquals(95f, cache.loadScores("30001")!!.single().score, 0.01f)
    }

    @Test
    fun `相同时间戳与清空回写不能伪装成原缓存版本`() {
        cache.saveScores("30001", listOf(score("CS01", "课程").copy(score = 80f)))
        val timestamp = cache.loadScoresTimestamp("30001")
        val beforeRequest = cache.getScoresSnapshot("30001")
        cache.clearCache("30001")
        cache.saveScores("30001", listOf(score("CS01", "课程").copy(score = 95f)))
        // Deliberately reproduce equal timestamps: revisions must not depend on clock precision.
        RuntimeEnvironment.getApplication().getSharedPreferences("ifafu_cache", android.content.Context.MODE_PRIVATE)
            .edit().putLong("scores_30001_ts", timestamp).apply()
        assertNull(cache.commitFreshScoresIfUnchanged("30001", listOf(score("CS01", "旧响应").copy(score = 80f)), beforeRequest.revision))
        assertEquals(95f, cache.loadScores("30001")!!.single().score, 0.01f)
    }
}
