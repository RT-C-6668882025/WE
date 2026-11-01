package com.opc.baize.paper

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class CanvasHistoryTest {
    @Test fun historySurvivesReopenAndStaleSavesCannotOverwriteAgentEdits() {
        val context=RuntimeEnvironment.getApplication()
        val id="f".repeat(64);val store=PaperRepository(context)
        File(context.filesDir,"papers/$id").deleteRecursively()
        try {
            val one=store.edit(id,0,600,840,0) {CanvasEdits.apply(it,JSONArray("""[{"action":"line","x":10,"y":10,"x2":100,"y2":100}]"""))}
            assertEquals(1L,one.revision)
            assertThrows(IllegalArgumentException::class.java) {store.edit(id,0,600,840,0) {it}}
            val two=PaperRepository(context).history(id,0,600,840,1,false)
            assertEquals(2L,two.revision);assertTrue(two.strokes.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {store.save(id,0,one)}
            val three=PaperRepository(context).history(id,0,600,840,2,true)
            assertEquals(3L,three.revision);assertEquals(one.strokes,three.strokes)
            assertEquals(three,store.load(id,0,600,840))
            assertThrows(IllegalArgumentException::class.java) {store.edit(id,0,600,840,3) {CanvasEdits.apply(it,JSONArray("""[{"action":"clear_layer"},{"action":"add_layer","layer_id":1}]"""))}}
            assertEquals(three,store.load(id,0,600,840))
        } finally {File(context.filesDir,"papers/$id").deleteRecursively()}
    }
}
