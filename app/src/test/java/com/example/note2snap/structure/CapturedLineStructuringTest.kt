package com.example.note2snap.structuring

import android.graphics.Rect
import com.example.note2snap.recognition.RecognizedLine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CapturedLineStructuringTest {
    private fun line(id: Int, box: Rect, text: String) = RecognizedLine(id, box, text, 1f)

    @Test fun capturedFourPhysicalLinesRemainSeparateAndOrdered() {
        val lines = listOf(
            line(100000, Rect(264,505,1151,587), "The quck brown fo jumps ver e"),
            line(100003, Rect(135,601,1318,666), "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG"),
            line(100006, Rect(135,697,1305,805), "he k os"),
            line(100008, Rect(134,799,1391,879), "THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG")
        )
        val result = PipelineTextStructurer().structure(lines.reversed())
        assertEquals(lines.map { it.text }, result.blocks.map { it.rawText })
        assertEquals(lines.map { it.boundingBox }, result.blocks.map { it.boundingBox })
    }

    @Test fun normalLowercaseSentenceWrapIsStillJoined() {
        val note = PipelineTextStructurer().structure(listOf(
            line(1, Rect(100,20,800,60), "WRAPPED TEXT"),
            line(2, Rect(100,100,800,140), "one wishes to better"),
            line(3, Rect(100,145,700,185), "understand certain characteristics.")
        ))
        assertTrue(note.blocks.any { it.rawText == "one wishes to better understand certain characteristics." })
    }

    @Test fun uppercaseSentenceEndingWithConnectorCanStillWrap() {
        val note = PipelineTextStructurer().structure(listOf(
            line(1, Rect(100,20,800,60), "WRAPPED TEXT"),
            line(2, Rect(100,100,800,140), "THE PROCESS APPLIES TO"),
            line(3, Rect(100,145,700,185), "the following inputs.")
        ))
        assertTrue(note.blocks.any { it.rawText == "THE PROCESS APPLIES TO the following inputs." })
    }

    @Test fun independentHeadingIsNotAbsorbedIntoBodyText() {
        val note = PipelineTextStructurer().structure(listOf(
            line(1, Rect(100,20,800,60), "MODULE NOTES"),
            line(2, Rect(100,100,800,140), "MEMORY MANAGEMENT"),
            line(3, Rect(100,145,700,185), "allocation and collection are separate operations.")
        ))
        assertTrue(note.blocks.any { it.rawText == "MEMORY MANAGEMENT" })
        assertTrue(note.blocks.any { it.rawText == "allocation and collection are separate operations." })
    }
}
