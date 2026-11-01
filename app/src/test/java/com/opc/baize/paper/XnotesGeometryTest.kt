package com.opc.baize.paper
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import org.junit.Assert.*
import org.junit.Test
class XnotesGeometryTest {
    @Test fun selectionRotationMirrorAndMovementUseTheSamePivot() {
        val pivot=Pt(100.0,100.0)
        val transform=Affine.translateBy(10.0,20.0).compose(Affine.rotateAbout(pivot,Math.PI/2))
            .compose(Affine.scaleAbout(pivot,-2.0,2.0))
        val p=transform.apply(Pt(110.0,100.0))
        assertEquals(110.0,p.x,.0001);assertEquals(100.0,p.y,.0001)
        assertEquals(2.0,transform.linearScale,.0001)
    }
}
