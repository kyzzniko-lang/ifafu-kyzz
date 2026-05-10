package com.ifafu.kyzz.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ifafu.kyzz.BuildConfig
import com.ifafu.kyzz.data.model.CourseReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CourseReviewApi @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "CourseReviewApi"
        private const val OWNER = "kyzzniko-lang"
        private const val REPO = "ifafu-kyzz-course-review"
        private const val ISSUE_NUMBER = 1
        private val TOKEN = BuildConfig.GITHUB_TOKEN
        private const val BASE = "https://api.github.com/repos/$OWNER/$REPO"
    }

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun isConfigured(): Boolean = TOKEN.isNotBlank()

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "token $TOKEN",
        "Accept" to "application/vnd.github.v3+json"
    )

    suspend fun getReviews(page: Int = 1, perPage: Int = 20): List<CourseReview> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        try {
            val url = "$BASE/issues/$ISSUE_NUMBER/comments?per_page=$perPage&page=$page&sort=created&direction=desc"
            val request = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.get().build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext emptyList()
                if (!response.isSuccessful) return@withContext emptyList()
                val arr = JsonParser.parseString(body).asJsonArray
                arr.mapNotNull { element ->
                    try {
                        val obj = element.asJsonObject
                        val commentId = obj.get("id")?.asLong?.toString() ?: ""
                        val bodyStr = obj.get("body")?.asString ?: return@mapNotNull null
                        val createdAt = obj.get("created_at")?.asString ?: ""
                        val data = JsonParser.parseString(bodyStr).asJsonObject
                        CourseReview(
                            courseName = data.get("courseName")?.asString ?: "",
                            teacher = data.get("teacher")?.asString ?: "",
                            difficulty = data.get("difficulty")?.asInt ?: 3,
                            grading = data.get("grading")?.asInt ?: 3,
                            attendance = data.get("attendance")?.asInt ?: 3,
                            comment = data.get("comment")?.asString ?: "",
                            nickname = data.get("nickname")?.asString ?: "",
                            authorId = data.get("authorId")?.asString ?: "",
                            commentId = commentId,
                            createdAt = createdAt
                        )
                    } catch (e: Exception) { null }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getReviews error", e)
            emptyList()
        }
    }

    suspend fun postReview(review: CourseReview): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val url = "$BASE/issues/$ISSUE_NUMBER/comments"
            val bodyJson = gson.toJson(mapOf("body" to gson.toJson(review)))
            val request = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.post(bodyJson.toRequestBody(JSON)).build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "postReview error", e)
            false
        }
    }

    suspend fun deleteReview(commentId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        try {
            val url = "$BASE/issues/comments/$commentId"
            val request = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.delete().build()

            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "deleteReview error", e)
            false
        }
    }
}
