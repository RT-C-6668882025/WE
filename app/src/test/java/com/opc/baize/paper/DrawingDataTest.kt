package com.opc.baize.paper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DrawingDataTest {
    @Test fun oldPagesMigrateToOneVisibleLayer() {
        val old=JSONObject(PaperPage(200,300,4,listOf(PaperStroke(listOf(InkPoint(10f,20f,1f,1))))).encode())
        old.remove("layers");old.remove("active_layer")
        old.getJSONArray("strokes").getJSONObject(0).apply {remove("layer");remove("brush");remove("operation")}
        val restored=PaperPage.decode(old.toString())
        assertEquals(listOf(PaperLayer(1,"图层 1")),restored.layers)
        assertEquals(1,restored.activeLayerId);assertEquals(1,restored.strokes.single().layerId)
    }
    @Test fun layersAndEveryBrushParameterSurviveReopen() {
        val brush=BrushSettings(.2f,.35f,.08f,.7f,pressure=false,mypaint=true,erase=true)
        val page=PaperPage(600,840,12,listOf(PaperStroke(listOf(InkPoint(40f,50f,.4f,90)),20f,0x80557799.toInt(),
            "drawdance-v1",2,brush)),listOf(PaperLayer(1,"背景",locked=true),PaperLayer(2,"颜色",opacity=.6f,blend="multiply")),2)
        assertEquals(page,PaperPage.decode(page.encode()))
    }
    @Test fun brokenLayerReferencesAndInvalidBrushesAreRejected() {
        val o=JSONObject(PaperPage(200,300,0,listOf(PaperStroke(listOf(InkPoint(1f,2f,1f,0))))).encode())
        o.getJSONArray("strokes").getJSONObject(0).put("layer",99)
        try {PaperPage.decode(o.toString());fail("orphan stroke")} catch(_:IllegalArgumentException){}
        try {BrushSettings(opacity=Float.NaN);fail("invalid opacity")} catch(_:IllegalArgumentException){}
    }
}
