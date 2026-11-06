package com.opc.baize.agent

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import android.util.AtomicFile

/** Drawing identity is independent of the latest AI learning page and conversation view. */
object WorkspaceDrawing {
    data class Document(val pdf: File,val sourcePath: String)
    internal fun blankContent(): JSONObject = JSONObject().put("we_page",1).put("mode","drawing").put("title","绘画")
        .put("blocks",JSONArray().put(JSONObject().put("id","canvas").put("type","drawing").put("height",500).put("grid","blank")))
    /** Resolve the actual editable source, including nested drawing pages and imported PDFs. */
    fun sourcePath(workspace: WeWorkspace, pdf: File): String? {
        val file = pdf.canonicalFile
        if (!file.path.startsWith(workspace.root.canonicalPath + "/memory/pages/")) return null
        return listOf("json", "md", "txt").map { File(file.parentFile,"${file.nameWithoutExtension}.$it") }
            .firstOrNull { it.isFile && it.canonicalPath.startsWith(workspace.root.canonicalPath + "/memory/pages/") }?.let { "/" + it.relativeTo(workspace.root).path }
    }
    /** Durable inbox: a page created/edited while the app is paused is still discoverable. */
    fun announce(workspace: WeWorkspace, pdf: File) {
        LearningTurn.write(File(workspace.container,"ready-canvas.json"),JSONObject()
            .put("path", "/"+pdf.relativeTo(workspace.root).invariantSeparatorsPath).toString())
        WorkspacePageEvents.publish(workspace.id,pdf)
        WorkspaceEvents.publish()
    }
    fun pending(workspace: WeWorkspace): File? = runCatching {
        val value=JSONObject(AtomicFile(File(workspace.container,"ready-canvas.json")).openRead().bufferedReader().use {it.readText()})
        val pdf=File(workspace.root,value.getString("path").removePrefix("/")).canonicalFile
        pdf.takeIf {it.isFile && it.extension=="pdf" && it.path.startsWith(workspace.root.canonicalPath+"/memory/pages/")}
    }.getOrNull()
    @Synchronized fun open(workspace: WeWorkspace,newDrawing: Boolean=false,title: String="空白页"): Document {
        val directory=File(workspace.root,"memory/pages/drawings").apply {mkdirs()}
        val pointer=AtomicFile(File(workspace.container,"active-drawing.json"))
        if(!newDrawing) {
            val saved=runCatching {JSONObject(pointer.openRead().bufferedReader().use {it.readText()})}.getOrNull()
            val legacyPath=saved?.optString("legacy_pdf")?.takeIf {it.isNotBlank()}
            if(legacyPath!=null) {
                val pdf=File(legacyPath).canonicalFile
                if(pdf.isFile && pdf.path.startsWith(workspace.root.canonicalPath+"/memory/pages/"))
                    return Document(pdf,LearningTurn.sourcePath(pdf))
            }
            val id=saved?.optString("id")
            if(id!=null && runCatching {UUID.fromString(id)}.isSuccess) {
                val pdf=File(directory,"$id.pdf");val source=File(directory,"$id.json")
                if(pdf.isFile && source.isFile) return Document(pdf,"/"+source.relativeTo(workspace.root).path)
            }
            // Adopt the former drawing entry once; never substitute an unrelated learning page.
            val legacy=LearningTurn.latestReady(workspace)
            if(legacy!=null && LearningPageRenderer.pageMap(legacy,0)?.optString("mode")=="drawing") {
                LearningTurn.write(File(workspace.container,"active-drawing.json"),JSONObject().put("legacy_pdf",legacy.path).toString())
                return Document(legacy,LearningTurn.sourcePath(legacy))
            }
        }
        val id=UUID.randomUUID().toString();val source=File(directory,"$id.json");val pdf=File(directory,"$id.pdf")
        val content=blankContent().put("title",title)
        try {
            LearningTurn.write(source,content.toString());LearningPageRenderer.render(source,pdf,true)
            // A valid trailing PDF comment gives each independent drawing a distinct content identity.
            pdf.appendText("\n% WE drawing $id\n")
            val stream=pointer.startWrite()
            try {stream.write(JSONObject().put("id",id).toString().toByteArray());pointer.finishWrite(stream)}
            catch(e:Exception) {pointer.failWrite(stream);throw e}
            return Document(pdf,"/"+source.relativeTo(workspace.root).path)
        } catch(e:Exception) {source.delete();pdf.delete();throw e}
    }
}
