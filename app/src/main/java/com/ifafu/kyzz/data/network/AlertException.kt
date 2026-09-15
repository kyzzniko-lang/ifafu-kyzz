package com.ifafu.kyzz.data.network

class AlertException(message: String, val isSessionExpired: Boolean = false) : Exception(message) {
    companion object {
        // "禁止使用此功能/该功能"是正方对会话失效后直连功能页的拦截 alert
        // （见 SessionResponseClassifier），与评教等场景的"禁止评教"类文案不同，
        // 必须用完整短语匹配避免误伤。
        private val SESSION_EXPIRED_KEYWORDS = listOf(
            "过期", "超时", "登录超时", "请重新登录",
            "禁止使用此功能", "禁止使用该功能"
        )

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
