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
        assertEquals(listOf(BlockType.KEY_DEFINITION, BlockType.SECTION_HEADER, BlockType.KEY_DEFINITION), spatial.blocks.map { it.type })
    }

    @Test fun headingIsNotConsumedAsSentenceContinuation() {
        val result = WhiteboardRuleEngine.processRecognizedLines(listOf(
            line("This paragraph ends with the", 100), line("Properties:", 135),
            line("• A property", 170)))
        assertEquals("Properties:", result.blocks[0].rawText)
        assertEquals(BlockType.SECTION_HEADER, result.blocks[0].type)
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

    @Test fun graphicsSoftwarePhotoStructuring() {
        val lines = listOf(
            "SOFTWARE FOR GRAPHICS DESIGN",
            "• Adobe Illustrator - most used industry standard tool for editing",
            "• CorelDRAW - Vector based software for both Windows and MACOS",
            "• Canva - much easier to use for those who are unfamiliar with adobe",
            "• Looka - web-based program that uses artificial intelligence",
            "• Inkscape - can be used to design logos and other more complex projects."
        )
        val note = WhiteboardRuleEngine.process(lines)
        assertEquals("SOFTWARE FOR GRAPHICS DESIGN", note.title)
        assertTrue(note.blocks.any { it.type == BlockType.KEY_DEFINITION })
    }

    @Test fun inheritancePhotoStructuring() {
        val lines = listOf(
            "INHERITANCE",
            "A pillar that aims to permit classes to inherit the properties, methods, and components of another class. It is also implemented using the extends keyword."
        )
        val note = WhiteboardRuleEngine.process(lines)
        assertEquals("INHERITANCE", note.title)
        assertEquals(1, note.blocks.size)
        assertEquals(BlockType.REGULAR_TEXT, note.blocks[0].type)
    }

    @Test fun logoDesignsPhotoStructuring() {
        val lines = listOf(
            "LOGO DESIGNS",
            "- visual symbols that represents brands / organizations",
            "- identify and differentiate a brand conveying its essence.",
            "- benefits organizations as it provides recognition, consistent branding",
            "and a competitive edge in the market.",
            "Key Persons to work with:",
            "Client Manager - provides insight into company values and goals",
            "Marketing / Branding Manager - insight on target audience",
            "Creative / Art Director - offers guidance on design direction and aesthetic"
        )
        val note = WhiteboardRuleEngine.process(lines)
        assertEquals("LOGO DESIGNS", note.title)
        val header = note.blocks.first { it.rawText.startsWith("Key Persons") }
        assertEquals(BlockType.SECTION_HEADER, header.type)
    }
}
