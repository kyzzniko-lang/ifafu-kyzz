package com.ifafu.kyzz.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 学年边界回归测试：新学年第一学期从"9月1日所在周的周一"开始，
 * 与 termFirstDay/estimatedFirstDay 的归一口径一致。
 * 2026年9月1日是周二 → 该周周一为8月31日（开学首日踩中的实际场景）。
 */
class TermResolverTest {
    private fun date(year: Int, month: Int, day: Int = 1): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(year, month - 1, day)
        }

    @Test
    fun academicYearTransitionsCoverAllCalendarBoundaries() {
        assertEquals(TermResolver.Term("2025-2026", "1"), TermResolver.inferCurrentTerm(date(2026, 1, 31)))
        assertEquals(TermResolver.Term("2025-2026", "2"), TermResolver.inferCurrentTerm(date(2026, 2, 1)))
        assertEquals(TermResolver.Term("2025-2026", "2"), TermResolver.inferCurrentTerm(date(2026, 7, 20)))
        // 2026-09-01 是周二，所在周周一为 8-31：开学首日即翻入新学年
        assertEquals(TermResolver.Term("2025-2026", "2"), TermResolver.inferCurrentTerm(date(2026, 8, 30)))
        assertEquals(TermResolver.Term("2026-2027", "1"), TermResolver.inferCurrentTerm(date(2026, 8, 31)))
        assertEquals(TermResolver.Term("2026-2027", "1"), TermResolver.inferCurrentTerm(date(2026, 9, 1)))
        assertEquals(TermResolver.Term("2026-2027", "1"), TermResolver.inferCurrentTerm(date(2026, 12, 31)))
    }

    @Test
    fun boundaryFollowsMondayOfSept1WeekEachYear() {
        // 2025-09-01 本身是周一 → 8-31（周日）仍属旧学年 2024-2025 第二学期
        assertEquals(TermResolver.Term("2024-2025", "2"), TermResolver.inferCurrentTerm(date(2025, 8, 31)))
        assertEquals(TermResolver.Term("2025-2026", "1"), TermResolver.inferCurrentTerm(date(2025, 9, 1)))
        // 2027-09-01 是周三 → 8-30（周一）即翻入新学年
        assertEquals(TermResolver.Term("2026-2027", "2"), TermResolver.inferCurrentTerm(date(2027, 8, 29)))
        assertEquals(TermResolver.Term("2027-2028", "1"), TermResolver.inferCurrentTerm(date(2027, 8, 30)))
    }

    @Test
    fun winterAndSummerBreakProbeTheCorrectAdjacentTerms() {
        val winter = TermResolver.breakTransition(date(2026, 2, 1))!!
        assertEquals(TermResolver.Term("2025-2026", "1"), winter.previous)
        assertEquals(TermResolver.Term("2025-2026", "2"), winter.upcoming)

        val summer = TermResolver.breakTransition(date(2026, 8, 1))!!
        assertEquals(TermResolver.Term("2025-2026", "2"), summer.previous)
        assertEquals(TermResolver.Term("2026-2027", "1"), summer.upcoming)
        assertNull(TermResolver.breakTransition(date(2026, 5, 1)))
    }

    @Test
    fun summerBreakEndsOnTheFirstMondayOfNewTerm() {
        // 开学周一之前仍是暑假过渡；当天起不再是过渡（新课表就是当前学期）
        val before = TermResolver.breakTransition(date(2026, 8, 30))
        assertNotNull(before)
        assertEquals(TermResolver.Term("2026-2027", "1"), before!!.upcoming)
        assertNull(TermResolver.breakTransition(date(2026, 8, 31)))
        assertNull(TermResolver.breakTransition(date(2026, 10, 5)))
        // 2025 年开学周一就是 9-1，8-31 仍在过渡期
        assertNotNull(TermResolver.breakTransition(date(2025, 8, 31)))
    }

    @Test
    fun estimatedFirstDayIsAlwaysMondayInTheRequestedAcademicYear() {
        val autumn = TermResolver.estimatedFirstDay(TermResolver.Term("2026-2027", "1"))!!
        val spring = TermResolver.estimatedFirstDay(TermResolver.Term("2026-2027", "2"))!!
        assertEquals(Calendar.MONDAY, autumn.get(Calendar.DAY_OF_WEEK))
        assertEquals(2026, autumn.get(Calendar.YEAR))
        assertEquals(8, autumn.get(Calendar.MONTH) + 1)
        assertEquals(31, autumn.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, spring.get(Calendar.DAY_OF_WEEK))
        assertEquals(2027, spring.get(Calendar.YEAR))
        assertTrue(spring.after(autumn))
    }

    @Test
    fun estimatedFirstDayMatchesAcademicYearFlipDate() {
        // 推断边界与校历估算首日必须同源，否则会出现旧学期套用新学期开学日
        val flipDate = TermResolver.estimatedFirstDay(TermResolver.Term("2026-2027", "1"))!!
        val dayBeforeFlip = flipDate.clone() as Calendar
        dayBeforeFlip.add(Calendar.DAY_OF_YEAR, -1)
        val lastDayOfOldTerm = TermResolver.inferCurrentTerm(dayBeforeFlip)
        val firstDayOfNewTerm = TermResolver.inferCurrentTerm(flipDate)
        assertEquals(TermResolver.Term("2025-2026", "2"), lastDayOfOldTerm)
        assertEquals(TermResolver.Term("2026-2027", "1"), firstDayOfNewTerm)
    }
}
