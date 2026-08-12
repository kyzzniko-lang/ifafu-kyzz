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
}
