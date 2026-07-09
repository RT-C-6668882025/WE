package com.opc.baize.core

/**
 * 大模型配置的纯逻辑部分（默认值 + 兜底判定），与 Android SharedPreferences 解耦，便于 JVM 单测。
 *
 * 开源版：密钥/节点/模型名全部运行时由用户填。三者任一为空 → 回复环节兜底提示，不崩。
 */
data class ModelConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
) {
    /** 三项齐全（去空白后非空）才算配置就绪，可发起回复请求。 */
    fun isReady(): Boolean =
        apiKey.trim().isNotEmpty() &&
        endpoint.trim().isNotEmpty() &&
        model.trim().isNotEmpty()

    companion object {
        /** 访问节点默认值（兼容 Anthropic Messages API 的服务；DeepSeek 开放平台）。 */
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/anthropic"

        /** 模型名默认值。 */
        const val DEFAULT_MODEL = "deepseek-v4-flash"

        /** 未配置时在纸页写出的兜底提示（沿用失败文案的浅近文言风格）。 */
        const val NOT_CONFIGURED_TIP =
            "……墨灵尚未通灵。请先在设置中填入大模型 API 密钥，方能应答。"
    }
}
