package com.opc.baize.subscription

/** Shared, credential-safe feedback in settings, handwriting and PDF. */
object SubscriptionErrors {
    fun describe(error: Exception): String {
        val failure = error as? SubscriptionProtocol.Failure
        val code = failure?.code ?: if (error is IllegalArgumentException) "invalid_model_output_or_input" else error.javaClass.simpleName
        val hint = when (code) {
            "sign_in_required", "plan_permission_required", "invalid_grant", "invalid_api_key" -> "请重新登录并确认订阅授权。"
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
