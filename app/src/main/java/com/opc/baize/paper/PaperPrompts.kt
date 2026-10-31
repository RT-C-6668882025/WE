package com.opc.baize.paper

import android.content.Context

/** Defaults are editable presets, not hidden task instructions. */
object PaperPrompts {
    val labels = arrayOf("识别", "批改", "解释", "自由指令")
    val defaults = arrayOf(
        "识别图中可见文字、手写和图形关系，按原布局顺序转写；看不清的位置标记[不清晰]。",
        "批改我的作答，指出错误、原因与正确解法；信息不足时先询问。",
        "解释选中内容与相关知识，给出清晰步骤。",
        "理解我的要求，生成或修改当前工作区内容。"
    )
    const val DEFAULT_SYSTEM = "你在 WE（Write Everything）的 Android 工作区中。按需读取 Skill 与参考资料，使用可用 MCP 和工具完成用户要求。学习页保存在 /memory/pages/。版面适合手写、保留留白。按用户要求工作，准确报告实际保存的结果。"
    fun read(context: Context, index: Int) = prefs(context).getString("paper_prompt_$index", defaults[index])!!
    fun system(context: Context) = prefs(context).getString("paper_system_prompt", DEFAULT_SYSTEM)!!
    fun save(context: Context, index: Int, text: String) { require(text.length <= 48000); prefs(context).edit().putString("paper_prompt_$index", text).apply() }
    fun saveSystem(context: Context, text: String) { require(text.length <= 48000); prefs(context).edit().putString("paper_system_prompt", text).apply() }
    private fun prefs(context: Context) = context.getSharedPreferences("moling", Context.MODE_PRIVATE)
}
