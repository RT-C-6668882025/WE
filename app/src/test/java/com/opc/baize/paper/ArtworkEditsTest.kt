package com.opc.baize.paper

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtworkEditsTest {
    private val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><g><rect width="100" height="100" fill="red"/></g></svg>"""
    private fun objectOp(type:String,content:String)=JSONObject().put("action",type).put("content",content)
        .put("x",10).put("y",10).put("x2",110).put("y2",110)
    @Test fun objectsRoundTripReplaceAndTransformWithoutFlatteningTheirSources() {
        val original=CanvasEdits.apply(PaperPage(600,840,0,emptyList()),JSONArray()
            .put(objectOp("svg",svg)).put(objectOp("text","Hello\nWE")))
        val edited=CanvasEdits.apply(original,JSONArray().put(JSONObject().put("action","replace_artwork").put("indices",JSONArray(listOf(1))).put("content","Changed"))
            .put(JSONObject().put("action","transform").put("indices",JSONArray(listOf(1))).put("scale",2).put("center_x",0).put("center_y",0)))
        assertEquals(svg,edited.strokes[0].content)
        assertEquals("Changed",edited.strokes[1].content)
        assertEquals(48f,edited.strokes[1].fontSize,0f)
        assertEquals(20f,edited.strokes[1].points[0].x,0f)
        assertEquals(edited,PaperPage.decode(edited.encode()))
        assertEquals("Hello\nWE",original.strokes[1].content)
    }
    @Test fun oldDocumentsRemainCompatibleAndInvalidSourcesAreRejected() {
        val old=JSONObject(PaperPage(600,840,0,listOf(PaperStroke(listOf(InkPoint(1f,1f,1f,0))))).encode())
        old.getJSONArray("strokes").getJSONObject(0).apply {remove("content");remove("font_size")}
        assertEquals("",PaperPage.decode(old.toString()).strokes.single().content)
        for(source in listOf("<!DOCTYPE svg><svg/>","<svg><image href='https://example.com/x'/></svg>","<svg><use href='#a'/></svg>","<svg style='fill:url(https://example.com)'/>","<svg><script>bad</script></svg>")) {
            assertThrows(IllegalArgumentException::class.java) {ArtworkContent.validate("svg",source)}
        }
        assertThrows(IllegalArgumentException::class.java) {ArtworkContent.validate("image","not base64")}
        assertThrows(IllegalArgumentException::class.java) {ArtworkContent.validate("text","x",Float.NaN)}
    }
    @Test fun lockedLayersAndRecoloringEmbeddedImagesFailWithoutMutation() {
        val original=CanvasEdits.apply(PaperPage(600,840,0,emptyList()),JSONArray().put(objectOp("svg",svg)))
        assertThrows(IllegalArgumentException::class.java) {CanvasEdits.apply(original,JSONArray().put(JSONObject().put("action","restyle").put("color","#FFFFFF")))}
        val locked=original.copy(layers=listOf(PaperLayer(1,"locked",locked=true)))
        assertThrows(IllegalArgumentException::class.java) {CanvasEdits.apply(locked,JSONArray().put(objectOp("text","x")))}
        assertEquals(svg,original.strokes.single().content)
    }
    @Test fun deepNestingMalformedXmlAndNamespacedScriptsAreRejected() {
        val sources=listOf("<svg>"+"<g>".repeat(65)+"</g>".repeat(65)+"</svg>",
            "<svg><g></svg>","<svg xmlns:x='http://www.w3.org/2000/svg'><x:script/></svg>","<svg onload='run()'/>")
        sources.forEach {source -> assertThrows(IllegalArgumentException::class.java) {ArtworkContent.validate("svg",source)}}
    }

}
