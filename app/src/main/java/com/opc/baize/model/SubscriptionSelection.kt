package com.opc.baize.model

import android.content.Context
import com.opc.baize.subscription.*

/** One source of truth for settings, handwriting, vision fallback and PDF grading. */
data class SubscriptionChoice(val textModel: String, val imageModel: String, val directVision: Boolean = false) {
    fun effectiveImageModel() = imageModel.ifBlank { textModel }
    fun validate(ids: Set<String>) {
        require(textModel in ids) { "请选择账号当前可用的文字模型" }
        require(effectiveImageModel() in ids) { "请选择账号当前可用的图片模型" }
    }
}
object SubscriptionSelection {
    fun read(context: Context): SubscriptionChoice {
        val p = context.getSharedPreferences("moling", Context.MODE_PRIVATE)
        return SubscriptionChoice(p.getString("subscription_model", "") ?: "", p.getString("subscription_image_model", "") ?: "", p.getBoolean("subscription_direct_vision", false))
    }
    fun save(context: Context, choice: SubscriptionChoice, catalog: List<ModelCatalog.Entry>) {
        choice.validate(catalog.map { it.id }.toSet())
        check(context.getSharedPreferences("moling", Context.MODE_PRIVATE).edit()
            .putString("model_provider", "subscription").putString("subscription_model", choice.textModel)
            .putString("subscription_image_model", choice.imageModel).putBoolean("subscription_direct_vision", choice.directVision).commit()) { "模型选择保存失败" }
    }
    fun provider(context: Context, image: Boolean = false): SubscriptionModelProvider {
        val choice = read(context)
        return SubscriptionModelProvider(SubscriptionGateway.get(context), if (image) choice.effectiveImageModel() else choice.textModel)
    }
}
