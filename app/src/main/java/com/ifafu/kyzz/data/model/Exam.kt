package com.ifafu.kyzz.data.model

data class Exam(
    var id: String = "",
    var name: String = "",
    var datetime: String = "",
    var address: String = "",
    var seatNumber: String = "",
    var campus: String = ""
)

data class ExamTable(
    var searchYearOptions: MutableList<String> = mutableListOf(),
    var searchTermOptions: MutableList<String> = mutableListOf(),
    var selectedYearOption: Int = 0,
    var selectedTermOption: Int = 0,
    var exams: List<Exam> = emptyList()
)
