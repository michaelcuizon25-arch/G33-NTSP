package com.example.note2snap.utils

import com.example.note2snap.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add under app/src/test/java/com/example/note2snap/utils/ */
class WhiteboardReviewerFormattingTest {

    @Test
    fun sentenceDefinitionEmphasizesTermWithoutChangingRawText() {
        val original = "A binary tree is a hierarchical data structure."
        val note = WhiteboardRuleEngine.process(listOf("Binary Tree", original))
        val definition = note.blocks.first { it.rawText == original }
        assertEquals(BlockType.KEY_DEFINITION, definition.type)
        assertEquals(original, definition.rawText)
        assertTrue(definition.formattedText.contains("<b>binary tree</b>"))
    }

    @Test
    fun colonDefinitionUsesNoArtificialBullet() {
        val note = WhiteboardRuleEngine.process(listOf("Binary Tree", "Node: A unit in a tree"))
        val definition = note.blocks.first { it.rawText.startsWith("Node:") }
        assertEquals(BlockType.KEY_DEFINITION, definition.type)
        assertEquals("<b>Node</b>: A unit in a tree", definition.formattedText)
    }

    @Test
    fun explicitBulletDefinitionPreservesBullet() {
        val note = WhiteboardRuleEngine.process(listOf("Traversals:", "• Preorder: Root Left Right"))
        val definition = note.blocks.first { it.rawText.startsWith("• Preorder:") }
        assertEquals(BlockType.KEY_DEFINITION, definition.type)
        assertTrue(definition.formattedText.startsWith("• <b>Preorder</b>:"))
    }

    @Test
    fun ordinarySentenceStaysPlainAndIsEscaped() {
        val note = WhiteboardRuleEngine.process(listOf("The student is reading a book.", "Water < ice"))
        assertEquals(BlockType.REGULAR_TEXT, note.blocks[0].type)
        assertEquals("Water &lt; ice", note.blocks[1].formattedText)
    }

    @Test
    fun cleanReviewerLayoutIsDefault() {
        assertFalse(WhiteboardRuleEngine.cornellLayout)
    }
}
