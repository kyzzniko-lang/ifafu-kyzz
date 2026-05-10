package com.ifafu.kyzz.data.repository

import com.ifafu.kyzz.data.api.GitHubIssuesApi
import com.ifafu.kyzz.data.model.Comment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepository @Inject constructor(
    private val api: GitHubIssuesApi
) {
    suspend fun getComments(page: Int = 1, perPage: Int = 20): List<Comment> {
        return api.getComments(page, perPage)
    }

    suspend fun postComment(content: String, nickname: String, authorId: String, tag: String = ""): Comment? {
        return api.postComment(content, nickname, authorId, tag)
    }

    suspend fun deleteComment(commentId: String): Boolean {
        return api.deleteComment(commentId)
    }

    suspend fun getNickname(userId: String): String? {
        return api.getNickname(userId)
    }

    suspend fun saveNickname(userId: String, nickname: String): Boolean {
        return api.saveNickname(userId, nickname)
    }

    suspend fun likeComment(commentId: String, userId: String): Comment? {
        return api.likeComment(commentId, userId)
    }
}
