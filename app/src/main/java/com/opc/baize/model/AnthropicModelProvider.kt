package com.opc.baize.model

import com.opc.baize.core.ModelConfig
import com.opc.baize.net.DeepSeekClient

class AnthropicModelProvider(private val config: ModelConfig) : ModelProvider {
    override val supportsImages = false
    override fun isReady() = config.isReady()
    override fun reply(history: List<Pair<String, String>>, text: String, imageDataUrl: String?): String {
        require(imageDataUrl == null) { "此 Anthropic 适配器目前只支持文字" }
        return DeepSeekClient().reply(config, history, text)
    }
}
