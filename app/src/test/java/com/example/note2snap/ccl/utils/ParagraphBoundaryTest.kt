
package com.example.note2snap.utils

import android.graphics.Rect
import com.example.note2snap.recognition.RecognizedLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ParagraphBoundaryTest {

    private fun line(
        id: Int,        text: String,
        left: Int,
        top: Int,
        right: Int
    ) = RecognizedLine(
        id,
        Rect(left, top, right, top + 40),
        text,
        0.9f
    )

    @Test
    fun mergesCloselyAlignedParagraphLines() {
        val lines = listOf(
            line(1, "BUDGET PHONES", 100, 0, 550),
            line(2, "The cheapest Android phone under 4000 with", 100, 100, 780),
            line(3, "a large battery in the Philippines is the POCO", 101, 146, 780),
            line(4, "C71 or Redmi A5, both priced around 3599", 101, 192, 780)
        )

        val result = WhiteboardRuleEngine.processRecognizedLines(lines)

        assertTrue(
            "Paragraph continuation should be merged",
            result.blocks.any {
                it.rawText.contains("Philippines is the POCO") &&
                        it.rawText.contains("C71 or Redmi A5")
            }
        )
    }

    @Test
    fun protectsHeadingFromRepetitiveNoise() {
        val lines = listOf(
            line(1, "BUDGET PHONES", 100, 0, 550),
            line(2, "Top Budget Options Under", 100, 100, 750),
            line(3, "AoOOO", 101, 146, 260),
            line(4, "• POCO C71", 120, 200, 420),
            line(5, "• Redmi A5", 120, 255, 420)
        )

        val result = WhiteboardRuleEngine.processRecognizedLines(lines)

        assertFalse(
            "Heading must not merge with repetitive noise",
            result.blocks.any {
                it.rawText.contains("Top Budget Options Under AoOOO")
            }
        )

        assertTrue(
            "Original heading text must remain",
            result.blocks.any {
                it.rawText == "Top Budget Options Under"
            }
        )
    }
}
