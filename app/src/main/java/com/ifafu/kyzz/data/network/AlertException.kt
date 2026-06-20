package com.ifafu.kyzz.data.network

class AlertException(message: String, val isSessionExpired: Boolean = false) : Exception(message) {
    companion object {
        private val SESSION_EXPIRED_KEYWORDS = listOf("过期", "超时", "登录超时", "请重新登录")

        /** 判断 alert 消息是否表示 session 过期 */
        fun isSessionExpiredMessage(message: String): Boolean {
            return SESSION_EXPIRED_KEYWORDS.any { message.contains(it) }
        }

        /** 从 alert 消息创建 AlertException，自动判断是否为 session 过期 */
        fun fromAlert(message: String): AlertException {
            return AlertException(message, isSessionExpiredMessage(message))
        }
    }
}
