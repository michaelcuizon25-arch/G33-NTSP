package com.example.note2snap.utils

import com.example.note2snap.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            BlockType.SECTION_HEADER,
            result.blocks[1].type
        )

        assertEquals(
            BlockType.BULLET_ITEM,
            result.blocks[2].type
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            result.blocks[6].type
        )

        assertEquals(
            BlockType.BULLET_ITEM,
            result.blocks[7].type
        )

        assertTrue(
            result.blocks.none {
                it.type == BlockType.VISUAL
            }
        )
    }
}