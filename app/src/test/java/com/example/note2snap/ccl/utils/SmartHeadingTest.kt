package com.example.note2snap.utils

import com.example.note2snap.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartHeadingTest {

    @Test
    fun recognizesHeadingBeforeBullets() {
        val note = WhiteboardRuleEngine.process(
            listOf(
                "Elements of Design",
                "Types of Lines",
                "• Horizontal",
                "• Vertical"
            )
        )

        val heading = note.blocks.first {
            it.rawText == "Types of Lines"
        }

        assertEquals(BlockType.SUBHEADING, heading.type)
    }

    @Test
    fun doesNotTurnExplanationIntoHeading() {
        val note = WhiteboardRuleEngine.process(
            listOf(
                "Photosynthesis",
                "Plants need water",
                "Water: Essential for growth"
            )
        )

        val explanation = note.blocks.first {
            it.rawText == "Plants need water"
        }

        assertEquals(BlockType.REGULAR_TEXT, explanation.type)
    }

    @Test
    fun preservesNumberedItems() {
        val note = WhiteboardRuleEngine.process(
            listOf(
                "Scientific Method",
                "1. Observation",
                "2. Experiment"
            )
        )

        val items = note.blocks.filter {
            it.type == BlockType.NUMBERED_ITEM
        }

        assertEquals(2, items.size)
    }
}