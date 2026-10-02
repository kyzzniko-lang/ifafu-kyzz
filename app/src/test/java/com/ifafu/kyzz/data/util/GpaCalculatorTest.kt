package com.ifafu.kyzz.data.util

import com.ifafu.kyzz.data.model.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖四年使用中必然出现的成绩形态：重修叠加、补考通过、免修/缺考哨兵、
 * 纯 0 分记录，确保 GPA / 加权平均分 / 总学分口径一致。
 */
class GpaCalculatorTest {

    private fun score(
        code: String,
        name: String,
        year: String,
        term: String,
        studyScore: Float,
        score: Float,
        makeupScore: Float = 0f,
        scorePoint: Float = 0f
    ) = Score(
        year = year, term = term, courseCode = code, courseName = name,
        studyScore = studyScore, score = score, makeupScore = makeupScore,
        scorePoint = scorePoint
    )

    @Test
    fun retakeKeepsLatestRecordOnlyForCredits() {
        // 大二挂科(4学分) + 大四重修通过(4学分)：总学分不应双计为 8
        val scores = listOf(
            score("MATH101", "高等数学", "2023-2024", "1", 4f, 45f),
            score("MATH101", "高等数学", "2026-2027", "1", 4f, 85f, scorePoint = 3.7f)
        )
        assertEquals(4f, GpaCalculator.computeTotalCredits(scores), 0.001f)
    }

    @Test
    fun makeupPassCountsMakeupScore() {
        // 挂科 45 分但补考 75 分：统计按 75 分参与，不再停留在挂科状态
        val scores = listOf(
            score("ENG101", "大学英语", "2024-2025", "2", 3f, 45f, makeupScore = 75f, scorePoint = 3.0f)
        )
        val gpa = GpaCalculator.computeGpa(scores)
        assertEquals(3.0f, gpa, 0.01f)
    }

    @Test
    fun makeupPassWithoutPointCountsInGpa() {
        // 补考通过后教务绩点列为空（scorePoint==0）：旧实现只替换分数、绩点仍为 0，
        // 该课被 computeGpa 整体剔除——平均分包含补考分而 GPA 不包含，口径分裂。
        // 现在按补考分/25 补线性绩点：75/25 = 3.0
        val scores = listOf(
            score("ENG101", "大学英语", "2024-2025", "2", 3f, 45f, makeupScore = 75f)
        )
        assertEquals(3.0f, GpaCalculator.computeGpa(scores), 0.01f)
    }

    @Test
    fun zeroScoreFailWithMakeupRetained() {
        // 真实 0 分挂科（非 -1/-2 哨兵）且补考通过：不能在 valid() 就被丢弃
        val scores = listOf(
            score("PHY101", "大学物理", "2023-2024", "1", 4f, 0f, makeupScore = 80f)
        )
        assertEquals(4f, GpaCalculator.computeTotalCredits(scores), 0.001f)
        assertEquals(3.2f, GpaCalculator.computeGpa(scores), 0.01f) // 80/25
    }

    @Test
    fun levelGradedCoursesCountInMixedGpa() {
        // 同学期混合百分制 + 等级制（优=95/良=85，绩点列为空）：
        // 旧实现只统计有绩点的课，等级制课从 GPA 消失而平均分包含它们。
        val scores = listOf(
            score("MATH", "高数", "2025-2026", "1", 4f, 90f, scorePoint = 4.0f),
            score("PE", "体育", "2025-2026", "1", 1f, 85f)  // 良，绩点空
        )
        // (4.0*4 + 3.4*1) / 5 = 3.88
        assertEquals(3.88f, GpaCalculator.computeGpa(scores), 0.01f)
    }

    @Test
    fun failedCourseWithoutMakeupExcludedFromGpa() {
        // 挂科且尚未补考：不参与 GPA 分子分母（与纯百分制兜底分支的 passed 过滤一致）
        val scores = listOf(
            score("MATH", "高数", "2025-2026", "1", 4f, 90f, scorePoint = 4.0f),
            score("C", "C语言", "2025-2026", "1", 3f, 40f, scorePoint = 0f)
        )
        assertEquals(4.0f, GpaCalculator.computeGpa(scores), 0.01f)
    }

