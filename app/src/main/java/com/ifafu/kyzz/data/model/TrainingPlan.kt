package com.ifafu.kyzz.data.model

data class TrainingPlan(
    var yearOptions: MutableList<String> = mutableListOf(),
    var selectedYearOption: Int = 0,
    var college: String = "",
    var major: String = "",
    var courses: List<TrainingCourse> = emptyList(),
    var creditSummary: List<CreditSummary> = emptyList()
)

data class TrainingCourse(
    val code: String = "",
    val name: String = "",
    val credit: String = "",
    val weeklyHours: String = "",
    val examType: String = "",
    val courseNature: String = "",
    val courseCategory: String = "",
    val suggestTerm: String = "",
    val weeks: String = ""
)

data class CreditSummary(
    val label: String = "",
    val credit: String = ""
)

data class GradeExam(
    val year: String = "",
    val term: String = "",
    val name: String = "",
    val ticketNumber: String = "",
    val date: String = "",
    val score: String = "",
    val listeningScore: String = "",
    val readingScore: String = "",
    val writingScore: String = "",
    val comprehensiveScore: String = ""
)
