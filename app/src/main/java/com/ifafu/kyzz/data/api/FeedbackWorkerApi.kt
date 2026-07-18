package com.ifafu.kyzz.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends authenticated GitHub write operations through the Cloudflare Worker.
 * The GitHub credential is stored only as a Worker secret and never ships in the APK.
 */
@Singleton
class FeedbackWorkerApi @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "FeedbackWorkerApi"
        private const val BASE_URL = "https://ifafu-feedback.kyzzniko.workers.dev"
    }

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun post(path: String, payload: Any): JsonObject? = execute("POST", path, payload)

    fun put(path: String, payload: Any): JsonObject? = execute("PUT", path, payload)

    fun delete(path: String, payload: Any): Boolean = execute("DELETE", path, payload)
        ?.get("ok")?.asBoolean == true

    private fun execute(method: String, path: String, payload: Any): JsonObject? {
        return try {
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$BASE_URL/${path.trimStart('/')}")
                .header("Accept", "application/json")
                .method(method, body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "$method $path failed with HTTP ${response.code}: ${responseText.take(200)}")
                    return runCatching { JsonParser.parseString(responseText).asJsonObject }
                        .getOrElse { JsonObject().apply {
                            addProperty("ok", false)
                            addProperty("message", "HTTP ${response.code}")
                        } }
                }
                JsonParser.parseString(responseText).asJsonObject
            }
        } catch (e: Exception) {
            Log.e(TAG, "$method $path failed", e)
            null
        }
    }
}
