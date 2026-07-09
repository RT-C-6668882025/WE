package com.opc.baize.core

/**
 * 会话记忆：内存保留最近 N 轮（识别文字 + 回文），与 server.py 的
 * deque(maxlen=MAX_TURNS) 同构。App 单会话，无需按 session 分桶。
 */
class SessionMemory(private val maxTurns: Int = 6) {

    private val turns = ArrayDeque<Pair<String, String>>()

    @Synchronized
    fun remember(recognized: String, reply: String) {
        // 空识别不进记忆（空 user 内容会让 Messages API 400，同 server.py 教训）
        if (recognized.isEmpty()) return
        turns.addLast(recognized to reply)
        while (turns.size > maxTurns) turns.removeFirst()
    }

    @Synchronized
    fun history(): List<Pair<String, String>> = turns.toList()

    @Synchronized
    fun clear() = turns.clear()
}
