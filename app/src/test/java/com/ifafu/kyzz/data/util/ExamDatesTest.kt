package com.ifafu.kyzz.data.util

import com.ifafu.kyzz.data.model.Exam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 考试日期/冲突判定的边界测试。countdownState 显式传入 today，不依赖真实时钟。
 */
class ExamDatesTest {

    private val today = LocalDate.of(2026, 6, 30)

    @Test
    fun nearestDayIgnoresPastSessionsWhenAnotherSessionIsUpcoming() {
        assertEquals(2, ExamDates.daysUntilNearest("2026-06-29 09:00-11:00~2026-07-02 14:00-16:00", today))
        assertEquals(-1, ExamDates.daysUntilNearest("2026-06-28~2026-06-29 09:00-11:00", today))
    }

    @Test
    fun timeKeysSortMorningBeforeAfternoonAndRejectInvalidTimes() {
        assertTrue(ExamDates.timeKey("9:00-11:00") < ExamDates.timeKey("14:00-16:00"))
        assertEquals("09:00-11:00", ExamDates.timeKey("9：00～11：00"))
        assertTrue(ExamDates.allPeriods("25:00-26:00").isEmpty())
        assertTrue(ExamDates.allPeriods("09:60-11:00").isEmpty())
    }

    @Test
    fun futureExamAndEmptyTablesAreNotExpired() {
        assertFalse(ExamDates.areAllExamsExpired(listOf(exam("2026-07-03")), today))
        assertFalse(ExamDates.areAllExamsExpired(listOf(exam("2026-06-29")), today))
        assertTrue(ExamDates.areAllExamsExpired(listOf(exam("2026-06-27")), today))
        assertFalse(ExamDates.areAllExamsExpired(emptyList(), today))
        assertFalse(ExamDates.areAllExamsExpired(listOf(exam("待定")), today))
    }

    // ==================== countdownState ====================

    @Test
    fun `未来考试返回 Upcoming`() {
        val state = ExamDates.countdownState("2026-07-03(周五)09:00-11:00", today)
        assertEquals(ExamDates.CountdownState.Upcoming(3), state)
    }

    @Test
    fun `当天有场次返回 Today`() {
        val state = ExamDates.countdownState("2026-06-30(周二)14:00-16:00", today)
        assertEquals(ExamDates.CountdownState.Today, state)
    }

    @Test
    fun `全部场次已过返回 Finished`() {
        val state = ExamDates.countdownState("2026-06-28(周日)09:00-11:00", today)
        assertEquals(ExamDates.CountdownState.Finished(2), state)
    }

    @Test
    fun `跨天考试第二天不是 Finished 而是 Today`() {
        // 回归：旧逻辑只看首日，6-30 打开 6-29~6-30 的考试会被标"已结束 1 天"
        val state = ExamDates.countdownState("2026-06-29~2026-06-30(周一)09:00-11:00", today)
        assertEquals(ExamDates.CountdownState.Today, state)
    }

    @Test
    fun `跨天考试全部结束后才 Finished 且按末日计天数`() {
        val state = ExamDates.countdownState("2026-06-29~2026-06-30(周一)09:00-11:00", LocalDate.of(2026, 7, 2))
        assertEquals(ExamDates.CountdownState.Finished(2), state)
    }

    @Test
    fun `中间空档日返回 InProgress`() {
        // 6-1 与 6-5 两场，今天 6-3：已开始但今天无场次
        val state = ExamDates.countdownState("2026-06-01 09:00-11:00~2026-06-05 14:00-16:00", today = LocalDate.of(2026, 6, 3))
        assertEquals(ExamDates.CountdownState.InProgress(2), state)
    }

    @Test
    fun `无法解析返回 Unknown`() {
        assertEquals(ExamDates.CountdownState.Unknown, ExamDates.countdownState("时间待定", today))
    }

    // ==================== allPeriods ====================

    @Test
    fun `多时段全量提取`() {
        val periods = ExamDates.allPeriods("2026-06-30(周二)09:00-11:00~13:00-15:00")
        assertEquals(listOf(540 to 660, 780 to 900), periods)
    }

    // ==================== conflictsBetween ====================

    private fun exam(dt: String) = Exam(name = "X", datetime = dt)

    @Test
    fun `同日同时段冲突`() {
        assertTrue(ExamDates.conflictsBetween(
            "2026-06-30(周二)09:00-11:00",
            "2026/6/30 09:00-11:00"
        ))
    }

    @Test
    fun `同日时段交叠冲突`() {
        // 旧版按字符串分组检测不到 "8:30-10:30" 与 "10:00-12:00" 的交叠
        assertTrue(ExamDates.conflictsBetween(
            "2026-06-30 8:30-10:30",
            "2026-06-30 10:00-12:00"
        ))
    }

    @Test
    fun `首尾相接不算冲突`() {
        assertFalse(ExamDates.conflictsBetween(
            "2026-06-30 09:00-11:00",
            "2026-06-30 11:00-13:00"
        ))
    }

    @Test
    fun `跨天考试第二天撞上另一门`() {
        // A 是 6-29~6-30 跨天考试，B 在 6-30 与其第二场重叠
        assertTrue(ExamDates.conflictsBetween(
            "2026-06-29~2026-06-30(周一)09:00-11:00",
            "2026-06-30(周二)10:00-12:00"
        ))
    }

    @Test
    fun timesRemainAttachedToTheirOwnDayInMultiSessionExams() {
        val multi = "2026-06-29 09:00-11:00~2026-06-30 14:00-16:00"
        assertFalse(ExamDates.conflictsBetween(multi, "2026-06-30 09:00-11:00"))
        assertTrue(ExamDates.conflictsBetween(multi, "2026-06-30 15:00-17:00"))
    }

    @Test
    fun `不同日期不冲突`() {
        assertFalse(ExamDates.conflictsBetween(
            "2026-06-30 09:00-11:00",
            "2026-07-01 09:00-11:00"
        ))
    }

    @Test
    fun `双方都无时段信息时同日保守提示冲突`() {
        assertTrue(ExamDates.conflictsBetween("2026-06-30", "2026年6月30日"))
        assertFalse(ExamDates.conflictsBetween("2026-06-30", "2026-07-01"))
    }

    @Test
    fun `一方无时段信息不判冲突`() {
        assertFalse(ExamDates.conflictsBetween("2026-06-30", "2026-06-30 09:00-11:00"))
    }

    // ==================== findConflictGroups ====================

    @Test
    fun `传递性冲突合并为一组`() {
        // A 撞 B（同时段），B 撞 C（同日交叠），A 与 C 无直接重叠
        val a = exam("2026-06-30 09:00-10:00")
        val b = exam("2026-06-30 09:30-11:00")
        val c = exam("2026-06-30 10:30-12:00")
        val other = exam("2026-07-05 09:00-11:00")
        val groups = ExamDates.findConflictGroups(listOf(a, b, c, other))
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].size)
    }

    @Test
    fun `无冲突返回空`() {
        val groups = ExamDates.findConflictGroups(listOf(
            exam("2026-06-30 09:00-11:00"),
            exam("2026-07-01 09:00-11:00")
        ))
        assertTrue(groups.isEmpty())
    }
}
