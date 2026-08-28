package com.ifafu.kyzz.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseWeekBoundaryTest {
    @Test
    fun oldCacheWithoutExplicitWeeksUsesInclusiveRange() {
        val course = Course(weekBegin = 1, weekEnd = 18, weeks = null)
        assertTrue(course.coversWeek(1))
        assertTrue(course.coversWeek(18))
        assertFalse(course.coversWeek(0))
        assertFalse(course.coversWeek(19))
        assertEquals((1..18).toList(), course.activeWeeks())
    }

    @Test
    fun irregularWeeksKeepGapsAndReportIrregularity() {
        val course = Course(
            weekBegin = 1,
            weekEnd = 16,
            weeks = (1..8).toList() + 10 + (12..16).toList()
        )
        assertFalse(course.coversWeek(9))
        assertTrue(course.coversWeek(10))
        assertFalse(course.coversWeek(11))
        assertTrue(course.hasIrregularWeeks)
    }
}
