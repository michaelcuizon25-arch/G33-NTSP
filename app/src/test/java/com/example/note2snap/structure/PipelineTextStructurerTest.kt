package com.example.note2snap.structuring

import android.graphics.Rect
import com.example.note2snap.model.BlockType
import com.example.note2snap.recognition.RecognizedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PipelineTextStructurerTest {

    @Test
    fun testMultipleRecognizedLinesFromSameRegion() {

        val lines =
            listOf(
                RecognizedLine(
                    sourceRegionId = 100000,
                    boundingBox = Rect(
                        63,
                        102,
                        580,
                        218
                    ),
                    text = "Binary Tree",
                    confidence = 1f
                ),

                RecognizedLine(
                    sourceRegionId = 100003,
                    boundingBox = Rect(
                        70,
                        239,
                        640,
                        290
                    ),
                    text = "A binary tree is a hierarchical",
                    confidence = 1f
                ),

                RecognizedLine(
                    sourceRegionId = 100003,
                    boundingBox = Rect(
                        68,
                        300,
                        716,
                        350
                    ),
                    text = "data structure in which each node",
                    confidence = 1f
                ),

                RecognizedLine(
                    sourceRegionId = 100006,
                    boundingBox = Rect(
                        68,
                        356,
                        746,
                        420
                    ),
                    text = "Properties:",
                    confidence = 1f
                ),

                RecognizedLine(
                    sourceRegionId = 100007,
                    boundingBox = Rect(
                        67,
                        412,
                        658,
                        476
                    ),
                    text = "• Each node has at most two children.",
                    confidence = 1f
                )
            )

        val structurer =
            PipelineTextStructurer()

        val result =
            structurer.structure(lines)

        println()
        println("===================================")
        println("PIPELINE TEXT STRUCTURER TEST")
        println("===================================")

        println(
            "TITLE: ${result.title}"
        )

        result.blocks.forEachIndexed { index, block ->

            println(
                "${index + 1}. ${block.type} -> ${block.rawText}"
            )

            println(
                "   Box: ${block.boundingBox}"
            )
        }

        println("===================================")

        assertEquals(
            "Binary Tree",
            result.title
        )

        assertEquals(
            4,
            result.blocks.size
        )

        assertEquals(
            BlockType.REGULAR_TEXT,
            result.blocks[0].type
        )

        assertEquals(
            "A binary tree is a hierarchical",
            result.blocks[0].rawText
        )

        assertEquals(
            BlockType.REGULAR_TEXT,
            result.blocks[1].type
        )

        assertEquals(
            "data structure in which each node",
            result.blocks[1].rawText
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            result.blocks[2].type
        )

        assertEquals(
            BlockType.BULLET_ITEM,
            result.blocks[3].type
        )

        assertTrue(
            result.blocks.all {
                it.boundingBox != null
            }
        )
    }
}