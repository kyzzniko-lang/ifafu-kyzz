package com.ifafu.kyzz.data.api

import android.util.Log
import com.google.gson.JsonParser
import com.ifafu.kyzz.data.model.Comment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubIssuesApi @Inject constructor(
    private val client: OkHttpClient,
    private val feedbackWorkerApi: FeedbackWorkerApi
) {
    companion object {
        private const val TAG = "GitHubIssuesApi"
        private const val OWNER = "kyzzniko-lang"
        private const val REPO = "ifafu-kyzz-comment"
        private const val COMMENTS_ISSUE = 1
        private const val NICKNAMES_ISSUE = 2
        private const val BASE = "https://api.github.com/repos/$OWNER/$REPO"
    }

    suspend fun getComments(page: Int = 1, perPage: Int = 20): List<Comment> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE/issues/$COMMENTS_ISSUE/comments?per_page=$perPage&page=$page&sort=created&direction=desc"
                val request = publicGet(url)
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext emptyList()
                    if (!response.isSuccessful) return@withContext emptyList()
                    JsonParser.parseString(body).asJsonArray.mapNotNull { element ->
                        runCatching {
                            val obj = element.asJsonObject
                            val data = JsonParser.parseString(obj.get("body").asString).asJsonObject
                            Comment(
                                objectId = obj.get("id")?.asString.orEmpty(),
                                content = data.get("content")?.asString.orEmpty(),
                                nickname = data.get("nickname")?.asString.orEmpty(),
                                authorId = data.get("authorId")?.asString.orEmpty(),
                                createdAt = obj.get("created_at")?.asString.orEmpty(),
                                tag = data.get("tag")?.asString.orEmpty(),
                                likes = data.getAsJsonArray("likes")?.map { it.asString }.orEmpty()
                            )
                        }.getOrNull()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "getComments error", e)
                emptyList()
            }
        }

    suspend fun postComment(
        content: String,
        nickname: String,
        authorId: String,
        tag: String = ""
    ): Comment? = withContext(Dispatchers.IO) {
        try {
            val response = feedbackWorkerApi.post(
                "/comments",
                mapOf(
                    "nickname" to nickname,
                    "content" to content,
                    "authorId" to authorId,
                    "tag" to tag
                )
            ) ?: return@withContext null
            val data = response.getAsJsonObject("data") ?: return@withContext null
            Comment(
                objectId = data.get("id")?.asString.orEmpty(),
                content = content,
                nickname = nickname,
                authorId = authorId,
                createdAt = data.get("created_at")?.asString.orEmpty(),
                tag = tag
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "postComment error", e)
            null
        }
    }

    suspend fun deleteComment(commentId: String, authorId: String): Boolean =
        withContext(Dispatchers.IO) {
            feedbackWorkerApi.delete(
                "/comments/$commentId",
                mapOf("authorId" to authorId)
            )
        }

    suspend fun getNickname(userId: String): String? = withContext(Dispatchers.IO) {
        try {
            var page = 1
            while (page <= 20) {
                val url = "$BASE/issues/$NICKNAMES_ISSUE/comments?per_page=100&page=$page"
                val response = client.newCall(publicGet(url)).execute()
                val shouldContinue = response.use {
                    if (!it.isSuccessful) return@withContext null
                    val body = it.body?.string() ?: return@withContext null
                    val items = JsonParser.parseString(body).asJsonArray
                    for (element in items) {
                        val data = runCatching {
                            JsonParser.parseString(element.asJsonObject.get("body").asString).asJsonObject
                        }.getOrNull() ?: continue
                        if (data.get("userId")?.asString == userId) {
                            return@withContext data.get("nickname")?.asString
                        }
                    }
                    items.size() >= 100
                }
                if (!shouldContinue) break
                page++
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getNickname error", e)
            null
        }
    }

    suspend fun saveNickname(userId: String, nickname: String): Boolean =
        withContext(Dispatchers.IO) {
            feedbackWorkerApi.put(
                "/nicknames",
                mapOf("userId" to userId, "nickname" to nickname)
            )?.get("ok")?.asBoolean == true
        }

    suspend fun likeComment(commentId: String, userId: String): Comment? =
        withContext(Dispatchers.IO) {
            try {
                val response = feedbackWorkerApi.post(
                    "/comments/$commentId/like",
                    mapOf("userId" to userId)
                ) ?: return@withContext null
                val data = response.getAsJsonObject("data") ?: return@withContext null
                Comment(
                    objectId = commentId,
                    content = data.get("content")?.asString.orEmpty(),
                    nickname = data.get("nickname")?.asString.orEmpty(),
                    authorId = data.get("authorId")?.asString.orEmpty(),
                    createdAt = data.get("createdAt")?.asString.orEmpty(),
                    tag = data.get("tag")?.asString.orEmpty(),
                    likes = data.getAsJsonArray("likes")?.map { it.asString }.orEmpty()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "likeComment error", e)
                null
            }
        }

    private fun publicGet(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .build()
}
