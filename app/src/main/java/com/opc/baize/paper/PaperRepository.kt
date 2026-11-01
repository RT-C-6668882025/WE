package com.opc.baize.paper

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Local files are separate: immutable original, editable vectors, derived model result. */
class PaperRepository(context: Context) : PaperStore {
    // MCP reads on its own worker while the writing screen saves on PaperIo.
    // AtomicFile.openRead must not restore a .bak while another instance writes.
    private companion object { val lock = Any() }
    private val resolver = context.contentResolver
    private val root = File(context.filesDir, "papers").apply { mkdirs() }
    override fun importPdf(uri: Uri): String = synchronized(lock) {
        val temp = File.createTempFile("import-", ".pdf", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取 PDF" }
                temp.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= 100L * 1024 * 1024) { "首版支持 100MB 以内 PDF" }
                        digest.update(buffer, 0, n); out.write(buffer, 0, n)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val dir = directory(id)
            val original = File(dir, "original.pdf")
            if (!original.exists()) check(temp.renameTo(original)) { "PDF 导入失败" }
            return id
        } finally { temp.delete() }
    }
    private fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}")))
        return File(root, id).apply { mkdirs() }
    }
    override fun pdf(id: String) = File(directory(id), "original.pdf")
    override fun load(id: String, page: Int, width: Int, height: Int): PaperPage = synchronized(lock) {
        val file = File(directory(id), "page-$page.ink.json")
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return PaperPage(width, height, 0, emptyList())
        val data = PaperPage.decode(atomic.openRead().bufferedReader().use { it.readText() })
        require(data.width == width && data.height == height) { "笔迹页尺寸不匹配，已停止加载以保护原文件" }
        return data
    }
    override fun save(id: String, page: Int, data: PaperPage) { synchronized(lock) {
        val old=load(id,page,data.width,data.height)
        if(data==old) return@synchronized
        require(data.revision==old.revision+1) { "Page changed; reload before saving" }
        commit(id,page,old,data)
    } }
    private fun state(id:String,page:Int): JSONObject {
        val file=File(directory(id),"page-$page.ink.json")
        return if(file.exists() || File(file.path+".bak").exists()) JSONObject(AtomicFile(file).openRead().bufferedReader().use {it.readText()}) else JSONObject()
    }
    private fun commit(id:String,page:Int,old:PaperPage,data:PaperPage,undo:org.json.JSONArray?=null,redo:org.json.JSONArray=org.json.JSONArray()) {
        val previous=state(id,page)
        val history=undo ?: (previous.optJSONArray("undo") ?: org.json.JSONArray()).put(JSONObject(old.encode()))
        // Bound persistent history independently from document size.
        val keep=(0 until history.length()).map {history.getJSONObject(it)}.toMutableList()
        while(keep.size>20 || (keep.size>1 && keep.sumOf {it.toString().length}>2000000)) keep.removeAt(0)
        val out=JSONObject(data.encode()).put("undo",org.json.JSONArray(keep)).put("redo",redo)
        write(File(directory(id),"page-$page.ink.json"),out.toString())
    }
    fun edit(id:String,page:Int,width:Int,height:Int,expected:Long,change:(PaperPage)->PaperPage): PaperPage = synchronized(lock) {
        val old=load(id,page,width,height); require(old.revision==expected) { "Revision conflict; read canvas again" }
        val next=change(old).copy(revision=old.revision+1)
        require(next.width==old.width && next.height==old.height)
        val checked=PaperPage.decode(next.encode()); commit(id,page,old,checked); checked
    }
    fun history(id:String,page:Int,width:Int,height:Int,expected:Long,redo:Boolean): PaperPage = synchronized(lock) {
        val old=load(id,page,width,height); require(old.revision==expected) { "Revision conflict; read canvas again" }
        val saved=state(id,page); val from=saved.optJSONArray(if(redo) "redo" else "undo") ?: org.json.JSONArray()
        require(from.length()>0) { "No history available" }
        val to=saved.optJSONArray(if(redo) "undo" else "redo") ?: org.json.JSONArray()
        val next=PaperPage.decode(from.getJSONObject(from.length()-1).toString()).copy(revision=old.revision+1)
        val rest=org.json.JSONArray((0 until from.length()-1).map {from.getJSONObject(it)})
        to.put(JSONObject(old.encode()))
        commit(id,page,old,next,if(redo) to else rest,if(redo) rest else to); next
    }
    override fun submission(id: String, page: Int, name: String, data: PaperPage) = write(File(directory(id), "$name.ink.json"), data.encode())
    override fun result(id: String, page: Int, revision: Long, region: String, answer: String, snapshotName: String) {
        val result = JSONObject().put("schema", 1).put("documentId", id).put("page", page).put("sourceRevision", revision)
            .put("sourceVectors", "$snapshotName.ink.json").put("region", JSONObject(region)).put("answer", answer).put("createdAt", System.currentTimeMillis())
        write(File(directory(id), "result-${java.util.UUID.randomUUID()}.json"), result.toString())
    }
    override fun results(id: String, page: Int): List<JSONObject> = synchronized(lock) { directory(id).listFiles().orEmpty()
        .filter { it.name.startsWith("result-") && it.name.endsWith(".json") }
        .map { AtomicFile(it).openRead().bufferedReader().use { reader -> JSONObject(reader.readText()) } }
        .filter { it.getInt("page") == page }.sortedByDescending { it.getLong("createdAt") }.take(20) }
    fun workspaceResults(context: Context, workspace: String): List<JSONObject> = synchronized(lock) {
        context.getSharedPreferences("moling",Context.MODE_PRIVATE).all.entries
            .filter { it.key.startsWith("paper_workspace_") && it.value == workspace }
            .map { it.key.removePrefix("paper_workspace_") }.filter { it.matches(Regex("[0-9a-f]{64}")) }
            .flatMap { id -> directory(id).listFiles().orEmpty().filter { it.name.startsWith("result-") && it.extension == "json" }
                .map { AtomicFile(it).openRead().bufferedReader().use { reader -> JSONObject(reader.readText()) } } }
            .sortedByDescending { it.getLong("createdAt") }.take(100)
    }
    private fun write(file: File, text: String) = synchronized(lock) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}
