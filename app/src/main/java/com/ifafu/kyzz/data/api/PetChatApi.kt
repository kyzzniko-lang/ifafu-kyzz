package com.ifafu.kyzz.data.api

import android.content.Context
import com.ifafu.kyzz.data.model.Pet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 手动构造（调用处传入 deviceId），不参与 Hilt 图：
 * @Inject constructor 携带默认参数时 Dagger 会生成两个注入构造器导致 kapt 编译失败。
 */
class PetChatApi(
    /** 兜底设备标识（学号哈希）。仅在无法注册签名凭据时使用（服务端灰度期放行） */
    private val deviceId: String = "",
    /** 用于持久化服务端签发的设备凭据；为空时退回旧的未签名模式 */
    private val appContext: Context? = null
) {

    enum class AiModel(val displayName: String, val description: String, val wireName: String) {
        GLM("GLM (高质量)", "回复慢，质量高", "glm"),
        QWEN("Qwen (快速)", "回复快，质量一般", "qwen")
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    companion object {
        /**
         * LLM 请求走自建代理（杭州服务器），密钥只存在服务端——
         * 旧方案把 XOR 编码的密钥编进 APK，反编译即可还原，已废弃。
         * 证书为 Let's Encrypt IP 证书，OkHttp 默认信任。
         */
        private const val BASE_URL = "https://116.62.222.192/ifafu/api/pet-chat"
        private const val REGISTER_URL = "$BASE_URL/register"
        private const val PREFS = "pet_chat_device"

        /** 进程内缓存，避免每次聊天都读 SharedPreferences */
        @Volatile private var cachedCred: Pair<String, String>? = null
        @Volatile private var cachedTimeOffset: Long = 0
        private val regLock = Any()

        /**
         * 兜底设备标识（仅在无法注册签名凭据时使用，服务端灰度期放行）。
         * 学号加盐哈希（安装级盐，防枚举反查，见 [com.ifafu.kyzz.data.util.AuthorId]）；
         * 无 context（纯 JVM 调用）或未登录时回退随机 ID。
         */
        fun deviceIdOf(account: String, appContext: Context? = null): String {
            if (appContext != null && account.isNotEmpty()) {
                return com.ifafu.kyzz.data.util.AuthorId.petFallbackDeviceId(appContext, account)
            }
            if (account.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(account.toByteArray())
                return "d_" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
            return "d_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /**
         * 与服务端约定：HMAC 密钥 = SHA-256(secret) 的原始字节，
         * 待签内容 = "device_id\ntimestamp\nnonce\nSHA-256(body)的hex"。
         */
        fun sign(secret: String, deviceId: String, timestamp: String,
                 nonce: String, body: ByteArray): String {
            val key = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
            val bodySha = MessageDigest.getInstance("SHA-256").digest(body).toHex()
            val message = "$deviceId\n$timestamp\n$nonce\n$bodySha".toByteArray()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message).toHex()
        }

        private fun newNonce(): String =
            ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
    }

    /** 服务端已签发的 (device_id, secret)；无 context 或未注册时为 null */
    private fun loadCredential(): Pair<String, String>? {
        cachedCred?.let { return it }
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return null
        val id = prefs.getString("device_id", null)
        val secret = prefs.getString("secret", null)
        if (id.isNullOrBlank() || secret.isNullOrBlank()) return null
        cachedTimeOffset = prefs.getLong("time_offset", 0L)
        return (id to secret).also { cachedCred = it }
    }

    /**
     * 向服务端注册设备凭据（服务端生成 device_id + secret，仅下发一次）。
     * 注册本身受服务端每 IP 每日次数限制，失败时调用方回退未签名模式。
     */
    private fun register(): Boolean {
        if (appContext == null) return false
        synchronized(regLock) {
            loadCredential()?.let { return true }
            return try {
                val request = Request.Builder()
                    .url(REGISTER_URL)
                    .header("Content-Type", "application/json")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    val json = JSONObject(resp.body?.string() ?: return false)
                    val id = json.optString("device_id")
                    val secret = json.optString("secret")
                    if (id.length < 4 || secret.length < 32) return false
                    // 记录与服务端的时钟偏差，签名时间戳按服务端时间校正
                    cachedTimeOffset = json.optLong("server_time", 0L) -
                            System.currentTimeMillis() / 1000
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("device_id", id)
                        .putString("secret", secret)
                        .putLong("time_offset", cachedTimeOffset)
                        .apply()
                    cachedCred = id to secret
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun clearCredential() {
        cachedCred = null
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove("device_id")?.remove("secret")?.apply()
    }

    /**
     * 宠物聊天的用户上下文。隐私约定：只允许粗粒度学业信息，
     * 禁止加入姓名/学号/班级等直接身份字段（详见 [PetPromptBuilder]）。
     */
    data class UserContext(
        val institute: String = "",
        val major: String = "",
        val todayCourses: List<String> = emptyList(),
        val nextExam: String = "",
        val recentScores: List<String> = emptyList(),
        val gpaSummary: String = "",
        val countdownEvents: List<String> = emptyList(),
        val recentNotes: List<String> = emptyList()
    )

    /**
     * @param strict 非聊天场景（如笔记 AI 归类）置 true：请求失败/限流/空响应时
     * 返回空串而不是宠物口吻的兜底文案。否则"今天已经和小宠物聊了很多啦，明天
     * 再来吧~"这类限流文案会被调用方当正常结果写进笔记分类。
     */
    suspend fun chat(
        userMessage: String,
        pet: Pet,
        history: List<ChatMessage>,
        userContext: UserContext = UserContext(),
        model: AiModel = AiModel.GLM,
        strict: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        fun fallback(text: String): String = if (strict) "" else text

        if (deviceId.isEmpty() && appContext == null) {
            return@withContext fallback("呜...我好像走神了，稍后再聊吧~")
        }

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

        val body = JSONObject().apply {
            put("model", model.wireName)
            put("messages", messagesJson)
            put("stream", false)
            put("temperature", 0.9)
            put("max_tokens", 512)
        }
        val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)

        try {
            val response = executeChat(bodyBytes)
            response.use {
                val responseBody = it.body?.string() ?: return@withContext fallback("喵...信号不太好~")
                if (it.code == 429) {
                    // 服务端限流文案已是宠物口吻（如"今天已经和小宠物聊了很多啦"）。
                    // 代理可能返回 HTML 错误页而非 JSON，直接 JSONObject 会抛异常
                    // 落进外层兜底，限流文案就永远展示不出来。
                    val rateLimitMsg = try {
                        JSONObject(responseBody).optString("error").ifBlank { null }
                    } catch (_: Exception) { null }
                    return@withContext fallback(rateLimitMsg ?: "今天聊得有点多啦，明天再来吧~")
                }
                if (!it.isSuccessful) return@withContext fallback("呜...说不出话来了~")

                val json = JSONObject(responseBody)
                val choices = json.optJSONArray("choices")
                val firstChoice = choices?.optJSONObject(0)
                val content = firstChoice?.optJSONObject("message")?.optString("content") ?: ""
                if (content.isBlank()) return@withContext fallback("喵...我想想怎么说~")
                return@withContext content
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback("喵...网络不太好，待会再聊吧~")
        }
    }

    /**
     * 发送聊天请求。有服务端签发的设备凭据时携带 HMAC 签名；凭据被拒（过期/
     * 吊销）则重注册一次再试，仍不可用则降级为旧的 X-Device-Id 明文模式
     * （服务端灰度期内放行，强制签名后该路径返回 401 由调用方兜底）。
     */
    private fun executeChat(bodyBytes: ByteArray): okhttp3.Response {
        var cred = if (register()) loadCredential() else null
        var reRegistered = false
        while (true) {
            val builder = Request.Builder()
                .url(BASE_URL)
                .header("Content-Type", "application/json")
            val current = cred
            if (current != null) {
                val timestamp = (System.currentTimeMillis() / 1000 + cachedTimeOffset).toString()
                val nonce = newNonce()
                builder.header("X-Device-Id", current.first)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", sign(current.second, current.first, timestamp, nonce, bodyBytes))
            } else {
                builder.header("X-Device-Id", deviceId)
            }
            val response = executeWithRetry(
                builder.post(bodyBytes.toRequestBody("application/json".toMediaType())).build()
            )
            if (response.code == 401 && current != null && !reRegistered) {
                response.close()
                reRegistered = true
                clearCredential()
                cred = if (register()) loadCredential() else null
                if (cred != null) continue
            }
            return response
        }
    }

    private fun buildSystemPrompt(pet: Pet, ctx: UserContext): String =
        PetPromptBuilder.build(pet, ctx)

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
}
