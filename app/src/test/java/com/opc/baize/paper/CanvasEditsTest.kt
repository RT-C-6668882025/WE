package com.opc.baize.paper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanvasEditsTest {
    private fun ops(raw:String)=JSONArray(raw)
    @Test fun batchUsesOneCoordinateSpaceAndSupportsBrushesLayersAndTransforms() {
        val before=PaperPage(600,840,4,emptyList())
        val after=CanvasEdits.apply(before,ops("""[
            {"action":"add_layer","name":"AI 图解","layer_id":2},
            {"action":"line","x":20,"y":20,"x2":100,"y2":60,"color":"#FF0000","brush":{"opacity":0.4,"mypaint":true}},
            {"action":"rectangle","x":100,"y":100,"x2":200,"y2":200},
            {"action":"transform","indices":[0],"dx":5,"dy":10},
            {"action":"update_layer","opacity":0.5,"blend":"multiply"}
        ]"""))
        assertTrue(before.strokes.isEmpty());assertEquals(4L,after.revision)
        assertEquals(2,after.activeLayerId);assertEquals(2,after.strokes.size)
        assertEquals(25f,after.strokes.first().points.first().x,0f)
        assertEquals(30f,after.strokes.first().points.first().y,0f)
        assertTrue(after.strokes.first().brush.mypaint)
        assertEquals(.5f,after.activeLayer.opacity,0f)
        assertEquals("multiply",after.activeLayer.blend)
    }
    @Test fun invalidLaterOperationDoesNotModifyOriginalOrLockedLayer() {
        val before=PaperPage(600,840,2,emptyList())
        assertThrows(IllegalArgumentException::class.java) {CanvasEdits.apply(before,ops("""[
            {"action":"line","x":10,"y":10,"x2":30,"y2":30},
            {"action":"line","x":-1,"y":0,"x2":0,"y2":0}
        ]"""))}
        assertTrue(before.strokes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {CanvasEdits.apply(before.copy(layers=listOf(PaperLayer(1,"锁定",locked=true))),ops("""[{"action":"clear_layer"}]"""))}
        assertThrows(IllegalArgumentException::class.java) {CanvasEdits.apply(before,ops("""[{"action":"line","x":0,"y":0,"x2":1,"y2":1,"widht":3}]"""))}
    }
    @Test fun eraseFillDuplicateAndDeleteKeepOtherLayers() {
        val after=CanvasEdits.apply(PaperPage(600,840,0,emptyList()),ops("""[
            {"action":"stroke","points":[[10,10,0.5,1],[30,30,1,20]]},
            {"action":"erase","points":[[10,10],[30,30]],"width":10},
            {"action":"duplicate_layer"},
            {"action":"fill","color":"#FFFFFF"},
            {"action":"delete_strokes","indices":[2]},
            {"action":"reorder_layer","index":0}
        ]"""))
        assertEquals(2,after.layers.first().id)
        assertEquals(2,after.strokes.count {it.layerId==1})
        assertTrue(after.strokes.first {it.layerId==1 && it.brush.erase}.brush.erase)
        assertEquals("fill",after.strokes.last().operation)
    }
    @Test fun solidShapesCurvesAndSeedFillRemainEditable() {
        val result=CanvasEdits.apply(PaperPage(600,840,0,emptyList()),ops("""[
            {"action":"rectangle","x":20,"y":20,"x2":80,"y2":80,"filled":true,"color":"#FF0000"},
            {"action":"polygon","points":[[100,100],[150,100],[125,150]],"filled":true},
            {"action":"bezier","points":[[10,10],[20,90],[80,90],[90,10]]},
            {"action":"fill","x":50,"y":50,"color":"#0000FF"}
        ]"""))
        assertEquals(listOf("polygon","polygon","stroke","fill"),result.strokes.map {it.operation})
        assertEquals(97,result.strokes[2].points.size)
        assertEquals(50f,result.strokes.last().points.first().x,0f)
        assertEquals(result,PaperPage.decode(result.encode()))
    }
    @Test fun regionRestylingAndWholeLayerTransformsPreserveOtherLayersAndBrushFields() {
        val original=CanvasEdits.apply(PaperPage(600,840,0,emptyList()),ops("""[
            {"action":"line","x":20,"y":20,"x2":40,"y2":40,"brush":{"hardness":0.2,"pressure":false}},
            {"action":"line","x":200,"y":200,"x2":240,"y2":240},
            {"action":"add_layer","layer_id":2},
            {"action":"line","x":20,"y":20,"x2":40,"y2":40}
        ]"""))
        val result=CanvasEdits.apply(original,ops("""[
            {"action":"restyle","layer_id":1,"bounds":[0,0,50,50],"color":"#0000FF","brush":{"opacity":0.5}},
            {"action":"transform","layer_id":1,"dx":10},
            {"action":"delete_strokes","layer_id":2}
        ]"""))
        assertEquals(2,result.strokes.size)
        assertEquals(0xff0000ff.toInt(),result.strokes[0].color)
        assertEquals(.2f,result.strokes[0].brush.hardness,0f)
        assertFalse(result.strokes[0].brush.pressure)
        assertEquals(.5f,result.strokes[0].brush.opacity,0f)
        assertEquals(30f,result.strokes[0].points[0].x,0f)
        assertEquals(210f,result.strokes[1].points[0].x,0f)
        assertEquals(3,original.strokes.size)
    }
    @Test fun invalidShapeAndAmbiguousSelectionFailAtomically() {
        val original=PaperPage(600,840,0,emptyList())
        for(raw in listOf(
            """[{"action":"fill","x":20}]""",
            """[{"action":"bezier","points":[[0,0],[10,10],[20,20]]}]""",
            """[{"action":"line","x":0,"y":0,"x2":1,"y2":1,"filled":true}]""",
            """[{"action":"transform","indices":[0],"bounds":[0,0,10,10]}]"""
        )) assertThrows(IllegalArgumentException::class.java) {CanvasEdits.apply(original,ops(raw))}
        assertTrue(original.strokes.isEmpty())
    }

}
