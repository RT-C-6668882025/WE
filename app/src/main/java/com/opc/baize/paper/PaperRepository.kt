package com.opc.baize.paper

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Local files are separate: immutable original, editable vectors, derived model result. */
class PaperRepository(context: Context) : PaperStore {
    private val resolver = context.contentResolver
    private val root = File(context.filesDir, "papers").apply { mkdirs() }
    override fun importPdf(uri: Uri): String {
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
    override fun load(id: String, page: Int, width: Int, height: Int): PaperPage {
        val file = File(directory(id), "page-$page.ink.json")
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return PaperPage(width, height, 0, emptyList())
        val data = PaperPage.decode(atomic.openRead().bufferedReader().use { it.readText() })
        require(data.width == width && data.height == height) { "笔迹页尺寸不匹配，已停止加载以保护原文件" }
        return data
    }
    override fun save(id: String, page: Int, data: PaperPage) = write(File(directory(id), "page-$page.ink.json"), data.encode())
    override fun submission(id: String, page: Int, name: String, data: PaperPage) = write(File(directory(id), "$name.ink.json"), data.encode())
    override fun result(id: String, page: Int, revision: Long, region: String, answer: String, snapshotName: String) {
        val result = JSONObject().put("schema", 1).put("documentId", id).put("page", page).put("sourceRevision", revision)
            .put("sourceVectors", "$snapshotName.ink.json").put("region", JSONObject(region)).put("answer", answer).put("createdAt", System.currentTimeMillis())
        write(File(directory(id), "result-${java.util.UUID.randomUUID()}.json"), result.toString())
    }
    override fun results(id: String, page: Int): List<JSONObject> = directory(id).listFiles().orEmpty()
        .filter { it.name.startsWith("result-") && it.name.endsWith(".json") }
        .map { AtomicFile(it).openRead().bufferedReader().use { reader -> JSONObject(reader.readText()) } }
        .filter { it.getInt("page") == page }.sortedByDescending { it.getLong("createdAt") }.take(20)
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
}
