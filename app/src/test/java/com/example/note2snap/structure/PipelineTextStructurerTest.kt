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
            "Binary Tree",
            result.title
        )

        assertEquals(
            BlockType.SECTION_HEADER,
            result.blocks[0].type
        )

        assertEquals(
            "Binary Tree",
            result.blocks[0].rawText
        )

// Two recognized fragments should become
// one readable paragraph.
        assertEquals(
            BlockType.KEY_DEFINITION,
            result.blocks[1].type
        )

        assertEquals(
            "A binary tree is a hierarchical data structure in which each node",
            result.blocks[1].rawText
        )

// Section heading should remain identifiable.
        assertEquals(
            BlockType.SECTION_HEADER,
            result.blocks[2].type
        )

        assertEquals(
            "Properties:",
            result.blocks[2].rawText
        )

// Explicit bullet should remain a bullet.
        assertEquals(
            BlockType.BULLET_ITEM,
            result.blocks[3].type
        )

// All blocks should preserve their coordinates.
        assertTrue(
            result.blocks.all {
                it.boundingBox != null
            }
        )

    }

    @Test
    fun testIndentedParagraphIsNotBullet() {

        val lines = listOf(
            RecognizedLine(
                sourceRegionId = 1,
                boundingBox = Rect(50, 50, 500, 90),
                text = "Photosynthesis",
                confidence = 1f
            ),
            RecognizedLine(
                sourceRegionId = 2,
                boundingBox = Rect(50, 120, 650, 160),
                text = "Plants produce food using sunlight.",
                confidence = 1f
            ),
            RecognizedLine(
                sourceRegionId = 3,
                boundingBox = Rect(110, 200, 650, 240),
                text = "Chlorophyll absorbs light energy.",
                confidence = 1f
            ),
            RecognizedLine(
                sourceRegionId = 4,
                boundingBox = Rect(50, 280, 550, 320),
                text = "Requirements:",
                confidence = 1f
            ),
            RecognizedLine(
                sourceRegionId = 5,
                boundingBox = Rect(110, 360, 550, 400),
                text = "• Water",
                confidence = 1f
            ),
            RecognizedLine(
                sourceRegionId = 6,
                boundingBox = Rect(110, 440, 550, 480),
                text = "• Sunlight",
                confidence = 1f
            )
        )

        val result = PipelineTextStructurer().structure(lines)

        result.blocks.forEach {
            println("${it.type} -> ${it.rawText}")
        }

        val paragraph = result.blocks.firstOrNull {
            it.rawText.contains("Chlorophyll absorbs light energy")
        }

        assertTrue(
            "Indented explanation should exist",
            paragraph != null
        )

        assertEquals(
            "Indented explanation should not become a bullet",
            BlockType.REGULAR_TEXT,
            paragraph!!.type
        )

        assertTrue(
            "Water should remain a bullet",
            result.blocks.any {
                it.rawText.contains("Water") &&
                        it.type == BlockType.BULLET_ITEM
            }
        )

        assertTrue(
            "Sunlight should remain a bullet",
            result.blocks.any {
                it.rawText.contains("Sunlight") &&
                        it.type == BlockType.BULLET_ITEM
            }
        )
    }

}