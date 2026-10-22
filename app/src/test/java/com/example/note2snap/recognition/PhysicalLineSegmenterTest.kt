package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhysicalLineSegmenterTest {
    private fun segment(boxes: List<Rect>): List<PhysicalLine> {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        boxes.forEach { box ->
            for (y in box.top until box.bottom) for (x in box.left until box.right)
                bitmap.setPixel(x, y, Color.BLACK)
        }
        return PhysicalLineSegmenter().splitIntoPhysicalLines(
            Region(1, Rect(50, 70, 250, 170), RegionType.TEXT, 1000, bitmap))
    }

    @Test fun detachedDotsStayWithTheirLetterBody() {
        val lines = segment(listOf(Rect(20, 15, 25, 18), Rect(20, 22, 100, 42)))
        assertEquals(1, lines.size)
        assertTrue(lines.single().boundingBox.top <= 85)
    }

    @Test fun separatePhysicalRowsRemainSeparateAndCoordinatesAreGlobal() {
        val lines = segment(listOf(Rect(20, 15, 100, 35), Rect(30, 60, 120, 80)))
        assertEquals(2, lines.size)
        assertTrue(lines[0].boundingBox.bottom < lines[1].boundingBox.top)
        assertTrue(lines[0].boundingBox.left >= 50)
    }

    @Test fun nearbyFullRowsAreNotMistakenForDetachedDots() {
        val lines = segment(listOf(Rect(20, 15, 100, 35), Rect(20, 40, 100, 60)))
        assertEquals(2, lines.size)
        assertTrue(lines[0].boundingBox.bottom <= lines[1].boundingBox.top)
    }

    @Test fun blankCropProducesNoRecognitionInput() {
        assertTrue(segment(emptyList()).isEmpty())
    }
}
