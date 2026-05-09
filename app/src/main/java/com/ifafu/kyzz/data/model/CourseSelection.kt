package com.ifafu.kyzz.data.model

data class CourseSelection(
    var courseNumber: String = "",
    var courseCode: String = "",
    var courseName: String = "",
    var courseNature: String = "",
    var selected: String = "",
    var teacher: String = "",
    var credits: String = "",
    var weekHours: String = "",
    var time: String = "",
    var location: String = "",
    var textbook: String = "",
    var studyMark: String = "",
    var remark: String = ""
)

data class MakeupExam(
    var courseNumber: String = "",
    var courseName: String = "",
    var studentName: String = "",
    var examTime: String = "",
    var examLocation: String = "",
    var seatNumber: String = "",
    var examForm: String = ""
)
