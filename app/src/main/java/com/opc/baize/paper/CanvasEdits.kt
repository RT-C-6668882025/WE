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
            val allowed=setOf("action","layer_id","points","width","color","brush","x","y","x2","y2","dx","dy","center_x","center_y","rotation","scale","duplicate","mirror","indices","name","visible","locked","opacity","blend","index","filled","bounds")
            require(op.keys().asSequence().all {it in allowed}) { "Unknown operation field" }
            for(key in listOf("layer_id","index")) if(op.has(key)) require(op.get(key) is Int || op.get(key) is Long) { "$key must be an integer" }
            for(key in listOf("duplicate","mirror","visible","locked","filled")) if(op.has(key)) require(op.get(key) is Boolean) { "$key must be boolean" }
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
            fun add(points: List<InkPoint>, erase: Boolean = false, fill: Boolean = false, polygon: Boolean = false) {
                editable()
                val width = number("width",3f); require(width > 0 && width <= 1000)
                val colorText = op.optString("color","#000000"); require(colorText.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
                val brush = BrushSettings.decode(op.optJSONObject("brush")).copy(erase=erase)
                page = page.copy(strokes=page.strokes + PaperStroke(points,width,colorText.drop(1).toLong(16).toInt().let {if(colorText.length==7) it or 0xff000000.toInt() else it},DrawdanceBrush.ENGINE,layerId,brush,if(fill) "fill" else if(polygon) "polygon" else "stroke"))
            }
            when(action) {
                "stroke", "erase" -> {
                    val points = op.getJSONArray("points"); require(points.length() in 1..10000)
                    add((0 until points.length()).map { j -> val p=points.getJSONArray(j); require(p.length() in 2..4 && (0 until p.length()).all {p.get(it) is Number})
                        point(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),if(p.length()>2) p.getDouble(2).toFloat() else 1f,if(p.length()>3) p.getLong(3) else j*10L)
                    },erase=action=="erase")
                }
                "polygon", "bezier" -> {
                    val raw=op.getJSONArray("points")
                    require(raw.length() in 3..10000 && (action!="bezier" || raw.length()==4))
                    val points=(0 until raw.length()).map { j ->
                        val p=raw.getJSONArray(j);require(p.length()==2 && p.get(0) is Number && p.get(1) is Number)
                        point(p.getDouble(0).toFloat(),p.getDouble(1).toFloat())
                    }
                    val curve=if(action=="bezier") (0..96).map { j ->
                        val t=j/96f;val u=1-t
                        point(u*u*u*points[0].x+3*u*u*t*points[1].x+3*u*t*t*points[2].x+t*t*t*points[3].x,
                            u*u*u*points[0].y+3*u*u*t*points[1].y+3*u*t*t*points[2].y+t*t*t*points[3].y)
                    } else points+points.first()
                    require(action!="bezier" || !op.optBoolean("filled")) { "A bezier is an open curve" }
                    add(curve.mapIndexed { j,p -> p.copy(time=j*10L) },polygon=op.optBoolean("filled"))
                }
                "line", "rectangle", "ellipse" -> {
                    require(listOf("x","y","x2","y2").all {op.has(it)}) { "Shape requires x,y,x2,y2" }
                    val a=point(number("x"),number("y")); val b=point(number("x2"),number("y2"))
                    val points=when(action) {
                        "line" -> listOf(a,b)
                        "rectangle" -> listOf(a,a.copy(x=b.x),b,a.copy(y=b.y),a)
                        else -> (0..96).map { j -> val angle=j*PI*2/96
                            point((a.x+b.x)/2+cos(angle).toFloat()*abs(b.x-a.x)/2,(a.y+b.y)/2+sin(angle).toFloat()*abs(b.y-a.y)/2) }
                    }
                    require(action!="line" || !op.optBoolean("filled")) { "A line is an open path" }
                    add(points.mapIndexed { j,p -> p.copy(time=j*10L) },polygon=op.optBoolean("filled"))
                }
                "fill" -> {
                    require(op.has("x")==op.has("y")) { "Fill requires both x and y" }
                    add(listOf(point(number("x"),number("y"))),fill=true)
                }
                "add_layer" -> {
                    require(page.layers.size<32); val id=op.optInt("layer_id",page.layers.maxOf {it.id}+1)
                    require(page.layers.none {it.id==id}); val name=op.optString("name","图层 $id"); require(name.isNotBlank())
                    page=page.copy(layers=page.layers+PaperLayer(id,name),activeLayerId=id)
                }
                "update_layer" -> {
                    val old=layer()
                    for (key in listOf("visible","locked","filled")) if(op.has(key)) require(op.get(key) is Boolean)
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
                "transform", "delete_strokes", "restyle" -> {
                    editable()
                    require(!(op.has("indices") && op.has("bounds"))) { "Use indices or bounds, not both" }
                    val indices=if(op.has("indices")) {
                        val raw=op.getJSONArray("indices");require(raw.length() in 1..10000)
                        (0 until raw.length()).map {require(raw.get(it) is Int || raw.get(it) is Long);raw.getInt(it)}.toSet()
                    } else {
                        val bounds=op.optJSONArray("bounds")
                        if(bounds!=null) {
                            require(bounds.length()==4 && (0..3).all {bounds.get(it) is Number && bounds.getDouble(it).isFinite()})
                            require(bounds.getDouble(0)<=bounds.getDouble(2) && bounds.getDouble(1)<=bounds.getDouble(3))
                        }
                        page.strokes.indices.filter { j -> val stroke=page.strokes[j]
                            stroke.layerId==layerId && (bounds==null || stroke.points.any {p ->
                                p.x.toDouble() in bounds.getDouble(0)..bounds.getDouble(2) && p.y.toDouble() in bounds.getDouble(1)..bounds.getDouble(3)
                            })
                        }.toSet()
                    }
                    require(indices.isNotEmpty()) { "No strokes selected" }
                    require(indices.all {it in page.strokes.indices && page.strokes[it].layerId==layerId}) { "Invalid stroke index/layer; read again" }
                    val duplicate=op.optBoolean("duplicate",false)
                    val scale=number("scale",1f); require(scale>0 && scale<=100)
                    val angle=number("rotation")*PI/180; val dx=number("dx"); val dy=number("dy")
                    val cx=number("center_x",page.width/2f); val cy=number("center_y",page.height/2f)
                    fun transform(stroke: PaperStroke)=stroke.copy(width=(stroke.width*scale).coerceIn(.1f,1000f),points=stroke.points.map { p ->
                        val x=(p.x-cx)*scale*(if(op.optBoolean("mirror")) -1 else 1); val y=(p.y-cy)*scale
                        point((cx+x*cos(angle)-y*sin(angle)+dx).toFloat(),(cy+x*sin(angle)+y*cos(angle)+dy).toFloat(),p.pressure,p.time)
                    })
                    if(action=="restyle") {
                        require(listOf("color","width","brush").any {op.has(it)}) { "Provide color, width or brush" }
                        val color=op.optString("color")
                        if(op.has("color")) require(color.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))
                        val width=number("width",1f);require(width>0 && width<=1000)
                        page=page.copy(strokes=page.strokes.mapIndexed { j,stroke -> if(j !in indices) stroke else stroke.copy(
                            color=if(op.has("color")) color.drop(1).toLong(16).toInt().let {if(color.length==7) it or 0xff000000.toInt() else it} else stroke.color,
                            width=if(op.has("width")) width else stroke.width,
                            brush=if(op.has("brush")) {
                                val merged=stroke.brush.encode();val patch=op.getJSONObject("brush")
                                patch.keys().forEach {key -> merged.put(key,patch.get(key))};BrushSettings.decode(merged)
                            } else stroke.brush)
                        })
                        continue
                    }
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
