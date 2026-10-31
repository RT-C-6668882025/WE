package com.opc.baize.mcp

import android.content.Context
import android.graphics.*
import android.util.AtomicFile
import android.util.Base64
import com.opc.baize.agent.*
import com.opc.baize.paper.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Same executor for local HTTP and relay. No OAuth credential or arbitrary command tools. */
class WorkspaceMcpTools(private val context: Context,
    private val render: (File, File) -> Unit = { s, p -> LearningPageRenderer.render(s, p, true) }) {
    private val files = WorkspaceFiles(context)
    private val receipts = File(context.noBackupFilesDir, "we-mcp/operations").apply { mkdirs() }
    val definitions: JSONArray get() = JSONArray(context.assets.open("we-mcp-tools.json").bufferedReader().use { it.readText() })
    companion object {
        private val lock = Any()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
        }
    }
    fun execute(name: String, args: JSONObject): JSONObject = synchronized(lock) {
        val definition = (0 until definitions.length()).map { definitions.getJSONObject(it) }.firstOrNull { it.getString("name") == name }
            ?: return@synchronized failure("Unknown tool")
        val schema = definition.getJSONObject("inputSchema")
        val props = schema.getJSONObject("properties")
        if (args.keys().asSequence().any { !props.has(it) }) return@synchronized failure("Unknown argument")
        val required = schema.getJSONArray("required")
        if ((0 until required.length()).any { !args.has(required.getString(it)) }) return@synchronized failure("Missing argument")
        for (key in args.keys()) {
            val type = props.getJSONObject(key).getString("type")
            val value = args.get(key)
            if ((type == "string" && value !is String) || (type == "integer" && value !is Int && value !is Long))
                return@synchronized failure("Invalid argument type: $key")
        }
        if (name == "get_operation_result") return@synchronized receipt(args.getString("request_id"))
        val mutation = !definition.getJSONObject("annotations").getBoolean("readOnlyHint")
        if (!mutation) return@synchronized try { success(call(name, args)) } catch (e: Exception) { failure(e.message ?: "Read failed") }
        val id = args.getString("operation_id")
        if (!id.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) return@synchronized failure("Invalid operation_id")
        val fingerprint = sha((name + canonical(args)).toByteArray())
        val path = File(receipts, "$id.json")
        if (path.exists() || File(path.path + ".bak").exists()) {
            val old = JSONObject(AtomicFile(path).openRead().bufferedReader().use { it.readText() })
            return@synchronized if (old.getString("fingerprint") != fingerprint) failure("operation_id conflict") else old
        }
        // Persist intent BEFORE any effect. If killed mid-write, retries report uncertain,
        // never replay a create or overwrite. Atomic file saves protect each artifact.
        val record = JSONObject().put("request_id", id).put("fingerprint", fingerprint).put("status", "running")
            .put("data_changed", "unknown").put("message", "Execution not confirmed. Inspect workspace before issuing a new operation.")
        LearningTurn.write(path, record.toString())
        val result = try { success(call(name, args)).put("request_id", id).put("fingerprint", fingerprint) }
            catch (e: Exception) { failure(e.message ?: "Write failed").put("request_id", id).put("fingerprint", fingerprint).put("data_changed", "unknown") }
        LearningTurn.write(path, result.toString()); result
    }
    private fun success(value: JSONObject) = JSONObject().put("status", "succeeded").put("result", value)
    private fun failure(message: String) = JSONObject().put("status", "failed").put("error", message.take(240)).put("data_changed", false)
    private fun receipt(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Invalid request_id" }
        val path = File(receipts, "$id.json")
        return try { JSONObject(AtomicFile(path).openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { JSONObject().put("status", "not_found").put("data_changed", "unknown") }
    }
    private fun workspace(args: JSONObject): WeWorkspace {
        val id = args.getString("workspace_id")
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid workspace_id" }
        return files.list().firstOrNull { it.id == id } ?: error("Workspace not found")
    }
    private fun describe(w: WeWorkspace) = JSONObject().put("id", w.id).put("name", w.name)
        .put("pinned", files.state(w).pinned).put("archived", files.state(w).archived)
        .put("latest_page", LearningTurn.latestReady(w)?.let { "/memory/pages/${it.name}" } ?: JSONObject.NULL)
    private fun file(w: WeWorkspace, path: String): File {
        val relative = path.removePrefix("/")
        require(relative.startsWith("memory/") || relative.startsWith(".agents/skills/")) { "Only memory/ and .agents/skills/ are exposed" }
        require(relative.split('/').none { it.isBlank() || it == "." || it == ".." } && '\\' !in relative && relative.none { it.code < 32 }) { "Invalid path" }
        val root = w.root.canonicalFile
        val target = File(root, relative)
        var part: File? = target
        while (part != null && part != root) {
            require(!java.nio.file.Files.isSymbolicLink(part.toPath())) { "Linked paths are not exposed" }
            part = part.parentFile
        }
        require(target.canonicalPath.startsWith(root.path + "/")) { "Path escaped workspace" }
        return target
    }
    private fun call(name: String, a: JSONObject): JSONObject = when (name) {
        "list_workspaces" -> {
            val scope = a.optString("scope", "all"); require(scope in setOf("active", "archived", "all"))
            JSONObject().put("workspaces", JSONArray(files.query(scope, a.optString("query")).map(::describe)))
        }
        "manage_workspace" -> {
            val w = workspace(a); require(w.name == a.getString("expected_name")) { "Workspace name changed; read again" }
            val action = a.getString("action"); require(action in setOf("pin", "unpin", "archive", "unarchive")) { "Unsupported management action" }
            files.manage(w, action); describe(w)
        }
        "create_workspace" -> describe(files.create(a.getString("name")))
        "rename_workspace" -> {
            val w = workspace(a); require(w.name == a.getString("expected_name")) { "Workspace name changed; read again" }
            files.rename(w, a.getString("name")); describe(w.copy(name = a.getString("name").trim()))
        }
        "list_files" -> {
            val w = workspace(a); val offset = a.optInt("offset", 0); require(offset >= 0)
            val all = listOf(File(w.root, "memory"), File(w.root, ".agents/skills")).flatMap { base ->
                base.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.toList()
            }.sortedBy { it.path }
            JSONObject().put("files", JSONArray(all.drop(offset).take(200).map {
                JSONObject().put("path", "/" + it.relativeTo(w.root).invariantSeparatorsPath).put("bytes", it.length())
            })).put("next_offset", if (offset + 200 < all.size) offset + 200 else JSONObject.NULL)
        }
        "read_file" -> {
            val f = file(workspace(a), a.getString("path")); require(f.isFile && f.length() <= 256000 && f.extension.lowercase() in setOf("md", "txt", "json", "csv", "yaml", "yml")) { "Use read_page for PDFs; text limit 256 KB" }
            val bytes = f.readBytes(); val text = bytes.toString(Charsets.UTF_8)
            val offset = a.optInt("offset", 0); val limit = a.optInt("limit", 12000); require(offset in 0..text.length && limit in 1..48000)
            val end = minOf(text.length, offset + limit)
            JSONObject().put("text", text.substring(offset, end)).put("sha256", sha(bytes)).put("next_offset", if (end < text.length) end else JSONObject.NULL)
        }
        "write_file" -> {
            val w = workspace(a); val f = file(w, a.getString("path")); val text = a.getString("content")
            require(text.toByteArray().size <= 256000 && f.extension.lowercase() in setOf("md", "txt", "json", "csv", "yaml", "yml")) { "Text limit 256 KB" }
            require(!f.name.startsWith("turn-") && f.name != "conversation.md") { "Learning pages and conversation snapshots are immutable; publish a new page" }
            if (f.exists()) require(f.isFile && f.length() <= 256000 && a.optString("expected_sha256") == sha(f.readBytes())) { "File changed or expected_sha256 missing; read again" }
            else require(!a.has("expected_sha256")) { "File no longer exists" }
            LearningTurn.write(f, text); JSONObject().put("path", a.getString("path")).put("sha256", sha(text.toByteArray()))
        }
        "publish_learning_page" -> {
            val w = workspace(a); val text = a.getString("content")
            require(text.isNotBlank() && text.toByteArray().size <= 128000) { "Page text limit 128 KB" }
            val source = a.optString("source_path").takeIf { it.isNotBlank() }
            if (source != null) file(w, source)
            val turn = LearningTurn.prepare(w, a.getString("request"), source, "外部 MCP Agent")
            turn.publishExternal(text, render)
            JSONObject().put("path", "/memory/pages/${turn.pdf.name}").put("source_path", "/memory/pages/${turn.source.name}")
        }
        "read_page" -> readPage(workspace(a), a)
        else -> error("Unsupported tool")
    }
    private fun readPage(w: WeWorkspace, a: JSONObject): JSONObject {
        val pdf = file(w, a.getString("path")); require(pdf.isFile && pdf.extension == "pdf" && pdf.length() <= 100L * 1024 * 1024) { "PDF not found or too large" }
        val repository = PaperRepository(context)
        val id = repository.importPdf(android.net.Uri.fromFile(pdf))
        return AndroidPdfEngine(pdf).use { engine ->
            val page = a.optInt("page", 0); require(page in 0 until engine.pageCount) { "Invalid page index" }
            val rendered = engine.render(page)
            try {
                val ink = repository.load(id, page, rendered.width, rendered.height)
                val canvas = Canvas(rendered.bitmap)
                canvas.scale(rendered.bitmap.width.toFloat() / rendered.width, rendered.bitmap.height.toFloat() / rendered.height)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 2.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                for (stroke in ink.strokes) {
                    paint.strokeWidth = stroke.width
                    val path = Path(); stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }; canvas.drawPath(path, paint)
                }
                val bytes = java.io.ByteArrayOutputStream(); rendered.bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                require(bytes.size() <= 450000) { "Image too large" }
                val data = JSONObject().put("document_id", id).put("page_count", engine.pageCount).put("page", page)
                    .put("ink", JSONObject(ink.encode())).put("feedback", JSONArray(repository.results(id, page)))
                    .put("content", JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))))
                require(data.toString().toByteArray().size <= 900000) { "Page feedback too large; use a smaller document" }
                data
            } finally { rendered.bitmap.recycle() }
        }
    }
}
