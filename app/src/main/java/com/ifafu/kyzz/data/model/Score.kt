package com.ifafu.kyzz.data.model

data class Score(
    var year: String = "",
    var term: String = "",
    var courseCode: String = "",
    var courseName: String = "",
    var courseType: String = "",
    var courseOwner: String = "",
    var studyScore: Float = 0f,
    var score: Float = 0f,
    var isDelayExam: Boolean = false,
    var makeupScore: Float = 0f,
    var isRestudy: Boolean = false,
    var institute: String = "",
    var scorePoint: Float = 0f,
    var comment: String = "",
    var makeupComment: String = ""
)

data class ScoreTable(
    var searchYearOptions: MutableList<String> = mutableListOf(),
    var searchTermOptions: MutableList<String> = mutableListOf(),
    var defaultSelectedYear: Int = 0,
    var defaultSelectedTerm: Int = 0,
    var scores: List<Score> = emptyList()
)
