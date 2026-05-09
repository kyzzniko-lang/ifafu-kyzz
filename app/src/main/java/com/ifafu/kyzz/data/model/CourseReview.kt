package com.ifafu.kyzz.data.model

data class CourseReview(
    val courseName: String = "",
    val teacher: String = "",
    val difficulty: Int = 3,      // 1-5 难度 (1=简单, 5=很难)
    val grading: Int = 3,         // 1-5 给分 (1=很严, 5=很松)
    val attendance: Int = 3,      // 1-5 点名 (1=不点, 5=每节都点)
    val comment: String = "",
    val nickname: String = "",
    val authorId: String = "",
    val commentId: String = "",
    val createdAt: String = ""
)
