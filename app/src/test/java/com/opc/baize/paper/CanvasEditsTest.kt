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
}
