package com.example.note2snap.utils

import com.example.note2snap.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerGroupingTest {
    @Test
    fun groupsConsecutiveDefinitionsWithoutAnExistingHeading() {
        val note = WhiteboardRuleEngine.process(listOf(
            "Photosynthesis",
            "Sunlight: Supplies energy for the process.",
            "Water: Absorbed by the roots.",
            "Plants use energy to produce food."
        ))
        val concepts = note.blocks.indexOfFirst { it.rawText == "Key Concepts" }
        assertTrue("Expected inferred heading before consecutive definitions", concepts >= 0)
        assertEquals(BlockType.SECTION_HEADER, note.blocks[concepts].type)
        assertEquals("Sunlight: Supplies energy for the process.", note.blocks[concepts + 1].rawText)
        assertEquals("Water: Absorbed by the roots.", note.blocks[concepts + 2].rawText)
    }

    @Test
    fun doesNotDuplicateAnExistingTopicHeading() {
        val note = WhiteboardRuleEngine.process(listOf(
            "Photosynthesis",
            "Requirements:",
            "Sunlight: Supplies energy for the process.",
            "Water: Absorbed by the roots."
        ))
        assertTrue(note.blocks.none { it.rawText == "Key Concepts" })
    }

    @Test
    fun singleDefinitionDoesNotGetAnInventedSection() {
        val note = WhiteboardRuleEngine.process(listOf(
            "Binary Tree",
            "A binary tree is a hierarchical data structure.",
            "The root node has no parent."
        ))
        assertTrue(note.blocks.none { it.rawText == "Key Concepts" })
    }
}
