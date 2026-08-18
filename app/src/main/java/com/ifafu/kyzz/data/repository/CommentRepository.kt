package com.ifafu.kyzz.data.repository

import com.ifafu.kyzz.data.api.GitHubIssuesApi
import com.ifafu.kyzz.data.cache.CacheManager
import com.ifafu.kyzz.data.model.Comment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepository @Inject constructor(
    private val api: GitHubIssuesApi,
    private val cacheManager: CacheManager
) {
    companion object {
        private const val DISCUSSION_CACHE_MAX_AGE_MS = 30 * 60 * 1000L
    }

    suspend fun getComments(page: Int = 1, perPage: Int = 20): List<Comment> {
        val comments = api.getComments(page, perPage)
        if (comments.isNotEmpty() && page == 1) {
            withContext(Dispatchers.IO) { cacheManager.saveDiscussionComments(comments) }
        }
        return comments
    }

    suspend fun loadCachedComments(): List<Comment>? = withContext(Dispatchers.IO) {
        cacheManager.loadDiscussionComments()
    }

    fun isDiscussionCacheStale(): Boolean =
        cacheManager.isDiscussionCommentsStale(DISCUSSION_CACHE_MAX_AGE_MS)

    suspend fun saveCachedComments(comments: List<Comment>) = withContext(Dispatchers.IO) {
        cacheManager.saveDiscussionComments(comments)
    }

    suspend fun postComment(content: String, nickname: String, authorId: String, tag: String = ""): Comment? {
        return api.postComment(content, nickname, authorId, tag)
    }

    suspend fun deleteComment(commentId: String, authorId: String): Boolean {
        return api.deleteComment(commentId, authorId)
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
