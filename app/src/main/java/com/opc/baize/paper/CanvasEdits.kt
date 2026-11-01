package com.opc.baize.paper

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Pure page-space edits, shared by live and unopened canvases. A batch commits once or not at all. */
object CanvasEdits {
    fun apply(original: PaperPage, operations: JSONArray): PaperPage {
        require(operations.toString().toByteArray().size<=512000) { "Edit batch limit 512 KB" }
        require(operations.length() in 1..100) { "Use 1..100 operations per batch" }
        var page = original
        for (i in 0 until operations.length()) {
            val op = operations.getJSONObject(i)
            val allowed=setOf("action","layer_id","points","width","color","brush","x","y","x2","y2","dx","dy","center_x","center_y","rotation","scale","duplicate","mirror","indices","name","visible","locked","opacity","blend","index")
            require(op.keys().asSequence().all {it in allowed}) { "Unknown operation field" }
            for(key in listOf("layer_id","index")) if(op.has(key)) require(op.get(key) is Int || op.get(key) is Long) { "$key must be an integer" }
            for(key in listOf("duplicate","mirror","visible","locked")) if(op.has(key)) require(op.get(key) is Boolean) { "$key must be boolean" }
            op.optJSONObject("brush")?.let {brush ->
                require(brush.keys().asSequence().all {it in setOf("hardness","opacity","spacing","smudge","pressure","mypaint")})
                for(key in listOf("pressure","mypaint")) if(brush.has(key)) require(brush.get(key) is Boolean)
                for(key in listOf("hardness","opacity","spacing","smudge")) if(brush.has(key)) require(brush.get(key) is Number)
            }
            for(key in listOf("action","name","color","blend")) if(op.has(key)) require(op.get(key) is String) { "$key must be text" }
            if(op.has("brush")) require(op.get("brush") is JSONObject)
            val action = op.getString("action")
            val layerId = op.optInt("layer_id", page.activeLayerId)
            fun layer() = page.layers.firstOrNull { it.id == layerId } ?: error("Layer not found")
            fun editable() { require(!layer().locked) { "Layer is locked" } }
            fun number(key: String, default: Float = 0f): Float {
                val value = if (op.has(key)) { require(op.get(key) is Number) { "$key must be numeric" }; op.getDouble(key).toFloat() } else default
                require(value.isFinite()) { "$key must be finite" }; return value
            }
            fun point(x: Float, y: Float, pressure: Float = 1f, time: Long = 0): InkPoint {
                require(x in 0f..page.width.toFloat() && y in 0f..page.height.toFloat() && pressure in 0f..1f && time >= 0) { "Point outside page or invalid pressure/time" }
                return InkPoint(x,y,pressure,time)
            }
            fun add(points: List<InkPoint>, erase: Boolean = false, fill: Boolean = false) {
                editable()
                val width = number("width",3f); require(width > 0 && width <= 1000)
                val colorText = op.optString("color","#000000"); require(colorText.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
                val brush = BrushSettings.decode(op.optJSONObject("brush")).copy(erase=erase)
                page = page.copy(strokes=page.strokes + PaperStroke(points,width,colorText.drop(1).toLong(16).toInt().let {if(colorText.length==7) it or 0xff000000.toInt() else it},DrawdanceBrush.ENGINE,layerId,brush,if(fill) "fill" else "stroke"))
            }
            when(action) {
                "stroke", "erase" -> {
                    val points = op.getJSONArray("points"); require(points.length() in 1..10000)
                    add((0 until points.length()).map { j -> val p=points.getJSONArray(j); require(p.length() in 2..4 && (0 until p.length()).all {p.get(it) is Number})
                        point(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),if(p.length()>2) p.getDouble(2).toFloat() else 1f,if(p.length()>3) p.getLong(3) else j*10L)
                    },erase=action=="erase")
                }
                "line", "rectangle", "ellipse" -> {
                    require(listOf("x","y","x2","y2").all {op.has(it)}) { "Shape requires x,y,x2,y2" }
                    val a=point(number("x"),number("y")); val b=point(number("x2"),number("y2"))
                    val points=when(action) {
                        "line" -> listOf(a,b)
                        "rectangle" -> listOf(a,a.copy(x=b.x),b,a.copy(y=b.y),a)
                        else -> (0..96).map { j -> val angle=j*PI*2/96
                            point((a.x+b.x)/2+cos(angle).toFloat()*abs(b.x-a.x)/2,(a.y+b.y)/2+sin(angle).toFloat()*abs(b.y-a.y)/2) }
                    }; add(points.mapIndexed { j,p -> p.copy(time=j*10L) })
                }
                "fill" -> add(listOf(point(0f,0f)),fill=true)
                "add_layer" -> {
                    require(page.layers.size<32); val id=op.optInt("layer_id",page.layers.maxOf {it.id}+1)
                    require(page.layers.none {it.id==id}); val name=op.optString("name","图层 $id"); require(name.isNotBlank())
                    page=page.copy(layers=page.layers+PaperLayer(id,name),activeLayerId=id)
                }
                "update_layer" -> {
                    val old=layer()
                    for (key in listOf("visible","locked")) if(op.has(key)) require(op.get(key) is Boolean)
                    val updated=old.copy(name=op.optString("name",old.name),visible=op.optBoolean("visible",old.visible),locked=op.optBoolean("locked",old.locked),opacity=number("opacity",old.opacity),blend=op.optString("blend",old.blend))
                    require(updated.name.isNotBlank()); page=page.copy(layers=page.layers.map {if(it.id==layerId) updated else it})
                }
                "select_layer" -> { layer(); page=page.copy(activeLayerId=layerId) }
                "delete_layer" -> {
                    editable(); require(page.layers.size>1) { "Cannot delete the last layer; use clear_layer" }
                    val layers=page.layers.filterNot {it.id==layerId}
                    page=page.copy(layers=layers,strokes=page.strokes.filterNot {it.layerId==layerId},activeLayerId=if(page.activeLayerId==layerId) layers.last().id else page.activeLayerId)
                }
                "reorder_layer" -> {
                    layer(); val index=op.getInt("index"); require(index in page.layers.indices)
                    val layers=page.layers.toMutableList(); val item=layers.removeAt(layers.indexOfFirst {it.id==layerId}); layers.add(index,item); page=page.copy(layers=layers)
                }
                "duplicate_layer" -> {
                    val old=layer(); require(page.layers.size<32); val id=page.layers.maxOf {it.id}+1
                    page=page.copy(layers=page.layers+old.copy(id=id,name=(old.name+" 副本").take(120),locked=false),strokes=page.strokes+page.strokes.filter {it.layerId==layerId}.map {it.copy(layerId=id)},activeLayerId=id)
                }
                "clear_layer" -> { editable(); page=page.copy(strokes=page.strokes.filterNot {it.layerId==layerId}) }
                "transform", "delete_strokes" -> {
                    editable(); val raw=op.getJSONArray("indices"); require(raw.length() in 1..10000)
                    val indices=(0 until raw.length()).map { require(raw.get(it) is Int || raw.get(it) is Long); raw.getInt(it) }.toSet()
                    require(indices.all {it in page.strokes.indices && page.strokes[it].layerId==layerId}) { "Invalid stroke index/layer; read again" }
                    val duplicate=op.optBoolean("duplicate",false)
                    val scale=number("scale",1f); require(scale>0 && scale<=100)
                    val angle=number("rotation")*PI/180; val dx=number("dx"); val dy=number("dy")
                    val cx=number("center_x",page.width/2f); val cy=number("center_y",page.height/2f)
                    fun transform(stroke: PaperStroke)=stroke.copy(width=(stroke.width*scale).coerceIn(.1f,1000f),points=stroke.points.map { p ->
                        val x=(p.x-cx)*scale*(if(op.optBoolean("mirror")) -1 else 1); val y=(p.y-cy)*scale
                        point((cx+x*cos(angle)-y*sin(angle)+dx).toFloat(),(cy+x*sin(angle)+y*cos(angle)+dy).toFloat(),p.pressure,p.time)
                    })
                    val selected=page.strokes.filterIndexed {j,_ -> j in indices}
                    page=page.copy(strokes=if(action=="delete_strokes") page.strokes.filterIndexed {j,_ -> j !in indices}
                        else if(duplicate) page.strokes+selected.map(::transform)
                        else page.strokes.mapIndexed {j,stroke -> if(j in indices) transform(stroke) else stroke})
                }
                else -> error("Unsupported canvas action: $action")
            }
            require(page.strokes.size<=100000 && page.strokes.sumOf {it.points.size}<=1000000) { "Canvas capacity exceeded" }
        }
        // Full validation before the first effect, including sizes, layer IDs and brush values.
        return PaperPage.decode(page.encode())
    }
}
