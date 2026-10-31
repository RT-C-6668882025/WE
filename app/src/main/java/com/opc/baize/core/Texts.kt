package com.opc.baize.core

/** WE paper surface copy. */
object Texts {
    const val PERSONA = "你是 WE（Write Everything）学习助手。用简洁的现代中文回答，先回应核心问题，再按需要解释依据。" +
        "手写识别可能有错字，结合上下文理解；无法辨认的关键内容先确认。不要编造题干。" +
        "纸面回复保持简洁，可分段，不使用复杂 Markdown。"
    const val EMPTY_RECOG_REPLY = "没有看清这次笔迹，请写大一点再试。"
    const val NEW_SESSION_REPLY = "已开始新对话。"
    const val INTRO_TEXT = "Write Everything\n选择手写、绘画或打字，继续同一个工作区。识别由你决定何时发送。"
    const val FAIL_TEXT = "这次请求没有完成，请重试。"
    const val MODEL_DOWNLOADING_TIP = "正在下载本机识别模型…"
    const val NO_RECOG_TIP = "本机手写识别不可用。请在设置中启用订阅视觉识别，或配置识别服务。"
}
