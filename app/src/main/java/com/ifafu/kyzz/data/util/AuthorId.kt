package com.ifafu.kyzz.data.util

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 匿名发言/点赞的作者标识（authorId）生成器。
 *
 * 旧方案 = 无盐 SHA-256(学号)：学号空间小（约 10^5 量级），枚举哈希即可
 * 把公开评论区里的发言、点赞反查到具体学号。现改为**安装级加盐**：
 * 盐随机生成后持久化，卸载重装/清除数据才会轮换；攻击者无法从 APK 或
 * 公开数据中取得盐，枚举失效。
 *
 * 代价（一次性）：升级后 authorId 轮换，旧昵称需重设、点赞历史重置。
 */
object AuthorId {

    private const val PREFS = "ifafu_user"
    private const val KEY_SALT = "author_salt"

    /** 纯函数核心：prefix + SHA-256(salt:account) 前 16 位 hex。空学号返回空串。 */
    fun hashed(prefix: String, account: String, salt: String): String {
        if (account.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$account".toByteArray())
        return prefix + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 安装级盐：首次调用时 SecureRandom 生成 128-bit 并持久化。 */
    @Synchronized
    fun salt(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SALT, null)?.let { return it }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        prefs.edit().putString(KEY_SALT, salt).apply()
        return salt
    }

    /** 讨论区/课程评价/点赞使用的作者标识（u_ 前缀，历史格式兼容）。 */
    fun discussionUserId(context: Context, account: String): String =
        hashed("u_", account, salt(context))

    /** 宠物聊天未注册服务端凭据时的兜底设备标识（d_ 前缀）。 */
    fun petFallbackDeviceId(context: Context, account: String): String =
        hashed("d_", account, salt(context))
}
