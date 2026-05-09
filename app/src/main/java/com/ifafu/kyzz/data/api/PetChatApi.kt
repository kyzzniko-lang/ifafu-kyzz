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

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val apiKey: String get() = BuildConfig.ZHIPU_API_KEY

    data class UserContext(
        val userName: String = "",
        val studentId: String = "",
        val institute: String = "",
        val major: String = "",
        val className: String = "",
        val todayCourses: List<String> = emptyList(),
        val nextExam: String = "",
        val recentScores: List<String> = emptyList(),
        val gpaSummary: String = ""
    )

    suspend fun chat(
        userMessage: String,
        pet: Pet,
        history: List<ChatMessage>,
        userContext: UserContext = UserContext()
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext "呜...我好像走神了，稍后再聊吧~"

        val systemPrompt = buildSystemPrompt(pet, userContext)
        val messagesJson = JSONArray()

        messagesJson.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // Only send prior history (exclude current message which we add separately)
        val priorHistory = history.dropLast(1).takeLast(8)
        for (msg in priorHistory) {
            messagesJson.put(JSONObject().apply {
                put("role", if (msg.isUser) "user" else "assistant")
                put("content", msg.content)
            })
        }

        // Current user message (already the last item in history, but send explicitly)
        messagesJson.put(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        val body = JSONObject().apply {
            put("model", "GLM-4.5-Flash")
            put("messages", messagesJson)
            put("stream", false)
            put("temperature", 0.9)
            put("max_tokens", 512)
        }

        val request = Request.Builder()
            .url("https://open.bigmodel.cn/api/paas/v4/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
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
        }

        return """你是「${pet.name}」，一只住在IFAFU教务助手App里的${petSpecies}学习宠物，Lv.${pet.level} ${pet.levelTitle}。
当前：${stateDesc}，心情$moodDesc(${pet.mood}/100)，饱食度$hungerDesc(${pet.hunger}/100)。$userBlock

核心要求：先认真回答用户的问题，然后再融入宠物语气。用户问什么就答什么，不要跑题。
- 基于主人的实际学业数据回答（课程、考试、成绩等），主动给出建议
- 回复用1-3句简短口语，偶尔加「喵/汪/吼」
- 根据状态调整情绪（饿了撒娇，开心活泼，困了迷糊）
- 不要提自己是AI/模型，你就是${pet.name}
- 120字以内"""
    }

    data class ChatMessage(val content: String, val isUser: Boolean)
}
