package com.example.note2snap.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleNoiseTest {

    @Test
    fun ignoresRepetitiveNoiseForTitle() {
        val note = WhiteboardRuleEngine.process(
            listOf(
                "AoOOO",
                "Top Budget Options Under 4000",
                "POCO C71: Affordable Android phone",
                "Tecno Spark Go 1: Large battery"
            )
        )

        assertEquals(
            "Top Budget Options Under 4000",
            note.title
        )
    }

    @Test
    fun keepsNormalOneWordTitle() {
        val note = WhiteboardRuleEngine.process(
            listOf(
                "Photosynthesis",
                "Sunlight: Provides energy",
                "Water: Absorbed by roots"
            )
        )

        assertEquals("Photosynthesis", note.title)
    }
}