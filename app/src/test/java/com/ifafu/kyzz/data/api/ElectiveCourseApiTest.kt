package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.data.model.ElectiveCourse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectiveCourseApiTest {
    @Test
    fun silentResponseNeedsAnUnambiguousMatchingSelectedClass() {
        val requested = ElectiveCourse(name = "大学英语", code = "EN001", teacher = "张老师", time = "周一", location = "教一")
        assertFalse(ElectiveCourseApi.confirmsSelection(emptyList(), requested))
        assertFalse(ElectiveCourseApi.confirmsSelection(listOf(requested.copy(teacher = "李老师")), requested))
        assertFalse(ElectiveCourseApi.confirmsSelection(listOf(requested, requested.copy()), requested))
        assertTrue(ElectiveCourseApi.confirmsSelection(listOf(requested.copy(courseIndex = "")), requested))
    }
}
