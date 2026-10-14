package com.example.note2snap.structuring

import android.graphics.Rect
import com.example.note2snap.model.BlockType
import com.example.note2snap.recognition.RecognizedLine
import com.example.note2snap.utils.WhiteboardRuleEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WhiteboardRulesRegressionTest {
    private fun line(text: String, y: Int, left: Int = 50) =
        RecognizedLine(y, Rect(left, y, left + 600, y + 30), text, 1f)

    @Test fun definitionsDoNotMergeEvenWhenPreviousEndsWithConnector() {
        val texts = listOf("Shape: enclosed area of", "Form: three dimensional shape of", "Texture: surface quality")
        val result = WhiteboardRuleEngine.processRecognizedLines(texts.mapIndexed { i, t -> line(t, 100 + i * 35) })
        assertEquals(texts, result.blocks.map { it.rawText })
        assertTrue(result.blocks.all { it.type == BlockType.KEY_DEFINITION })
    }

    @Test fun wrappedDefinitionStillJoinsItsContinuation() {
        val result = WhiteboardRuleEngine.processRecognizedLines(listOf(
            line("Shape: an enclosed area of", 100), line("two dimensional space", 135),
            line("Form: three dimensional volume", 170)))
        assertEquals(2, result.blocks.size)
        assertEquals("Shape: an enclosed area of two dimensional space", result.blocks[0].rawText)
    }

    @Test fun titleAndExplicitBulletsAgreeAcrossEntryPoints() {
        val texts = listOf("Binary Tree", "A binary tree is a hierarchical data structure.",
            "Traversals:", "• Preorder: Root → Left → Right")
        val plain = WhiteboardRuleEngine.process(texts)
        val spatial = WhiteboardRuleEngine.processRecognizedLines(texts.mapIndexed { i, t -> line(t, 100 + i * 50) })
        assertEquals("Binary Tree", spatial.title)
        assertEquals(plain.title, spatial.title)
        assertEquals(plain.blocks.map { it.type }, spatial.blocks.map { it.type })
        assertEquals(listOf(BlockType.REGULAR_TEXT, BlockType.SECTION_HEADER, BlockType.BULLET_ITEM), spatial.blocks.map { it.type })
    }

    @Test fun headingIsNotConsumedAsSentenceContinuation() {
        val result = WhiteboardRuleEngine.processRecognizedLines(listOf(
            line("This paragraph ends with the", 100), line("Properties:", 135),
            line("• A property", 170)))
        assertEquals("Properties:", result.blocks[1].rawText)
        assertEquals(BlockType.SECTION_HEADER, result.blocks[1].type)
    }

    @Test fun indentationAloneDoesNotCreateABullet() {
        val result = WhiteboardRuleEngine.processRecognizedLines(listOf(
            line("This is a completed sentence.", 100),
            line("Another completed sentence.", 170, 150)))
        assertEquals(listOf(BlockType.REGULAR_TEXT, BlockType.REGULAR_TEXT), result.blocks.map { it.type })
    }

    @Test fun uppercaseEquationIsMathAndNumberedDefinitionIsAListItem() {
        val result = WhiteboardRuleEngine.process(listOf("N = 5", "1. Collect: observations"))
        assertEquals(listOf(BlockType.MATHEMATICAL, BlockType.NUMBERED_ITEM), result.blocks.map { it.type })
    }

    @Test fun numberedSectionsAndMathKeepTheirTypes() {
        val result = WhiteboardRuleEngine.process(listOf("1.1 - Introduction to Statistics", "1. Collect data", "n <= N", "### Sample"))
        assertEquals("1.1 - Introduction to Statistics", result.title)
        assertEquals(listOf(BlockType.NUMBERED_ITEM, BlockType.MATHEMATICAL, BlockType.SUBHEADING), result.blocks.map { it.type })
    }
}
