package com.opc.baize.mcp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import com.opc.baize.agent.*
import com.opc.baize.paper.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Uses the same page vectors, renderer and persistent history as the app's canvas. */
class CanvasMcpTools(private val context: Context) {
    private val files=WorkspaceFiles(context)
    private val repository=PaperRepository(context)
    private fun workspace(a:JSONObject):WeWorkspace = files.list().firstOrNull {it.id==a.getString("workspace_id")} ?: error("Workspace not found")
    private fun path(w:WeWorkspace,path:String):File {
        val relative=path.removePrefix("/")
        require(relative.startsWith("memory/pages/") && relative.split('/').none {it.isBlank() || it=="." || it==".."} && '\\' !in relative && relative.none {it.code<32})
        val file=File(w.root,relative)
        var part:File?=file
        while(part!=null && part!=w.root) {require(!java.nio.file.Files.isSymbolicLink(part.toPath())) {"Linked pages are not exposed"};part=part.parentFile}
        require(file.canonicalPath.startsWith(w.root.canonicalPath+"/memory/pages/") && file.isFile && file.extension=="pdf" && file.length()<=100L*1024*1024)
        return file
    }
    private fun describe(w:WeWorkspace,pdf:File)=JSONObject().put("path","/"+pdf.relativeTo(w.root).invariantSeparatorsPath)
        .put("name",pdf.name).put("source_path",WorkspaceDrawing.sourcePath(w,pdf) ?: JSONObject.NULL).put("updated_at",pdf.lastModified())
    fun call(name:String,a:JSONObject):JSONObject {
        val w=workspace(a)
        if(name=="list_canvases") {
            val offset=a.optInt("offset",0);require(offset>=0)
            val pages=File(w.root,"memory/pages").walkTopDown().onEnter {!java.nio.file.Files.isSymbolicLink(it.toPath())}
                .filter {it.isFile && it.extension=="pdf" && !java.nio.file.Files.isSymbolicLink(it.toPath())}.sortedBy {it.path}.toList()
            return JSONObject().put("canvases",JSONArray(pages.drop(offset).take(100).map {describe(w,it)}))
                .put("next_offset",if(offset+100<pages.size) offset+100 else JSONObject.NULL)
        }
        if(name=="create_canvas") {
            val title=a.getString("title"); require(title.isNotBlank() && title.length<=120)
            val page=WorkspaceDrawing.open(w,true,title)
            WorkspacePageEvents.publish(w.id,page.pdf)
            return describe(w,page.pdf).put("ready_to_open",true).put("revision",0)
        }
        if(name=="get_canvas_context" && !a.has("path")) return JSONObject().put("active",CanvasSession.context(w.id) ?: JSONObject.NULL)
        val pdf=path(w,a.getString("path"));val index=a.optInt("page",0);val id=repository.importPdf(Uri.fromFile(pdf))
        return AndroidPdfEngine(pdf).use {engine ->
            require(index in 0 until engine.pageCount) {"Invalid page index"}
            val rendered=engine.render(index)
            try {
                fun saved()=repository.load(id,index,rendered.width,rendered.height)
                fun metadata(page:PaperPage)=describe(w,pdf).put("document_id",id).put("page",index).put("page_count",engine.pageCount)
                    .put("revision",page.revision).put("width",page.width).put("height",page.height)
                    .put("layers",JSONObject(page.encode()).getJSONArray("layers")).put("active_layer",page.activeLayerId).put("stroke_count",page.strokes.size)
                when(name) {
                    "get_canvas_context" -> metadata(saved()).put("active",CanvasSession.context(w.id) ?: JSONObject.NULL)
                    "edit_canvas", "canvas_history" -> {
                        val expected=a.getLong("expected_revision");require(expected>=0)
                        val change:(PaperPage)->PaperPage={ current ->
                            require(current.revision==expected) {"Revision conflict; read canvas again"}
                            if(name=="edit_canvas") repository.edit(id,index,rendered.width,rendered.height,expected) {CanvasEdits.apply(it,a.getJSONArray("operations"))}
                            else {
                                val action=a.getString("action");require(action=="undo" || action=="redo")
                                repository.history(id,index,rendered.width,rendered.height,expected,action=="redo")
                            }
                        }
                        val updated=CanvasSession.change(w.id,id,index,expected,change) {change(saved())}
                        metadata(updated).put("saved",true)
                    }
                    "export_canvas" -> {
                        val data=saved()
                        if(a.has("expected_revision")) require(a.getLong("expected_revision")==data.revision) {"Revision conflict; read again"}
                        val transparent=a.optBoolean("transparent",false)
                        val bitmap=Bitmap.createBitmap(rendered.bitmap.width,rendered.bitmap.height,Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).apply {
                                if(!transparent) {drawColor(Color.WHITE);drawBitmap(rendered.bitmap,0f,0f,null)}
                                scale(bitmap.width.toFloat()/data.width,bitmap.height.toFloat()/data.height)
                                LayerRenderer().use {it.draw(this,data)}
                            }
                            val directory=File(w.root,"memory/exports").apply {mkdirs()}
                            val output=File(directory,"canvas-${UUID.randomUUID()}.png")
                            val bytes=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))
                            val payload=bytes.toByteArray();require(payload.size<=16*1024*1024) {"Export too large"}
                            val atomic=android.util.AtomicFile(output);val stream=atomic.startWrite()
                            try {stream.write(payload);atomic.finishWrite(stream)} catch(e:Exception) {atomic.failWrite(stream);throw e}
                            val result=metadata(data).put("export_path","/"+output.relativeTo(w.root).invariantSeparatorsPath)
                                .put("mime_type","image/png").put("sha256",WorkspaceMcpTools.sha(payload)).put("transparent",transparent)
                            if(a.optBoolean("include_image",true)) {
                                var edge=768
                                while(edge>=96) {
                                    val ratio=minOf(1f,edge.toFloat()/maxOf(bitmap.width,bitmap.height))
                                    val preview=Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt().coerceAtLeast(1),(bitmap.height*ratio).toInt().coerceAtLeast(1),true)
                                    val encoded=ByteArrayOutputStream()
                                    try {check(preview.compress(if(transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,80,encoded))}
                                    finally {if(preview!==bitmap) preview.recycle()}
                                    if(encoded.size()<=400000) {
                                        result.put("content",JSONArray().put(JSONObject().put("type","image").put("mimeType",if(transparent) "image/png" else "image/jpeg")
                                            .put("data",Base64.encodeToString(encoded.toByteArray(),Base64.NO_WRAP))))
                                        break
                                    }; edge/=2
                                }
                            };result
                        } finally {bitmap.recycle()}
                    }
                    else -> error("Unknown canvas tool")
                }
            } finally {rendered.bitmap.recycle()}
        }
    }
}
