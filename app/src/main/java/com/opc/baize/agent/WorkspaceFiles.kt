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
    val root get() = File(container, "workspace").apply { mkdirs(); File(this, "memory/pages").mkdirs() }
    val transcript get() = File(container, "conversation.txt")
}

class WorkspaceFiles(private val context: Context) {
    private val base = File(context.filesDir, "we-workspaces").apply { mkdirs() }
    fun all(): List<WeWorkspace> = synchronized(managementLock) { base.listFiles().orEmpty().filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
        .map { dir -> val title = File(dir, "title.txt")
            WeWorkspace(dir.name, if (title.exists() || File(title.path + ".bak").exists()) AtomicFile(title).openRead().bufferedReader().use { it.readText().take(80) } else dir.name, dir)
        }.sortedBy { it.name } }
    fun list(): List<WeWorkspace> = all().filter { state(it).trashedAt == 0L }
    fun create(name: String): WeWorkspace {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1～80 个字的工作区名称" }
        val id = UUID.randomUUID().toString()
        val dir = File(base, id).apply { check(mkdirs()) }
        File(dir, "title.txt").writeText(name.trim())
        return WeWorkspace(id, name.trim(), dir).also { it.root }
    }
    /** History is indexed in the same workspace list; the original session archive is retained. */
    fun forSession(session: com.opc.baize.core.Session): WeWorkspace {
        // Trashed sessions stay indexed so the legacy archive cannot resurrect them.
        val existing = all().firstOrNull { sessionId(it) == session.id }
        var workspace = existing ?: create(if (session.isEmpty()) "新工作区" else session.summary()).also {
            attachSession(it, session.id)
            if (session.isEmpty()) LearningTurn.write(File(it.container, "automatic-title.txt"), "pending")
        }
        if (state(workspace).trashedAt > 0) return workspace
        if (!session.isEmpty() && File(workspace.container, "automatic-title.txt").exists()) {
            rename(workspace, session.summary()); workspace = workspace.copy(name = session.summary())
        }
        val text = session.turns.joinToString("\n\n") { "你：${it.question}\nWE：${it.reply}" }
        val source = File(workspace.root, "memory/pages/conversation.md")
        if (text.isNotEmpty() && (!source.isFile || source.readText() != text)) {
            LearningTurn.write(source, text); workspace.container.setLastModified(System.currentTimeMillis())
        }
        return workspace
    }
    fun sessionId(workspace: WeWorkspace): String? = File(workspace.container, "session-id.txt").takeIf(File::isFile)?.readText()
    fun attachSession(workspace: WeWorkspace, id: String) {
        require(id.isNotBlank() && id.length <= 256)
        LearningTurn.write(File(workspace.container, "session-id.txt"), id)
    }
    fun rename(workspace: WeWorkspace, name: String) = synchronized(managementLock) {
        require(name.isNotBlank() && name.length <= 80) { "请输入 1～80 个字的名称" }
        LearningTurn.write(File(workspace.container, "title.txt"), name.trim())
        File(workspace.container, "automatic-title.txt").delete()
    }
    data class State(val pinned: Boolean = false, val archived: Boolean = false, val trashedAt: Long = 0, val openedAt: Long = 0)
    fun state(workspace: WeWorkspace): State = synchronized(managementLock) {
        val file = File(workspace.container, "management.json")
        if (!file.exists() && !File(file.path + ".bak").exists()) return State()
        val value = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        return State(value.optBoolean("pinned"), value.optBoolean("archived"), value.optLong("trashedAt"), value.optLong("openedAt"))
    }
    fun manage(workspace: WeWorkspace, action: String) = synchronized(managementLock) {
        require(all().any { it.id == workspace.id }) { "工作区不存在" }
        val current = state(workspace)
        val next = when (action) {
            "pin" -> current.copy(pinned = true)
            "unpin" -> current.copy(pinned = false)
            "archive" -> current.copy(archived = true)
            "unarchive" -> current.copy(archived = false)
            "trash" -> if (current.trashedAt > 0) current else current.copy(trashedAt = System.currentTimeMillis())
            "restore" -> current.copy(trashedAt = 0)
            "open" -> { require(current.trashedAt == 0L) { "请先恢复工作区" }; current.copy(openedAt = System.currentTimeMillis()) }
            else -> error("未知管理操作")
        }
        LearningTurn.write(File(workspace.container, "management.json"), JSONObject().put("pinned", next.pinned)
            .put("archived", next.archived).put("trashedAt", next.trashedAt).put("openedAt", next.openedAt).toString())
    }
    fun updatedAt(workspace: WeWorkspace): Long = maxOf(state(workspace).openedAt,
        workspace.container.lastModified(), workspace.transcript.lastModified(),
        File(workspace.container, "workspace/memory/pages/conversation.md").lastModified(),
        File(workspace.container, "turns").listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0)
    /** Pinned first in every view; search is case insensitive and never changes stored names. */
    fun query(scope: String = "active", text: String = "", sort: String = "recent"): List<WeWorkspace> {
        val candidates = all().filter { w ->
            val s = state(w)
            val visible = when (scope) { "trash" -> s.trashedAt > 0; "archived" -> s.trashedAt == 0L && s.archived
                "all" -> s.trashedAt == 0L; else -> s.trashedAt == 0L && !s.archived }
            visible && w.name.contains(text.trim(), ignoreCase = true)
        }
        val pinned = candidates.associate { it.id to state(it).pinned }
        val updated = candidates.associate { it.id to updatedAt(it) }
        return candidates.sortedWith(compareByDescending<WeWorkspace> { pinned.getValue(it.id) }
            .thenComparator { a, b -> when (sort) {
                "name" -> a.name.compareTo(b.name, ignoreCase = true)
                "oldest" -> updated.getValue(a.id).compareTo(updated.getValue(b.id))
                else -> updated.getValue(b.id).compareTo(updated.getValue(a.id))
            } }.thenBy { it.id })
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
        private val managementLock = Any()
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
