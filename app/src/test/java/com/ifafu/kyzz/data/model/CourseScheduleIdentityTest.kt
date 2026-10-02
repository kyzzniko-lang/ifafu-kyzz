package com.ifafu.kyzz.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseScheduleIdentityTest {
    @Test
    fun distinctScheduleKeepsRoomAndWeekRangeChanges() {
        val base = Course(
            name = "测试课程",
            teacher = "张老师",
            address = "一教101",
            weekDay = 1,
            begin = 1,
            end = 2,
            weekBegin = 1,
            weekEnd = 8,
            oddOrTwice = 0
        )
        val moved = base.copy(address = "二教202", weekBegin = 9, weekEnd = 16)

        val distinct = listOf(base, moved, base.copy()).distinctBy { it.scheduleIdentity() }

        assertEquals(listOf(base, moved), distinct)
    }

    @Test
    fun distinctScheduleKeepsDifferentNonContinuousWeeks() {
        val base = Course(name = "测试课程", weekBegin = 1, weekEnd = 8, weeks = listOf(1, 3, 8))
        val other = base.copy(weeks = listOf(1, 5, 8))
        assertEquals(2, listOf(base, other).distinctBy { it.scheduleIdentity() }.size)
        assertEquals(base.scheduleIdentity(), base.copy(weeks = listOf(8, 3, 1, 1)).scheduleIdentity())
    }

    @Test
    fun continuousCachedAndExplicitWeeksHaveTheSameIdentity() {
        val base = Course(name = "测试课程", weekBegin = 1, weekEnd = 3)
        assertEquals(base.scheduleIdentity(), base.copy(weeks = listOf(1, 2, 3)).scheduleIdentity())
    }
}
