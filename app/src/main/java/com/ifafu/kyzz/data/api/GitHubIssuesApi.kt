package com.ifafu.kyzz.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ifafu.kyzz.BuildConfig
import com.ifafu.kyzz.data.model.Comment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubIssuesApi @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "GitHubIssuesApi"
        private const val OWNER = "kyzzniko-lang"
        private const val REPO = "ifafu-kyzz-comment"
        private const val COMMENTS_ISSUE = 1
        private const val NICKNAMES_ISSUE = 2
        private val TOKEN = com.ifafu.kyzz.data.util.KeyGuard.decode(BuildConfig.GITHUB_TOKEN_ENC)
        private const val BASE = "https://api.github.com/repos/$OWNER/$REPO"
    }

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun isConfigured(): Boolean = TOKEN.isNotBlank()

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "token $TOKEN",
        "Accept" to "application/vnd.github.v3+json"
    )

    // ========== 评论 ==========

    suspend fun getComments(page: Int = 1, perPage: Int = 20): List<Comment> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        try {
            val url = "$BASE/issues/$COMMENTS_ISSUE/comments?per_page=$perPage&page=$page&sort=created&direction=desc"
            val request = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.get().build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                if (!response.isSuccessful) {
                    Log.e(TAG, "getComments failed: $body")
                    return@withContext emptyList()
                }
                val arr = JsonParser.parseString(body).asJsonArray
                arr.mapNotNull { element ->
                    try {
                        val obj = element.asJsonObject
                        val commentId = obj.get("id")?.asLong ?: 0
                        val bodyStr = obj.get("body")?.asString ?: return@mapNotNull null
                        val createdAt = obj.get("created_at")?.asString ?: ""

                        val data = JsonParser.parseString(bodyStr).asJsonObject
                        val likesArr = data.getAsJsonArray("likes")
                        val likes = likesArr?.map { it.asString } ?: emptyList()
                        Comment(
                            objectId = commentId.toString(),
                            content = data.get("content")?.asString ?: "",
                            nickname = data.get("nickname")?.asString ?: "",
                            authorId = data.get("authorId")?.asString ?: "",
                            createdAt = createdAt,
                            tag = data.get("tag")?.asString ?: "",
                            likes = likes
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getComments error", e)
            emptyList()
        }
    }

    suspend fun postComment(content: String, nickname: String, authorId: String, tag: String = ""): Comment? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        try {
            val url = "$BASE/issues/$COMMENTS_ISSUE/comments"
            val innerJson = com.google.gson.JsonObject().apply {
                addProperty("nickname", nickname)
                addProperty("content", content)
                addProperty("authorId", authorId)
                addProperty("tag", tag)
                add("likes", com.google.gson.JsonArray())
            }
            val bodyJson = gson.toJson(mapOf("body" to innerJson.toString()))
            val request = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.post(bodyJson.toRequestBody(JSON)).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext null
                if (!response.isSuccessful) {
                    Log.e(TAG, "postComment failed: $body")
                    return@withContext null
                }
                val json = JsonParser.parseString(body).asJsonObject
                Comment(
                    objectId = json.get("id")?.asString ?: "",
                    content = content,
                    nickname = nickname,
                    authorId = authorId,
                    createdAt = json.get("created_at")?.asString ?: "",
                    tag = tag,
                    likes = emptyList()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "postComment error", e)
            null
        }
    }

    suspend fun deleteComment(commentId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val url = "$BASE/issues/comments/$commentId"
            val request = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.delete().build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "deleteComment error", e)
            false
        }
    }

    // ========== 用户昵称（Issue #2 作为注册表） ==========

    suspend fun getNickname(userId: String): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        try {
            var page = 1
            val maxPages = 20
            var result: String? = null
            while (result == null && page <= maxPages) {
                val url = "$BASE/issues/$NICKNAMES_ISSUE/comments?per_page=100&page=$page"
                val request = Request.Builder().url(url).apply {
                    authHeaders().forEach { (k, v) -> header(k, v) }
                }.get().build()

                val shouldContinue = client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use false
                    if (!response.isSuccessful) return@use false
                    val arr = JsonParser.parseString(body).asJsonArray
                    if (arr.size() == 0) return@use false
                    for (element in arr) {
                        try {
                            val obj = element.asJsonObject
                            val bodyStr = obj.get("body")?.asString ?: continue
                            val data = JsonParser.parseString(bodyStr).asJsonObject
                            if (data.get("userId")?.asString == userId) {
                                result = data.get("nickname")?.asString
                                return@use false
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {}
                    }
                    arr.size() >= 100
                }
                if (!shouldContinue) break
                page++
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getNickname error", e)
            null
        }
    }

    suspend fun saveNickname(userId: String, nickname: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            var existingCommentId: Long? = null
            var page = 1
            val maxPages = 20
            while (existingCommentId == null && page <= maxPages) {
                val listUrl = "$BASE/issues/$NICKNAMES_ISSUE/comments?per_page=100&page=$page"
                val listRequest = Request.Builder().url(listUrl).apply {
                    authHeaders().forEach { (k, v) -> header(k, v) }
                }.get().build()

                val found = client.newCall(listRequest).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext false
                    val arr = JsonParser.parseString(body).asJsonArray
                    if (arr.size() == 0) return@use true // no more pages, not found
                    var foundId: Long? = null
                    for (element in arr) {
                        try {
                            val obj = element.asJsonObject
                            val bodyStr = obj.get("body")?.asString ?: continue
                            val data = JsonParser.parseString(bodyStr).asJsonObject
                            if (data.get("userId")?.asString == userId) {
                                foundId = obj.get("id")?.asLong
                                break
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {}
                    }
                    if (foundId != null) {
                        existingCommentId = foundId
                        true
                    } else if (arr.size() < 100) {
                        true // no more pages
                    } else {
                        page++
                        false
                    }
                }
                if (found) break
            }

            val nicknameBody = gson.toJson(mapOf("body" to gson.toJson(mapOf(
                "userId" to userId,
                "nickname" to nickname
            ))))

            if (existingCommentId != null) {
                // 更新已有昵称
                val updateUrl = "$BASE/issues/comments/$existingCommentId"
                val updateRequest = Request.Builder().url(updateUrl).apply {
                    authHeaders().forEach { (k, v) -> header(k, v) }
                }.patch(nicknameBody.toRequestBody(JSON)).build()
                client.newCall(updateRequest).execute().use { it.isSuccessful }
            } else {
                // 创建新昵称
                val createUrl = "$BASE/issues/$NICKNAMES_ISSUE/comments"
                val createRequest = Request.Builder().url(createUrl).apply {
                    authHeaders().forEach { (k, v) -> header(k, v) }
                }.post(nicknameBody.toRequestBody(JSON)).build()
                client.newCall(createRequest).execute().use { it.isSuccessful }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "saveNickname error", e)
            false
        }
    }

    // ========== 点赞 ==========

    suspend fun likeComment(commentId: String, userId: String): Comment? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        try {
            // 1. 读取当前 comment body
            val getUrl = "$BASE/issues/comments/$commentId"
            val getRequest = Request.Builder().url(getUrl).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.get().build()

            val currentBody = client.newCall(getRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JsonParser.parseString(response.body?.string() ?: return@withContext null).asJsonObject
                json.get("body")?.asString ?: return@withContext null
            }

            val data = JsonParser.parseString(currentBody).asJsonObject
            val likesArr = data.getAsJsonArray("likes") ?: com.google.gson.JsonArray()
            val likes = likesArr.map { it.asString }.toMutableList()

            if (userId in likes) {
                likes.remove(userId)
            } else {
                likes.add(userId)
            }
            data.add("likes", gson.toJsonTree(likes))

            // 2. PATCH 更新
            val patchBody = gson.toJson(mapOf("body" to data.toString()))
            val patchRequest = Request.Builder().url(getUrl).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.patch(patchBody.toRequestBody(JSON)).build()

            client.newCall(patchRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                Comment(
                    objectId = commentId,
                    content = data.get("content")?.asString ?: "",
                    nickname = data.get("nickname")?.asString ?: "",
                    authorId = data.get("authorId")?.asString ?: "",
                    createdAt = "",
                    tag = data.get("tag")?.asString ?: "",
                    likes = likes
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "likeComment error", e)
            null
        }
    }
}
