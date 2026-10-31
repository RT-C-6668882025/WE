@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
package com.opc.baize.agent

import android.util.AtomicFile
import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One immutable input -> one new paper. Never guesses a file from the assistant's prose. */
class LearningTurn private constructor(val workspace: WeWorkspace, val id: String, private val record: JSONObject) {
    val source = File(workspace.root, "memory/pages/turn-$id.md")
    val pdf = File(workspace.root, "memory/pages/turn-$id.pdf")
    private val journal = File(workspace.container, "turns/$id.json")
    val prompt: String get() = (File(workspace.root,"memory/agent-prompt.txt").takeIf(File::isFile)?.readText()?.take(48000) ?: DEFAULT_TEMPLATE)
        .replace("{request}",record.getString("request"))
        .replace("{source}",record.optString("sourcePath"))
        .replace("{origin}",record.optString("origin").ifBlank { "用户的新需求" })
        .replace("{output}","/memory/pages/turn-$id.md")

    suspend fun execute(runtime: AgentRuntime,
        render: (File, File) -> Unit = { source, output -> LearningPageRenderer.render(source, output, true) },
        onEvent: suspend (AgentEvent) -> Unit = {}): File {
        require(record.getString("state") == "prepared") { "此任务已执行；请从保存的结果继续，避免重复调用。" }
        update("running")
        try {
            var completed = false
            var answer = ""
            runtime.run(AgentRequest(id, workspace.id, prompt)).collect { event ->
                onEvent(event)
                when (event) {
                    is AgentEvent.Failed -> error("Agent 未完成：${event.code}")
                    is AgentEvent.ResultAvailable -> {
                        check(event.result.status == RuntimeRunStatus.COMPLETED) { "页面任务尚未完成：${event.result.status}" }
                        completed = true; answer = event.result.response
                    }
                    else -> Unit
                }
            }
            check(completed) { "Agent 已停止，尚未完成页面。" }
            require(source.isFile && source.length() in 1L..256000L && source.readText().isNotBlank()) { "Agent 尚未保存学习页，原页已保留。" }
            render(source, pdf)
            val previous = workspace.transcript.takeIf(File::isFile)?.readText().orEmpty()
            write(workspace.transcript, (previous + "\n你：${record.getString("request")}\nWE：$answer\n页面：/memory/pages/${source.name}\n").takeLast(256000))
            update("ready")
            return pdf
        } catch (e: Exception) { update("interrupted"); throw e }
    }

    private fun update(state: String) { record.put("state", state); write(journal, record.toString()) }
    /** External Agent supplies content; it never needs access to subscription credentials. */
    fun publishExternal(text: String, render: (File, File) -> Unit) = publishContent(text, "外部 Agent", render)
    fun publishEdited(text: String, render: (File, File) -> Unit) = publishContent(text, "你（编辑）", render)
    private fun publishContent(text: String, author: String, render: (File, File) -> Unit) {
        require(record.getString("state") == "prepared")
        update("running")
        try {
            write(source, text); render(source, pdf)
            require(pdf.isFile && pdf.length() > 0) { "Page rendering failed" }
            val previous = workspace.transcript.takeIf(File::isFile)?.readText().orEmpty()
            write(workspace.transcript, (previous + "\n你：${record.getString("request")}\n$author：已生成学习页\n页面：/memory/pages/${source.name}\n").takeLast(256000))
            update("ready")
        } catch (e: Exception) { update("interrupted"); throw e }
    }
    companion object {
        val DEFAULT_TEMPLATE = """
        {request}

        WE 页面任务：自主选择并读取适用 Skill，使用可用工具完成需求。无需让用户手动串联操作。
        原材料路径：{source}
        本次反馈来源：{origin}。
        理解手写内容是答案、修改要求还是绘图意图，再决定批改、重排、解释或下一步学习内容。
        有看不清的关键内容时，在学习页提出一个具体问题，不编造答案；不要一次给出大量后续练习。
        将本轮完整可阅读的页面内容实际写入 {output}，再读取检查。
        需要题目、答题留白或绘图框架时，可在这个文件保存 JSON 页面：
        {"we_page":1,"title":"标题","mode":"handwriting","blocks":[{"id":"q1","type":"question","text":"题目","answer_lines":3},{"id":"draw1","type":"drawing","height":240,"grid":"blank"}]}。
        支持 heading、text、question、writing、drawing、figure。正文默认 20 点，行高 32 点，页边保留批注空间。
        图形 figure 的 shapes 支持 line、rect、ellipse、text，坐标使用绘图区内 0～1000 相对坐标。
        版面优先适应平板笔：题目和答题空间放在一起，短段落、少量题目和足够留白。修改以新版本保存，不覆盖原材料或笔迹。
        不要只在聊天里回答，也不要让用户自行找文件或排版。
    """.trimIndent()

        fun prepare(workspace: WeWorkspace, request: String, sourcePath: String? = null, origin: String = ""): LearningTurn {
            require(request.isNotBlank() && request.length <= 48000) { "请输入需求（最多 48000 字）。" }
            if (sourcePath != null) {
                val file = File(workspace.root, sourcePath.removePrefix("/")).canonicalFile
                require(file.path.startsWith(workspace.root.canonicalPath + "/memory/pages/") && file.isFile) { "原学习材料不在当前工作区。" }
            }
            val id = UUID.randomUUID().toString()
            return LearningTurn(workspace, id, JSONObject().put("request", request).put("sourcePath", sourcePath.orEmpty())
                .put("origin", origin).put("createdAt", System.currentTimeMillis())).also { it.update("prepared") }
        }
        fun latestReady(workspace: WeWorkspace): File? = File(workspace.container, "turns").listFiles().orEmpty()
            .filter { it.extension == "json" }.mapNotNull { file -> runCatching {
                val record = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                if (record.getString("state") != "ready") null else {
                    val id = file.nameWithoutExtension
                    require(UUID.fromString(id).toString() == id)
                    File(workspace.root, "memory/pages/turn-$id.pdf").takeIf(File::isFile)?.let {
                        record.getLong("createdAt") to it
                    }
                }
            }.getOrNull() }.sortedByDescending { it.first }.firstOrNull()?.second
        fun sourcePath(pdf: File): String = "/memory/pages/${pdf.nameWithoutExtension}.md"
        fun write(file: File, text: String) {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file); val out = atomic.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
        }
    }
}
