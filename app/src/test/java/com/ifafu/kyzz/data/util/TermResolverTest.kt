package com.ifafu.kyzz.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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
        assertEquals(TermResolver.Term("2025-2026", "2"), TermResolver.inferCurrentTerm(date(2026, 8, 31)))
        assertEquals(TermResolver.Term("2026-2027", "1"), TermResolver.inferCurrentTerm(date(2026, 9, 1)))
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
    fun estimatedFirstDayIsAlwaysMondayInTheRequestedAcademicYear() {
        val autumn = TermResolver.estimatedFirstDay(TermResolver.Term("2026-2027", "1"))!!
        val spring = TermResolver.estimatedFirstDay(TermResolver.Term("2026-2027", "2"))!!
        assertEquals(Calendar.MONDAY, autumn.get(Calendar.DAY_OF_WEEK))
        assertEquals(2026, autumn.get(Calendar.YEAR))
        assertEquals(Calendar.MONDAY, spring.get(Calendar.DAY_OF_WEEK))
        assertEquals(2027, spring.get(Calendar.YEAR))
        assertTrue(spring.after(autumn))
    }
}