    @Test
    fun exemptAndAbsentSentinelsExcluded() {
        val scores = listOf(
            score("PE101", "体育", "2024-2025", "1", 1f, -1f),   // 免修
            score("HIS101", "历史", "2024-2025", "1", 2f, -2f),  // 缺考
            score("ART101", "艺术", "2024-2025", "1", 2f, 90f, scorePoint = 4.0f)
        )
        assertEquals(2f, GpaCalculator.computeTotalCredits(scores), 0.001f)
        assertEquals(4.0f, GpaCalculator.computeGpa(scores), 0.01f)
    }

    @Test
    fun weightedAvgMatchesCreditWeights() {
        val scores = listOf(
            score("A", "课程A", "2025-2026", "1", 4f, 90f, scorePoint = 4.0f),
            score("B", "课程B", "2025-2026", "1", 1f, 60f, scorePoint = 1.0f)
        )
        // (90*4 + 60*1) / 5 = 84
        assertEquals(84f, GpaCalculator.computeWeightedAvg(scores), 0.01f)
        // (4.0*4 + 1.0*1) / 5 = 3.4
        assertEquals(3.4f, GpaCalculator.computeGpa(scores), 0.01f)
    }

    @Test
    fun emptyListYieldsZero() {
        assertEquals(0f, GpaCalculator.computeTotalCredits(emptyList()), 0.001f)
        assertEquals(0f, GpaCalculator.computeGpa(emptyList()), 0.001f)
    }

    @Test
    fun validKeepsZeroScoreWithMakeupOnly() {
        // 调用方预过滤必须复用 valid()：自建 `score > 0` 会把"真实 0 分挂科+
        // 补考及格"删掉，导致与直接交原始列表的统计互相矛盾。
        val zeroMakeup = score("PHY", "大学物理", "2024-2025", "1", 4f, 0f, makeupScore = 80f)
        val notPublished = score("CS", "算法", "2024-2025", "1", 3f, 0f)   // 未出分
        val exempt = score("PE", "体育", "2024-2025", "1", 1f, -1f)        // 免修哨兵
        val absent = score("HIS", "历史", "2024-2025", "1", 2f, -2f)       // 缺考哨兵
        val zeroCredit = score("ART", "艺术", "2024-2025", "1", 0f, 90f)   // 0 学分
        val normal = score("MATH", "高数", "2024-2025", "1", 5f, 88f, scorePoint = 3.8f)

        val valid = GpaCalculator.valid(listOf(zeroMakeup, notPublished, exempt, absent, zeroCredit, normal))
        assertEquals(listOf("大学物理", "高数"), valid.map { it.courseName })
    }

    @Test
    fun isPassedCountsMakeupPassButNotFailOrSentinel() {
        // 直接及格
        assertTrue(GpaCalculator.isPassed(score("A", "课A", "2024-2025", "1", 2f, 85f)))
        // 挂科后补考及格（选修学分同样已获得）
        assertTrue(GpaCalculator.isPassed(score("B", "课B", "2024-2025", "1", 2f, 45f, makeupScore = 70f)))
        // 真实 0 分挂科 + 补考及格
        assertTrue(GpaCalculator.isPassed(score("C", "课C", "2024-2025", "1", 2f, 0f, makeupScore = 60f)))
        // 挂科未补考 / 补考仍不及格
        assertFalse(GpaCalculator.isPassed(score("D", "课D", "2024-2025", "1", 2f, 45f)))
        assertFalse(GpaCalculator.isPassed(score("E", "课E", "2024-2025", "1", 2f, 50f, makeupScore = 55f)))
        // 免修/缺考哨兵不算已获得成绩
        assertFalse(GpaCalculator.isPassed(score("F", "课F", "2024-2025", "1", 2f, -1f)))
        assertFalse(GpaCalculator.isPassed(score("G", "课G", "2024-2025", "1", 2f, -2f)))
        // 未出分
        assertFalse(GpaCalculator.isPassed(score("H", "课H", "2024-2025", "1", 2f, 0f)))
    }
}
