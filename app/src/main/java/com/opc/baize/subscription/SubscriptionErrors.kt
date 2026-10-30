package com.opc.baize.subscription

/** Shared, credential-safe feedback in settings, handwriting and PDF. */
object SubscriptionErrors {
    fun describe(error: Exception): String {
        val failure = error as? SubscriptionProtocol.Failure
        val code = failure?.code ?: if (error is IllegalArgumentException) "invalid_model_output_or_input" else error.javaClass.simpleName
        val hint = when (code) {
            "sign_in_required", "plan_permission_required", "invalid_grant", "invalid_api_key" -> "请重新登录并确认订阅授权。"
            "refresh_interrupted_sign_in_required" -> "上次刷新在响应保存前中断，为避免重复使用旧令牌，请重新登录。"
            "saved_session_unavailable", "saved_session_key_unavailable", "saved_session_invalid", "saved_session_binding_mismatch" -> "保存的登录无法安全恢复，请重新登录；若仍失败，可清除本应用数据后重试。"
            "identity_verification_pending" -> "登录响应已加密保存，请在订阅设置点击恢复保存的登录，重试身份校验。"
            "saved_session_clear_failed" -> "本地登录信息清理失败，请清除本应用数据后重新登录。"
            "refresh_not_due" -> "尚未到允许刷新时间，请稍后再试。"
            "model_not_found", "no_visible_models", "invalid_model_catalog" -> "请刷新账号模型列表并重新选择。"
            "subscription_sharing_usage_limit_exceeded" -> "订阅额度已达上限，请到 ChatGPT 用量设置查看。"
            "subscription_sharing_user_not_eligible" -> "当前账号不具备此订阅授权资格。"
            "tls_handshake_failed", "network_connection_reset", "network_timeout", "network_dns_failed", "network_connect_failed" -> "网络连接未完成，请检查网络后重试。"
            "invalid_model_output_or_input" -> "模型输出格式或输入不符合要求，可在模型设置页测试所选模型。"
            else -> "请查看订阅设置中的阶段日志，或测试当前模型。"
        }
        return "$hint\n错误：$code" + (if (failure != null && failure.status != 0) "（HTTP ${failure.status}）" else "")
    }
}
