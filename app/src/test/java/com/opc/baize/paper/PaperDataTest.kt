package com.opc.baize.paper

import org.junit.Assert.*
import org.junit.Test

class PaperDataTest {
    @Test fun restoresVectorsWithWidthPressureTimeAndRevision() {
        val original = PaperPage(600, 800, 42, listOf(PaperStroke(listOf(InkPoint(10f, 20f, 0.5f, 100), InkPoint(30f, 40f, 1f, 120)), 2.5f)))
        assertEquals(original, PaperPage.decode(original.encode()))
    }
    @Test fun coordinateMappingRoundTripsAcrossZoomPanAndPageSizes() {
        for ((w, h) in listOf(600 to 800, 800 to 600, 1000 to 1000)) {
            for (zoom in listOf(1f, 2f, 5f)) {
                val t = PaperTransform.fit(1200, 900, w, h, zoom, 123f, -67f)
                for ((x, y) in listOf(0f to 0f, w / 2f to h / 2f, w.toFloat() to h.toFloat())) {
                    assertEquals(x, t.pageX(x * t.scale + t.left), 0.001f)
                    assertEquals(y, t.pageY(y * t.scale + t.top), 0.001f)
                }
            }
        }
    }
    @Test fun capturedSubmissionIsUnaffectedByLaterEdits() {
        val stroke = PaperStroke(listOf(InkPoint(10f, 10f, 1f, 1)))
        val captured = PaperPage(600, 800, 1, listOf(stroke))
        val edited = captured.copy(revision = 2, strokes = captured.strokes + stroke)
        assertEquals(1, PaperPage.decode(captured.encode()).strokes.size)
        assertEquals(2, edited.strokes.size); assertEquals(1L, captured.revision)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPointsOutsideRecordedPage() {
        PaperPage.decode(PaperPage(100, 100, 1, listOf(PaperStroke(listOf(InkPoint(200f, 10f, 1f, 1))))).encode())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownCoordinateSchema() {
        PaperPage.decode(PaperPage(100, 100, 0, emptyList()).encode().replace("android-pdf-renderer-page-points", "screen-pixels"))
    }
}
