package com.example.note2snap.utils

import com.example.note2snap.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [28])
class WhiteboardRuleEngineTest {

    @Test
    fun testWhiteboardStructuring() {

        val lines = listOf(
            "Binary Tree",
            "A binary tree is a hierarchical data structure.",
            "Properties:",
            "• Each node has at most two children.",
            "• The top node is called the root.",
            "• A binary tree can be empty.",
            "• Subtrees can also be binary trees.",
            "Traversals:",
            "• Preorder: Root → Left → Right",
            "• Inorder: Left → Root → Right",
            "• Postorder: Left → Right → Root"
        )

        val result =
            WhiteboardRuleEngine.process(lines)

        println()
        println("===================================")
        println("WHITEBOARD STRUCTURING TEST")
        println("===================================")

        println("TITLE: ${result.title}")

        result.blocks.forEachIndexed { index, block ->
            println(
                "${index + 1}. ${block.type} -> ${block.formattedText}"
            )
        }

        println("===================================")


        assertEquals(
            "Binary Tree",
            result.title
        )

        assertEquals(
            BlockType.REGULAR_TEXT,
            result.blocks[0].type
        )

        assertEquals(
            BlockType.KEY_DEFINITION,
            result.blocks[1].type
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            result.blocks[2].type
        )

        assertEquals(
            BlockType.BULLET_ITEM,
            result.blocks[3].type
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            result.blocks[7].type
        )

        assertEquals(
            BlockType.KEY_DEFINITION,
            result.blocks[8].type
        )

        assertTrue(
            result.blocks.none {
                it.type == BlockType.VISUAL
            }
        )

    }

    @Test
    fun testBasicNoteFormatting() {

        val lines = listOf(
            "Photosynthesis",
            "Plants produce food using sunlight.",
            "Requirements:",
            "• Sunlight",
            "• Water",
            "• Carbon dioxide",
            "Chlorophyll: Absorbs light energy.",
            "Products:",
            "1. Glucose",
            "2. Oxygen"
        )

        val result = WhiteboardRuleEngine.process(lines)

        assertEquals("Photosynthesis", result.title)

        fun assertBlockType(
            text: String,
            expectedType: BlockType
        ) {
            val block = result.blocks.firstOrNull {
                it.rawText == text
            }

            assertTrue(
                "Missing block: $text",
                block != null
            )

            assertEquals(
                "Wrong type for: $text",
                expectedType,
                block!!.type
            )
        }

        assertBlockType(
            "Plants produce food using sunlight.",
            BlockType.REGULAR_TEXT
        )

        assertBlockType(
            "Requirements:",
            BlockType.SECTION_HEADER
        )

        assertBlockType(
            "• Sunlight",
            BlockType.BULLET_ITEM
        )

        assertBlockType(
            "• Water",
            BlockType.BULLET_ITEM
        )

        assertBlockType(
            "Chlorophyll: Absorbs light energy.",
            BlockType.KEY_DEFINITION
        )

        assertBlockType(
            "Products:",
            BlockType.SECTION_HEADER
        )

        assertBlockType(
            "1. Glucose",
            BlockType.NUMBERED_ITEM
        )

        assertBlockType(
            "2. Oxygen",
            BlockType.NUMBERED_ITEM
        )
    }


    @Test
    fun testSentenceStyleDefinition() {

        val lines = listOf(
            "Binary Tree",
            "A binary tree is a hierarchical data structure.",
            "Properties:",
            "• Each node has at most two children."
        )

        val result = WhiteboardRuleEngine.process(lines)

        val definition = result.blocks.firstOrNull {
            it.rawText == "A binary tree is a hierarchical data structure."
        }

        assertTrue(
            "Definition should exist",
            definition != null
        )

        assertEquals(
            "Sentence-style definition should be recognized",
            BlockType.KEY_DEFINITION,
            definition!!.type
        )
    }


    @Test
    fun testOrdinarySentencesAreNotDefinitions() {

        val sentences = listOf(
            "The student is reading a book.",
            "The weather is sunny today.",
            "The computer is running slowly.",
            "The teacher is explaining the lesson.",
            "The classroom is a place for learning."
        )

        for (sentence in sentences) {

            val result = WhiteboardRuleEngine.process(
                listOf(sentence)
            )

            val block = result.blocks.first()

            assertEquals(
                "Incorrectly classified: $sentence",
                BlockType.REGULAR_TEXT,
                block.type
            )
        }
    }


}