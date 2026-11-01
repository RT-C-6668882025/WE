package com.opc.baize.paper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawingDeviceTest {
    private fun line(color:Int,y:Float=50f,brush:BrushSettings=BrushSettings(hardness=1f,pressure=false)) =
        PaperStroke(listOf(InkPoint(20f,y,1f,0),InkPoint(140f,y,1f,100)),20f,color,"drawdance-v1",1,brush)
    private fun raster(renderer:LayerRenderer,page:PaperPage,preview:PaperStroke?=null): Bitmap =
        Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).also {renderer.draw(Canvas(it),page,preview)}
    @Test fun nativeLayersErasePreviewAndReplayMatch() {
        LayerRenderer().use {renderer ->
            val page=PaperPage(160,100,1,listOf(line(Color.RED)))
            val first=raster(renderer,page)
            assertTrue(Color.alpha(first.getPixel(80,50))>200)
            val erase=line(Color.BLACK,brush=BrushSettings(hardness=1f,pressure=false,erase=true)).copy(width=6f)
            raster(renderer,page,erase).also {assertTrue(Color.alpha(it.getPixel(80,50))<50);it.recycle()}
            raster(renderer,page).also {assertEquals(first.getPixel(80,50),it.getPixel(80,50));it.recycle()}
            val erased=page.copy(strokes=page.strokes+erase)
            val saved=raster(renderer,erased)
            LayerRenderer().use {fresh -> raster(fresh,PaperPage.decode(erased.encode())).also {
                assertEquals(saved.getPixel(80,50),it.getPixel(80,50));it.recycle()
            } }
            raster(renderer,page.copy(layers=listOf(PaperLayer(1,"隐藏",visible=false)))).also {
                assertEquals(0,Color.alpha(it.getPixel(80,50)));it.recycle()
            }
            first.recycle();saved.recycle()
        }
    }
    @Test fun mypaintFillAndLegacySeedAreReplayable() {
        val page=PaperPage(160,100,1,listOf(line(Color.RED).copy(engine="vector"),
            line(Color.BLUE,30f,BrushSettings(mypaint=true)),
            PaperStroke(listOf(InkPoint(1f,1f,1f,0)),color=Color.GREEN,engine="drawdance-v1",operation="fill")))
        LayerRenderer().use {renderer -> raster(renderer,page).also {bitmap ->
            assertEquals(Color.GREEN,bitmap.getPixel(1,1))
            assertTrue(Color.red(bitmap.getPixel(80,50))>100)
            assertTrue(Color.blue(bitmap.getPixel(80,30))>100)
            bitmap.recycle()
        } }
    }
}
