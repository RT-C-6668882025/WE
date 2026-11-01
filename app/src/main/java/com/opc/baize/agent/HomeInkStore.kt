package com.opc.baize.agent

import android.util.AtomicFile
import com.opc.baize.InkView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Raw home ink belongs to the task, independently of drawing documents and model replies. */
object HomeInkStore {
    data class Draft(val width: Int,val height: Int,val strokes: List<List<InkView.Pt>>) {
        fun encode(): String=JSONObject().put("schema",1).put("width",width).put("height",height)
            .put("strokes",JSONArray().apply {strokes.forEach {s -> put(JSONArray().apply {
                s.forEach {p -> put(JSONArray(listOf(p.x,p.y,p.w,p.t)))}
            })}}).toString()
        companion object {
            fun decode(text: String): Draft {
                val value=JSONObject(text);require(value.getInt("schema")==1)
                val width=value.getInt("width");val height=value.getInt("height");require(width>0 && height>0)
                val raw=value.getJSONArray("strokes");require(raw.length()<=10000);var total=0
                return Draft(width,height,(0 until raw.length()).map {i ->
                    val points=raw.getJSONArray(i);total+=points.length();require(total<=1000000)
                    (0 until points.length()).map {j -> val p=points.getJSONArray(j)
                        val x=p.getDouble(0).toFloat();val y=p.getDouble(1).toFloat();val w=p.getDouble(2).toFloat()
                        require(x.isFinite() && y.isFinite() && w.isFinite() && w in .001f..10000f && kotlin.math.abs(x)<=1000000f && kotlin.math.abs(y)<=1000000f)
                        InkView.Pt(x,y,w,p.getLong(3))
                    }
                })
            }
        }
    }
    private fun directory(workspace: WeWorkspace)=File(workspace.root,"memory/handwriting").apply {mkdirs()}
    fun save(workspace: WeWorkspace,draft: Draft) {
        val path=File(directory(workspace),"home-draft.json")
        if(draft.strokes.isEmpty()) AtomicFile(path).delete() else LearningTurn.write(path,draft.encode())
    }
    fun load(workspace: WeWorkspace): Draft? = runCatching {
        val file=AtomicFile(File(directory(workspace),"home-draft.json"))
        file.openRead().bufferedReader().use {Draft.decode(it.readText())}
    }.getOrNull()
    fun archive(workspace: WeWorkspace,draft: Draft) {
        if(draft.strokes.isNotEmpty()) LearningTurn.write(File(directory(workspace),"input-${UUID.randomUUID()}.json"),draft.encode())
    }
}
