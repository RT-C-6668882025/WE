package com.opc.baize.paper

import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ColoredInkTest {
    @Test fun coloredInkCanBeErasedAndRestoredWithoutTouchingThePage() {
        val view = PaperInkView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 600, 840)
        view.load(Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888), PaperPage(600, 840, 0, emptyList()))
        fun touch(action: Int, x: Float) { val e = MotionEvent.obtain(1, 2, action, x, 100f, 0); try { view.onTouchEvent(e) } finally { e.recycle() } }
        try {
            view.penColor = Color.BLUE; view.penWidth = 5f
            touch(MotionEvent.ACTION_DOWN, 100f); touch(MotionEvent.ACTION_UP, 200f)
            assertEquals(Color.BLUE, view.page.strokes.single().color)
            val saved = PaperPage.decode(view.page.encode()); assertEquals(view.page, saved)
            val old = org.json.JSONObject(saved.encode()); old.getJSONArray("strokes").getJSONObject(0).remove("color")
            assertEquals(Color.BLACK, PaperPage.decode(old.toString()).strokes.single().color)
            view.mode = PaperInkView.Mode.ERASE; touch(MotionEvent.ACTION_DOWN, 150f); touch(MotionEvent.ACTION_UP, 150f)
            assertTrue(view.page.strokes.isEmpty()); view.undo()
            assertEquals(saved.strokes, view.page.strokes)
        } finally { view.release() }
    }
}
