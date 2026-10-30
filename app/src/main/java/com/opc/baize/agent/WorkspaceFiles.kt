package com.opc.baize.agent

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class WeWorkspace(val id: String, val name: String, val container: File) {
    val root get() = File(container, "workspace").apply { mkdirs() }
    val transcript get() = File(container, "conversation.txt")
}

class WorkspaceFiles(private val context: Context) {
    private val base = File(context.filesDir, "we-workspaces").apply { mkdirs() }
    fun list(): List<WeWorkspace> = base.listFiles().orEmpty().filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
        .map { WeWorkspace(it.name, File(it, "title.txt").takeIf(File::isFile)?.readText()?.take(80) ?: it.name, it) }.sortedBy { it.name }
    fun create(name: String): WeWorkspace {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1～80 个字的工作区名称" }
        val id = UUID.randomUUID().toString()
        val dir = File(base, id).apply { check(mkdirs()) }
        File(dir, "title.txt").writeText(name.trim())
        return WeWorkspace(id, name.trim(), dir).also { it.root }
    }
    fun skillNames(workspace: WeWorkspace): List<String> = File(workspace.root, ".agents/skills").listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "SKILL.md").isFile }.map { it.name }.sorted()
    fun importSkill(workspace: WeWorkspace, uri: Uri): String {
        val source = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "无法读取所选目录" }
        val skillFile = requireNotNull(source.findFile("SKILL.md")) { "请选择包含 SKILL.md 的单个 Skill 文件夹" }
        val markdown = context.contentResolver.openInputStream(skillFile.uri)!!.use { readBounded(it, 64 * 1024).toString(Charsets.UTF_8) }
        val name = Regex("(?m)^name:\\s*[\"']?([a-z0-9]+(?:-[a-z0-9]+)*)[\"']?\\s*$").find(markdown)?.groupValues?.get(1)
            ?: error("SKILL.md 缺少有效的 name")
        require(name.length <= 64)
        val parent = File(workspace.root, ".agents/skills").apply { mkdirs() }
        val target = File(parent, name)
        require(!target.exists()) { "同名 Skill 已存在，请先使用其他名称" }
        // Staging is outside the agent workspace; partial imports never become discoverable.
        val stage = File(workspace.container, "import-${UUID.randomUUID()}").apply { mkdirs() }
        var count = 0; var total = 0
        fun copy(directory: DocumentFile, destination: File, depth: Int) {
            require(depth <= 12) { "Skill 目录过深" }
            for (entry in directory.listFiles()) {
                require(++count <= 200) { "Skill 文件过多" }
                val filename = requireNotNull(entry.name)
                require(filename !in setOf(".", "..") && !filename.contains('/') && !filename.contains('\\')) { "无效文件名" }
                if (filename == ".git") continue
                val out = File(destination, filename)
                if (entry.isDirectory) { out.mkdirs(); copy(entry, out, depth + 1) }
                else {
                    val bytes = context.contentResolver.openInputStream(entry.uri)!!.use { readBounded(it, 10 * 1024 * 1024 - total) }
                    total += bytes.size; out.writeBytes(bytes)
                }
            }
        }
        try { copy(source, stage, 0); check(stage.renameTo(target)) { "无法保存 Skill" } }
        finally { stage.deleteRecursively() }
        return name
    }
    fun mcp(workspace: WeWorkspace): String = File(workspace.container, "mcp.json").takeIf(File::isFile)?.readText() ?: "[]"
    fun saveMcp(workspace: WeWorkspace, text: String) {
        val values = JSONArray(text)
        require(values.length() <= 20)
        for (i in 0 until values.length()) {
            val item = values.getJSONObject(i)
            require(item.keys().asSequence().all { it in setOf("id", "url") }) { "当前仅支持公开 MCP 的 id 和 url" }
            require(item.getString("id").matches(Regex("[a-zA-Z0-9_-]{1,40}"))) { "无效 MCP 标识" }
            val uri = java.net.URI(item.getString("url"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "MCP 地址须为 HTTPS" }
        }
        val atomic = AtomicFile(File(workspace.container, "mcp.json")); val out = atomic.startWrite()
        try { out.write(values.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    companion object {
        private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
            require(max >= 0) { "Skill 总大小超过 10 MB" }
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= max) { "Skill 文件过大" }; out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
