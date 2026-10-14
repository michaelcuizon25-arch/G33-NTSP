package com.example.note2snap.recognition

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WhiteboardPhotoRegressionTest {
    @Test fun lectureBoardHasCompleteTitleAndSeparateBodyRows() {
        val photo = javaClass.classLoader!!.getResourceAsStream("board_test_5.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        val preprocessed = WhiteboardPreprocessor().process(photo)
        val text = ConnectedComponentLabeler().label(
            preprocessed.binarizedBitmap, preprocessed.recognitionBitmap
        ).filter { it.type == RegionType.TEXT }
        // Manually inspected ink extents at the preprocessor's 1800-pixel scale.
        // These assert content coverage rather than total region counts, which
        // can legitimately change as unrelated board marks are filtered.
        val coreLines = listOf(
            Rect(225, 350, 945, 375),  // Mobile Application Development
            Rect(225, 455, 610, 485),  // LECTURE SERIES
            Rect(225, 570, 820, 595),  // Implementing with User Input
            Rect(215, 680, 1450, 705), // This principle ... Dart.
            Rect(220, 760, 1480, 785), // The output ... inside
            Rect(235, 850, 415, 875)   // the class.
        )
        val matching = coreLines.map { core ->
            val covering = text.filter { it.boundingBox.contains(core) }
            assertEquals("Expected one complete crop for $core", 1, covering.size)
            covering.single()
        }
        assertEquals("Physical rows must remain distinct", coreLines.size, matching.map { it.id }.toSet().size)
        matching.forEach { region ->
            assertEquals(region.boundingBox.width(), region.croppedBitmap.width)
            assertEquals(region.boundingBox.height(), region.croppedBitmap.height)
        }
    }
}
