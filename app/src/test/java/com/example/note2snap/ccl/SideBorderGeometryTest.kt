package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.recognition.PaddlePrimaryInputGuard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SideBorderGeometryTest {
    @Test fun thinTallFragmentsSlightlyInsetAtEitherSideAreRejected() {
        assertTrue(SideBorderGeometry.isClearFragment(Rect(6,30,10,120),800,600,25))
        assertTrue(SideBorderGeometry.isClearFragment(Rect(790,30,794,120),800,600,25))
    }

    @Test fun normalHeightNarrowIOrLOrOneNearTheMarginAreNotRejected() {
        for (x in listOf(6,40,300,790)) for (width in 1..4) {
            assertFalse(SideBorderGeometry.isClearFragment(Rect(x,100,x+width,125),800,600,25))
        }
    }

    @Test fun interiorTallCharactersAndWideEdgeWritingArePreserved() {
        assertFalse(SideBorderGeometry.isClearFragment(Rect(300,30,304,150),800,600,25))
        assertFalse(SideBorderGeometry.isClearFragment(Rect(6,30,26,150),800,600,25))
        assertFalse(SideBorderGeometry.isClearFragment(Rect(20,30,24,150),800,600,25))
        assertFalse(SideBorderGeometry.isClearFragment(Rect(6,30,10,75),800,600,25))
    }

    @Test fun primaryGuardPreservesOneTwoAndThreePixelVerticalGlyphStrokes() {
        for (width in 1..3) {
            val bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                for (y in 20 until 60) for (x in 30 until 30+width) bitmap.setPixel(x,y,Color.BLACK)
                assertNull("Narrow I/l/1-like stroke must not be rejected without global edge evidence",
                    PaddlePrimaryInputGuard.rejectionReason(bitmap))
            } finally { bitmap.recycle() }
        }
    }

    private fun label(boxes: List<Rect>): List<Region> {
        val binary=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val source=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (box in boxes) for(y in box.top until box.bottom) for(x in box.left until box.right) {
            binary.setPixel(x,y,Color.WHITE);source.setPixel(x,y,Color.BLACK)
        }
        return try { ConnectedComponentLabeler().label(binary,source).filter { it.type==RegionType.TEXT } }
            finally { binary.recycle();source.recycle() }
    }

    @Test fun cclKeepsFourTextRowsAndExcludesFourInsetSideFragments() {
        val text=listOf(70,210,350,490).flatMap { y -> listOf(Rect(100,y,114,y+25),Rect(135,y,149,y+25),Rect(170,y,184,y+25)) }
        val borders=listOf(30,170,310,450).map { y -> Rect(6,y,10,y+90) }
        val regions=label(text+borders)
        assertEquals(4,regions.size)
        assertTrue(regions.all { it.boundingBox.left >= 80 })
    }

    @Test fun cclRetainsNarrowHandwrittenStemsInsideActualTextRows() {
        val regions=label(listOf(Rect(100,100,103,125),Rect(125,100,128,125),Rect(150,100,153,125)))
        assertTrue(regions.isNotEmpty())
        assertTrue(regions.any { it.boundingBox.left <= 100 && it.boundingBox.right >= 153 })
    }
}
