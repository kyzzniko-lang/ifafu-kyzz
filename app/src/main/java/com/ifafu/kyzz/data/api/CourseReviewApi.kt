package com.ifafu.kyzz.data.api

import android.util.Log
import com.google.gson.JsonParser
import com.ifafu.kyzz.data.model.CourseReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CourseReviewApi @Inject constructor(
    private val client: OkHttpClient,
    private val feedbackWorkerApi: FeedbackWorkerApi
) {
    companion object {
        private const val TAG = "CourseReviewApi"
        private const val OWNER = "kyzzniko-lang"
        private const val REPO = "ifafu-kyzz-course-review"
        private const val ISSUE_NUMBER = 1
        private const val BASE = "https://api.github.com/repos/$OWNER/$REPO"
    }

    suspend fun getReviews(page: Int = 1, perPage: Int = 20): List<CourseReview> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/issues/$ISSUE_NUMBER/comments?per_page=$perPage&page=$page&sort=created&direction=desc"
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .get().build()

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
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { null }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getReviews error", e)
            emptyList()
        }
    }

    suspend fun postReview(review: CourseReview): Boolean = withContext(Dispatchers.IO) {
        try {
            feedbackWorkerApi.post("/course-reviews", review)
                ?.get("ok")?.asBoolean == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "postReview error", e)
            false
        }
    }

    suspend fun deleteReview(commentId: String, authorId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            feedbackWorkerApi.delete(
                "/course-reviews/$commentId",
                mapOf("authorId" to authorId)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "deleteReview error", e)
            false
        }
    }
}
