package com.ifafu.kyzz.data.model

data class Syllabus(
    var searchYearOptions: MutableList<String> = mutableListOf(),
    var searchTermOptions: MutableList<String> = mutableListOf(),
    var selectedYearOption: Int = 0,
    var selectedTermOption: Int = 0,
    var campus: Int = 0,
    var courses: List<Course> = emptyList(),
    var adjustCourses: List<AdjustCourse> = emptyList(),
    var practiceCourses: List<PracticeCourse> = emptyList(),
    var internshipCourses: List<InternshipCourse> = emptyList(),
    var unscheduledCourses: List<UnscheduledCourse> = emptyList()
)

data class AdjustCourse(
    val id: String = "",
    val name: String = "",
    val original: String = "",
    val adjusted: String = "",
    val applyTime: String = ""
)

data class PracticeCourse(
    val name: String = "",
    val teacher: String = "",
    val credit: String = "",
    val weeks: String = "",
    val time: String = "",
    val location: String = ""
)

data class InternshipCourse(
    val year: String = "",
    val term: String = "",
    val name: String = "",
    val time: String = "",
    val moduleCode: String = "",
    val prerequisite: String = "",
    val internshipId: String = ""
)

data class UnscheduledCourse(
    val year: String = "",
    val term: String = "",
    val name: String = "",
    val teacher: String = "",
    val credit: String = ""
)
