package com.ifafu.kyzz.data.model

data class Comment(
    val objectId: String = "",
    val content: String = "",
    val nickname: String = "",
    val authorId: String = "",
    val createdAt: String = "",
    val tag: String = "",
    val likes: List<String> = emptyList()
)

data class UserProfile(
    val objectId: String = "",
    val userId: String = "",
    val nickname: String = ""
)
