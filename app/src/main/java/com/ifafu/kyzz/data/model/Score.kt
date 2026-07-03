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
) {
    // 运行时字段：本地首次见到该成绩的时间戳。不参与 JSON 序列化（transient），
    // 仅用于成绩页排序。教务系统不提供录入时间，以此时间戳近似"出分先后"。
    @Transient var firstSeenTs: Long = 0L

    /**
     * NEW 标记判定：成绩首次见到时间在最近 [windowMs] 内则显示 NEW。
     * 默认 48 小时常驻，刷新不会丢失；超过窗口自动下架；窗口内出现更新的成绩时，
     * 旧成绩因 firstSeenTs 更早自然不再是最新的（但仍在窗口内也会显示 NEW，
     * 由"同批"语义决定——同一天出分的成绩一起显示 NEW 是合理的）。
     */
    fun isNewWithin(windowMs: Long = 48 * 60 * 60 * 1000L): Boolean {
        if (firstSeenTs <= 0L) return false
        return System.currentTimeMillis() - firstSeenTs <= windowMs
    }
}

data class ScoreTable(
    var searchYearOptions: MutableList<String> = mutableListOf(),
    var searchTermOptions: MutableList<String> = mutableListOf(),
    var defaultSelectedYear: Int = 0,
    var defaultSelectedTerm: Int = 0,
    var scores: List<Score> = emptyList()
)
