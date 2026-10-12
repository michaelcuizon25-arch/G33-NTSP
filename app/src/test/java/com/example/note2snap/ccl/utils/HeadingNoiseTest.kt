package com.example.note2snap.utils

import com.example.note2snap.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingNoiseTest {

    @Test
    fun repetitiveFragmentIsNotHeading() {
        val note = WhiteboardRuleEngine.process(
            listOf(
                "Photosynthesis",
                "AoOOO",
                "• Sunlight",
                "• Water"
            )
        )

        val fragment = note.blocks.first {
            it.rawText == "AoOOO"
        }

        assertEquals(BlockType.REGULAR_TEXT, fragment.type)
    }
}