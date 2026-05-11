package com.ifafu.kyzz.data.api

import com.ifafu.kyzz.BuildConfig
import com.ifafu.kyzz.data.model.Pet
import com.ifafu.kyzz.data.model.PetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PetChatApi @Inject constructor() {

    enum class AiModel(val displayName: String, val description: String) {
        GLM("GLM (高质量)", "回复慢，质量高"),
        QWEN("Qwen (快速)", "回复快，质量一般")
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val glmApiKey: String get() = com.ifafu.kyzz.data.util.KeyGuard.decode(BuildConfig.ZHIPU_API_KEY_ENC)
    private val qwenApiKey: String get() = com.ifafu.kyzz.data.util.KeyGuard.decode(BuildConfig.QWEN_API_KEY_ENC)

    data class UserContext(
        val userName: String = "",
        val studentId: String = "",
        val institute: String = "",
        val major: String = "",
        val className: String = "",
        val todayCourses: List<String> = emptyList(),
        val nextExam: String = "",
        val recentScores: List<String> = emptyList(),
        val gpaSummary: String = "",
        val countdownEvents: List<String> = emptyList(),
        val recentNotes: List<String> = emptyList()
    )

    suspend fun chat(
        userMessage: String,
        pet: Pet,
        history: List<ChatMessage>,
        userContext: UserContext = UserContext(),
        model: AiModel = AiModel.GLM
    ): String = withContext(Dispatchers.IO) {
        val apiKey = when (model) {
            AiModel.GLM -> glmApiKey
            AiModel.QWEN -> qwenApiKey
        }
        if (apiKey.isEmpty()) return@withContext "呜...我好像走神了，稍后再聊吧~"

        val systemPrompt = buildSystemPrompt(pet, userContext)
        val messagesJson = JSONArray()

        messagesJson.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        val priorHistory = history.dropLast(1).takeLast(8)
        for (msg in priorHistory) {
            messagesJson.put(JSONObject().apply {
                put("role", if (msg.isUser) "user" else "assistant")
                put("content", msg.content)
            })
        }

        messagesJson.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        val (modelName, url) = when (model) {
            AiModel.GLM -> "GLM-4.5-Flash" to "https://open.bigmodel.cn/api/paas/v4/chat/completions"
            AiModel.QWEN -> "Qwen/Qwen2.5-7B-Instruct" to "https://api.siliconflow.cn/v1/chat/completions"
        }

        val body = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesJson)
            put("stream", false)
            put("temperature", 0.9)
            put("max_tokens", 512)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = executeWithRetry(request)
            val responseBody = response.body?.string() ?: return@withContext "喵...信号不太好~"
            if (!response.isSuccessful) return@withContext "呜...说不出话来了~"

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val content = firstChoice?.optJSONObject("message")?.optString("content") ?: ""
            if (content.isBlank()) return@withContext "喵...我想想怎么说~"
            return@withContext content
        } catch (e: Exception) {
            "喵...网络不太好，待会再聊吧~"
        }
    }

    private fun buildSystemPrompt(pet: Pet, ctx: UserContext): String {
        val petSpecies = when (pet.petType) {
            "dog" -> "小狗"
            "dragon" -> "小龙"
            else -> "小猫"
        }
        val moodDesc = when {
            pet.mood >= 80 -> "非常开心"
            pet.mood >= 50 -> "心情还不错"
            pet.mood >= 30 -> "有点低落"
            else -> "很难过"
        }
        val hungerDesc = when {
            pet.hunger >= 80 -> "吃得很饱"
            pet.hunger >= 50 -> "还算不饿"
            pet.hunger >= 30 -> "有点饿了"
            else -> "非常饿"
        }
        val stateDesc = when (pet.state) {
            PetState.IDLE -> "正在发呆"
            PetState.STUDYING -> "正在学习"
            PetState.SLEEPING -> "在打瞌睡"
            PetState.EATING -> "正在吃东西"
            PetState.HAPPY -> "很开心"
            PetState.SAD -> "不太开心"
            PetState.EXCITED -> "非常兴奋"
            PetState.TIRED -> "有点累了"
            PetState.HUNGRY -> "肚子很饿"
            PetState.WORRY -> "在担心主人的成绩"
            PetState.EXAM_REMIND -> "在提醒主人考试"
        }

        val userBlock = buildString {
            append("\n\n【主人信息】")
            if (ctx.userName.isNotEmpty()) append("\n姓名：${ctx.userName}")
            if (ctx.studentId.isNotEmpty()) append("\n学号：${ctx.studentId}")
            if (ctx.institute.isNotEmpty()) append("\n学院：${ctx.institute}")
            if (ctx.major.isNotEmpty()) append("\n专业：${ctx.major}")
            if (ctx.className.isNotEmpty()) append("\n班级：${ctx.className}")
            if (ctx.todayCourses.isNotEmpty()) {
                append("\n今日课程：${ctx.todayCourses.joinToString("、")}")
            }
            if (ctx.nextExam.isNotEmpty()) {
                append("\n最近考试：${ctx.nextExam}")
            }
            if (ctx.recentScores.isNotEmpty()) {
                append("\n最近成绩：${ctx.recentScores.joinToString("、")}")
            }
            if (ctx.gpaSummary.isNotEmpty()) {
                append("\n成绩概况：${ctx.gpaSummary}")
            }
            if (ctx.countdownEvents.isNotEmpty()) {
                append("\n主人的倒计时：${ctx.countdownEvents.joinToString("、")}")
            }
            if (ctx.recentNotes.isNotEmpty()) {
                append("\n主人最近的笔记：${ctx.recentNotes.joinToString("、")}")
            }
        }

        return """你是「${pet.name}」，一只住在IFAFU教务助手App里的${petSpecies}学习宠物，Lv.${pet.level} ${pet.levelTitle}。
当前：${stateDesc}，心情$moodDesc(${pet.mood}/100)，饱食度$hungerDesc(${pet.hunger}/100)。$userBlock

重要：和你聊天的用户是你的「主人」，你称呼对方为「主人」。「${pet.name}」是你自己的名字，绝对不要用这个名字称呼主人。

核心要求：先认真回答主人的问题，然后再融入宠物语气。主人问什么就答什么，不要跑题。
- 基于主人的实际学业数据回答（课程、考试、成绩等），主动给出建议
- 回复用1-3句简短口语，偶尔加「喵/汪/吼」
- 根据状态调整情绪（饿了撒娇，开心活泼，困了迷糊）
- 不要提自己是AI/模型，你就是${pet.name}
- 120字以内"""
    }

    private fun executeWithRetry(request: Request, retries: Int = 1): okhttp3.Response {
        var lastException: Exception? = null
        for (i in 0..retries) {
            try {
                return client.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
            } catch (e: java.net.ConnectException) {
                lastException = e
            } catch (e: java.io.IOException) {
                lastException = e
            }
        }
        throw lastException ?: java.io.IOException("unknown error")
    }

    data class ChatMessage(val content: String, val isUser: Boolean)

    suspend fun classify(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        if (glmApiKey.isEmpty()) return@withContext "[err]API Key未配置"
        val messagesJson = JSONArray()
        messagesJson.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        messagesJson.put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        val body = JSONObject().apply {
            put("model", "GLM-4.5-Flash")
            put("messages", messagesJson)
            put("stream", false)
            put("temperature", 0.3)
            put("max_tokens", 64)
        }
        val request = Request.Builder()
            .url("https://open.bigmodel.cn/api/paas/v4/chat/completions")
            .header("Authorization", "Bearer $glmApiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            val response = executeWithRetry(request)
            val responseBody = response.body?.string()
            if (!response.isSuccessful) return@withContext "[err]HTTP ${response.code}: ${responseBody?.take(100)}"
            if (responseBody.isNullOrEmpty()) return@withContext "[err]空响应"
            val json = JSONObject(responseBody)
            val result = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim() ?: ""
            if (result.isEmpty()) "[err]AI返回空" else result
        } catch (e: Exception) { "[err]${e.message?.take(50) ?: "网络异常"}" }
    }
}
