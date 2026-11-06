package com.opc.baize.paper

import org.json.JSONArray
import org.json.JSONObject

/** Rendered PDF page space in points, independent of view pixels, zoom and pan. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
data class BrushSettings(
    val hardness: Float = .8f, val opacity: Float = 1f, val spacing: Float = .12f,
    val smudge: Float = 0f, val pressure: Boolean = true, val mypaint: Boolean = false,
    val erase: Boolean = false
) {
    init { require(listOf(hardness, opacity, spacing, smudge).all { it.isFinite() && it in 0f..1f }) }
    fun encode() = JSONObject().put("hardness", hardness).put("opacity", opacity).put("spacing", spacing)
        .put("smudge", smudge).put("pressure", pressure).put("mypaint", mypaint).put("erase", erase)
    companion object {
        val names = arrayOf("圆笔", "铅笔", "喷枪", "马克笔", "涂抹", "MyPaint 圆笔", "MyPaint 混色")
        val presets = listOf(BrushSettings(), BrushSettings(.95f,.45f,.08f), BrushSettings(.1f,.2f,.08f),
            BrushSettings(.8f,.35f,.08f,pressure=false), BrushSettings(.6f,1f,.08f,.95f),
            BrushSettings(.8f,mypaint=true), BrushSettings(.5f,.8f,.1f,.7f,mypaint=true))
        fun decode(o: JSONObject?) = if (o == null) BrushSettings() else BrushSettings(
            o.optDouble("hardness",.8).toFloat(),o.optDouble("opacity",1.0).toFloat(),o.optDouble("spacing",.12).toFloat(),
            o.optDouble("smudge",0.0).toFloat(),o.optBoolean("pressure",true),o.optBoolean("mypaint"),o.optBoolean("erase"))
    }
}
data class PaperStroke(val points: List<InkPoint>, val width: Float = 1.8f, val color: Int = -16777216,
    val engine: String = "vector", val layerId: Int = 1, val brush: BrushSettings = BrushSettings(), val operation: String = "stroke")
data class PaperLayer(val id: Int, val name: String, val visible: Boolean = true, val locked: Boolean = false,
    val opacity: Float = 1f, val blend: String = "normal") {
    init { require(id > 0 && name.length <= 120 && opacity.isFinite() && opacity in 0f..1f && blend in blends) }
    companion object { val blends = listOf("normal","multiply","screen","overlay","darken","lighten") }
}
data class PaperPage(val width: Int, val height: Int, val revision: Long, val strokes: List<PaperStroke>,
    val layers: List<PaperLayer> = listOf(PaperLayer(1,"图层 1")), val activeLayerId: Int = 1) {
    val activeLayer get() = layers.first { it.id == activeLayerId }
    fun encode(): String = JSONObject().put("schema", 1).put("space", "android-pdf-renderer-page-points")
        .put("width",width).put("height",height).put("revision",revision).put("active_layer",activeLayerId)
        .put("layers",JSONArray().apply { layers.forEach { put(JSONObject().put("id",it.id).put("name",it.name)
            .put("visible",it.visible).put("locked",it.locked).put("opacity",it.opacity).put("blend",it.blend)) } })
        .put("strokes",JSONArray().apply { strokes.forEach { stroke ->
            put(JSONObject().put("width",stroke.width).put("color",stroke.color).put("engine",stroke.engine)
                .put("layer",stroke.layerId).put("brush",stroke.brush.encode()).put("operation",stroke.operation).put("points",JSONArray().apply {
                    stroke.points.forEach { put(JSONArray(listOf(it.x,it.y,it.pressure,it.time))) }
                }))
        } }).toString()
    companion object {
        fun decode(json: String): PaperPage {
            val o=JSONObject(json)
            require(o.getInt("schema")==1 && o.getString("space")=="android-pdf-renderer-page-points")
            val width=o.getInt("width"); val height=o.getInt("height"); val revision=o.getLong("revision")
            require(width>0 && height>0 && revision>=0)
            val rawLayers=o.optJSONArray("layers")
            val layers=if(rawLayers==null) listOf(PaperLayer(1,"图层 1")) else {
                require(rawLayers.length() in 1..32)
                (0 until rawLayers.length()).map { i -> val l=rawLayers.getJSONObject(i)
                    PaperLayer(l.getInt("id"),l.getString("name"),l.optBoolean("visible",true),l.optBoolean("locked"),
                        l.optDouble("opacity",1.0).toFloat(),l.optString("blend","normal")) }
            }
            require(layers.map { it.id }.distinct().size==layers.size)
            val active=o.optInt("active_layer",layers.last().id);require(layers.any { it.id==active })
            val strokes=o.getJSONArray("strokes");require(strokes.length()<=100000);var total=0
            val decoded=(0 until strokes.length()).map { i ->
                val stroke=strokes.getJSONObject(i);val points=stroke.getJSONArray("points")
                total+=points.length();require(total<=1000000)
                val penWidth=stroke.getDouble("width").toFloat();require(penWidth.isFinite() && penWidth>0 && penWidth<=1000)
                val layer=stroke.optInt("layer",1);require(layers.any { it.id==layer })
                PaperStroke((0 until points.length()).map { j -> val p=points.getJSONArray(j)
                    val x=p.getDouble(0).toFloat();val y=p.getDouble(1).toFloat();val pressure=p.getDouble(2).toFloat()
                    require(x.isFinite() && y.isFinite() && pressure.isFinite() && x in 0f..width.toFloat() && y in 0f..height.toFloat())
                    InkPoint(x,y,pressure,p.getLong(3))
                },penWidth,stroke.optInt("color",-16777216),stroke.optString("engine","vector").also {
                    require(it in setOf("vector","drawpile-classic-v1","drawdance-v1"))
                },layer,BrushSettings.decode(stroke.optJSONObject("brush")),stroke.optString("operation","stroke").also {require(it in setOf("stroke","fill","polygon"))})
            }
            return PaperPage(width,height,revision,decoded,layers,active)
        }
    }
}

/** One transform shared by ink rendering, input mapping and selection. */
data class PaperTransform(val scale: Float, val left: Float, val top: Float) {
    fun pageX(viewX: Float) = (viewX - left) / scale
    fun pageY(viewY: Float) = (viewY - top) / scale
    companion object {
        fun fit(viewWidth: Int, viewHeight: Int, pageWidth: Int, pageHeight: Int, zoom: Float, dx: Float, dy: Float): PaperTransform {
            require(viewWidth > 0 && viewHeight > 0 && pageWidth > 0 && pageHeight > 0 && zoom > 0)
            val scale = minOf(viewWidth.toFloat() / pageWidth, viewHeight.toFloat() / pageHeight) * zoom
            return PaperTransform(scale, (viewWidth - pageWidth * scale) / 2 + dx, (viewHeight - pageHeight * scale) / 2 + dy)
        }
    }
}
