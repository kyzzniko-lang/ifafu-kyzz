package com.ifafu.kyzz.data.model

data class Note(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val audioPath: String = "",
    val category: String = "",
    val isFavorite: Boolean = false,
    val isPetVisible: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
