package com.ifafu.kyzz.data.model

data class Syllabus(
    var searchYearOptions: MutableList<String> = mutableListOf(),
    var searchTermOptions: MutableList<String> = mutableListOf(),
    var selectedYearOption: Int = 0,
    var selectedTermOption: Int = 0,
    var campus: Int = 0,
    var courses: List<Course> = emptyList()
)
