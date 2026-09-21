package com.example.note2snap.model

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredNoteOrderingTest {

    @Test
    fun testTextAndVisualOrdering() {

        val blocks =
            listOf(

                NoteBlock(
                    rawText = "A binary tree is a hierarchical data structure.",
                    type = BlockType.REGULAR_TEXT,
                    formattedText = "A binary tree is a hierarchical data structure.",
                    boundingBox = Rect(
                        60,
                        180,
                        700,
                        230
                    )
                ),

                NoteBlock(
                    rawText = "Properties:",
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>Properties:</b>",
                    boundingBox = Rect(
                        60,
                        500,
                        300,
                        560
                    )
                ),

                NoteBlock(
                    rawText = "• Each node has at most two children.",
                    type = BlockType.BULLET_ITEM,
                    formattedText = "• Each node has at most two children.",
                    boundingBox = Rect(
                        60,
                        600,
                        720,
                        660
                    )
                ),

                NoteBlock(
                    rawText = "",
                    type = BlockType.VISUAL,
                    formattedText = "",
                    imagePath = "fake_tree_diagram.png",
                    boundingBox = Rect(
                        780,
                        150,
                        1320,
                        590
                    )
                ),

                NoteBlock(
                    rawText = "Traversals:",
                    type = BlockType.SECTION_HEADER,
                    formattedText = "<b>Traversals:</b>",
                    boundingBox = Rect(
                        830,
                        690,
                        1050,
                        750
                    )
                ),

                NoteBlock(
                    rawText = "• Preorder: Root → Left → Right",
                    type = BlockType.BULLET_ITEM,
                    formattedText = "• Preorder: Root → Left → Right",
                    boundingBox = Rect(
                        830,
                        760,
                        1380,
                        820
                    )
                )
            )

        val ordered =
            blocks.sortedWith(
                compareBy<NoteBlock>(
                    {
                        it.boundingBox?.top
                            ?: Int.MAX_VALUE
                    },
                    {
                        it.boundingBox?.left
                            ?: Int.MAX_VALUE
                    }
                )
            )

        println()
        println("===================================")
        println("TEXT + VISUAL ORDERING TEST")
        println("===================================")

        ordered.forEachIndexed { index, block ->

            val label =
                when (block.type) {

                    BlockType.VISUAL ->
                        "[VISUAL]"

                    else ->
                        block.rawText
                }

            println(
                "${index + 1}. ${block.type} -> $label"
            )
        }

        println("===================================")

        assertEquals(
            BlockType.REGULAR_TEXT,
            ordered[0].type
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            ordered[1].type
        )

        assertEquals(
            BlockType.BULLET_ITEM,
            ordered[2].type
        )

        assertEquals(
            BlockType.VISUAL,
            ordered[3].type
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            ordered[4].type
        )

        assertEquals(
            BlockType.BULLET_ITEM,
            ordered[5].type
        )
    }
}