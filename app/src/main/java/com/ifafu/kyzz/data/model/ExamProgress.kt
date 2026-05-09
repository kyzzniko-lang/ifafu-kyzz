package com.ifafu.kyzz.data.model

data class ExamProgress(
    val examId: String,
    val status: Int  // 0=未开始, 1=复习中, 2=已掌握
)
