package com.example.note2snap.ccl

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConnectedComponentRegressionTest {
    private fun label(boxes: List<Rect>): List<Region> {
        val binary = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        binary.eraseColor(Color.BLACK)
        val source = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        boxes.forEach { box ->
            for (y in box.top until box.bottom) for (x in box.left until box.right) {
                binary.setPixel(x, y, Color.WHITE)
                source.setPixel(x, y, Color.BLACK)
            }
        }
        return ConnectedComponentLabeler().label(binary, source).filter { it.type == RegionType.TEXT }
    }

    @Test fun overlappingFragmentsBecomeOneCompleteCrop() {
        val regions = label(listOf(Rect(100, 100, 170, 120), Rect(185, 104, 250, 124), Rect(265, 108, 330, 128)))
        assertEquals(1, regions.size)
        val region = regions.single()
        assertEquals(Rect(90, 94, 340, 134), region.boundingBox)
        assertEquals(region.boundingBox.width(), region.croppedBitmap.width)
        assertEquals(Color.BLACK, region.croppedBitmap.getPixel(230, 20))
    }

    @Test fun closeRowsStaySeparateDespiteCropPaddingOverlap() {
        val regions = label(listOf(Rect(100, 100, 200, 120), Rect(100, 126, 200, 146)))
        assertEquals(2, regions.size)
        assertTrue(regions[0].boundingBox.top < regions[1].boundingBox.top)
    }

    @Test fun isolatedMarkDoesNotBecomeTextThroughPadding() {
        val regions = label(listOf(Rect(100, 100, 200, 120), Rect(400, 300, 405, 305)))
        assertEquals(1, regions.size)
        assertTrue(regions.single().boundingBox.right < 400)
    }

    @Test fun tinySpecksDoNotSetTheHandwritingScale() {
        val letters = listOf(Rect(100, 100, 120, 140), Rect(135, 104, 155, 144),
            Rect(170, 100, 190, 140))
        val specks = (0 until 30).map { Rect(300 + it * 10, 300, 304 + it * 10, 304) }
        val regions = label(letters + specks)
        assertEquals(1, regions.size)
        assertTrue(regions.single().boundingBox.right < 300)
    }

    @Test fun detachedDotIsIncludedInItsLetterCrop() {
        val regions = label(listOf(Rect(100, 100, 112, 120), Rect(104, 93, 108, 97)))
        assertEquals(1, regions.size)
        assertTrue(regions.single().boundingBox.top <= 93)
    }

    @Test fun widelySeparatedColumnsAreNotJoined() {
        val regions = label(listOf(Rect(100, 100, 200, 120), Rect(500, 100, 600, 120)))
        assertEquals(2, regions.size)
    }

    @Test fun aLegitimateSingleCharacterIsRetained() {
        val regions = label(listOf(Rect(100, 100, 112, 120)))
        assertEquals(1, regions.size)
    }
}
