package com.example.note2snap.utils

import android.graphics.Rect
import com.example.note2snap.model.BlockType
import com.example.note2snap.recognition.RecognizedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ElementsOfDesignStructuringTest {

    @Test
    fun strayRecognitionAboveRealTitleDoesNotBecomeTitleOrBody() {
        val lines = listOf(
            RecognizedLine(1, Rect(12, 4, 38, 20), "a a9", 0.3f),
            RecognizedLine(2, Rect(50, 52, 460, 98), "ELEMENTS OF DESIGN", 0.9f),
            RecognizedLine(3, Rect(50, 155, 470, 194), "7 Bacic Elements of Design 9", 0.7f),
            RecognizedLine(4, Rect(50, 240, 520, 270), "Line - can have width and length", 0.8f),
            RecognizedLine(5, Rect(50, 320, 520, 350), "Shape – an area of space", 0.8f)
        )
        val result = WhiteboardRuleEngine.processRecognizedLines(lines)
        assertEquals("ELEMENTS OF DESIGN", result.title)
        assertFalse(result.blocks.any { it.rawText == "a a9" })
        assertTrue(result.blocks.any {
            it.rawText == "7 Bacic Elements of Design 9" && it.type == BlockType.SECTION_HEADER
        })
        assertTrue(result.blocks.any {
            it.rawText.startsWith("Line -") && it.type == BlockType.KEY_DEFINITION
        })
    }

    @Test
    fun dashDefinitionsPreserveOriginalWordingButEmphasizeTerms() {
        val examples = listOf(
            "Line - can have width and length" to "<b>Line</b>: can have width and length",
            "Shape – an area of space" to "<b>Shape</b>: an area of space",
            "Form — objects occupy space" to "<b>Form</b>: objects occupy space"
        )
        val result = WhiteboardRuleEngine.process(listOf("ELEMENTS OF DESIGN") + examples.map { it.first })
        for ((original, formatted) in examples) {
            val block = result.blocks.first { it.rawText == original }
            assertEquals(BlockType.KEY_DEFINITION, block.type)
            assertEquals(formatted, block.formattedText)
        }
    }

    @Test
    fun ordinaryHyphenatedSentenceRemainsPlain() {
        val sentence = "The step-by-step process is explained below."
        val result = WhiteboardRuleEngine.process(listOf(sentence))
        assertEquals(BlockType.REGULAR_TEXT, result.blocks.first().type)
    }
}
