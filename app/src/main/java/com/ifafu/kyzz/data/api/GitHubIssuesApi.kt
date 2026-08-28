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
                // 优先走 Worker（服务端 Token 不受客户端匿名额度限制）；Worker 尚未
                // 部署或暂时不可用时回退 GitHub 公共接口，避免新版本直接显示空列表。
                // 注意：data 为 null/非数组（如 Worker 返回 {"ok":true,"data":null} 表示
                // 空库）时 getAsJsonArray 会抛 ClassCastException，被外层 catch 吞成
                // 空列表就绕过了 GitHub 兜底，必须先判形态。
                val workerResponse = feedbackWorkerApi.get("/comments?per_page=$perPage&page=$page")
                val dataElement = workerResponse?.get("data")
                if (dataElement != null && dataElement.isJsonArray) {
                    return@withContext parsePublicComments(dataElement.asJsonArray)
                }

                val url = "$BASE/issues/$COMMENTS_ISSUE/comments?per_page=$perPage&page=$page&sort=created&direction=desc"
                client.newCall(publicGet(url)).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext emptyList()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GitHub comments failed: HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    parseGithubComments(JsonParser.parseString(body).asJsonArray)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "getComments error", e)
                emptyList()
            }
        }

    private fun parsePublicComments(items: com.google.gson.JsonArray): List<Comment> =
        items.mapNotNull { element ->
            runCatching {
                val obj = element.asJsonObject
                Comment(
                    objectId = obj.get("id")?.asString.orEmpty(),
                    content = obj.get("content")?.asString ?: obj.get("text")?.asString.orEmpty(),
                    nickname = obj.get("nickname")?.asString.orEmpty().ifBlank { "匿名用户" },
                    authorId = obj.get("authorId")?.asString.orEmpty(),
                    createdAt = obj.get("createdAt")?.asString.orEmpty(),
                    tag = obj.get("tag")?.asString.orEmpty(),
                    likes = obj.getAsJsonArray("likes")?.map { it.asString }.orEmpty()
                ).takeIf { it.content.isNotBlank() }
            }.getOrNull()
        }

    private fun parseGithubComments(items: com.google.gson.JsonArray): List<Comment> =
        items.mapNotNull { element ->
            runCatching {
                val obj = element.asJsonObject
                val data = JsonParser.parseString(obj.get("body")?.asString.orEmpty()).asJsonObject
                Comment(
                    objectId = obj.get("id")?.asString.orEmpty(),
                    content = data.get("content")?.asString ?: data.get("text")?.asString.orEmpty(),
                    nickname = data.get("nickname")?.asString.orEmpty().ifBlank { "匿名用户" },
                    authorId = data.get("authorId")?.asString.orEmpty(),
                    createdAt = obj.get("created_at")?.asString.orEmpty(),
                    tag = data.get("tag")?.asString.orEmpty(),
                    likes = data.getAsJsonArray("likes")?.map { it.asString }.orEmpty()
                ).takeIf { it.content.isNotBlank() }
            }.getOrNull()
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
                // Worker 返回的字段是 createdAt（见 worker.js 的响应映射），
                // 读 created_at 永远为空，自己刚发的评论时间显示空白。
                createdAt = data.get("createdAt")?.asString
                    ?: data.get("created_at")?.asString.orEmpty(),
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

    // 昵称查询进程内缓存（含"查无此人"= null）：匿名 GitHub 接口限额 60 次/小时/IP，
    // 每次进讨论区都翻页查询的话，校园 NAT 后整个宿舍楼的配额会被打光，
    // 连带评论区/课程评价的 GitHub 兜底通道一起 403。
    private val nicknameCache = java.util.concurrent.ConcurrentHashMap<String, String?>()

    suspend fun getNickname(userId: String): String? = withContext(Dispatchers.IO) {
        if (nicknameCache.containsKey(userId)) return@withContext nicknameCache[userId]
        try {
            var page = 1
            while (page <= 5) {
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
                            val nickname = data.get("nickname")?.asString
                            nicknameCache[userId] = nickname
                            return@withContext nickname
                        }
                    }
                    items.size() >= 100
                }
                if (!shouldContinue) break
                page++
            }
            nicknameCache[userId] = null
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
            val ok = feedbackWorkerApi.put(
                "/nicknames",
                mapOf("userId" to userId, "nickname" to nickname)
            )?.get("ok")?.asBoolean == true
            // 保存成功后同步缓存，避免同会话内 checkNickname 读到过期的"未设置"
            if (ok) nicknameCache[userId] = nickname
            ok
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
