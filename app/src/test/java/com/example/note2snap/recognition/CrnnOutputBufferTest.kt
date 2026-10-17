package com.example.note2snap.recognition

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CrnnOutputBufferTest {
    @Test fun nativeByteLengthControlsSequenceAndFinalFrameSurvivesWithStaleCachedShape() {
        for (steps in listOf(7, 128, 256)) {
            val buffer = ByteBuffer.allocate(steps * 80 * 4).order(ByteOrder.nativeOrder())
            repeat(steps * 80) { buffer.putFloat(it.toFloat()) }
            buffer.rewind()
            val output = readCrnnLogits(buffer.asReadOnlyBuffer(), buffer.remaining(), intArrayOf(1, 1, 80),
                intArrayOf(-1, -1, 80), "FLOAT32", intArrayOf(1, steps * 4, 32))
            assertEquals(steps, output[0].size)
            assertEquals(0f, output[0][0][0], 0f)
            assertEquals((steps * 80 - 1).toFloat(), output[0][steps - 1][79], 0f)
            assertEquals(0, buffer.position())
        }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid output must not be accepted or truncated") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun actualOneStepOutputIsRejectedForFixedWidthModelInsteadOfBeingAcceptedAsPlaceholder() {
        val buffer = ByteBuffer.allocate(80 * 4)
        rejects { readCrnnLogits(buffer, buffer.remaining(), intArrayOf(1, 1, 80),
            intArrayOf(-1, -1, 80), "FLOAT32", intArrayOf(1, 512, 32)) }
    }

    @Test fun truncatedBytesIncorrectTypesAndIncompatibleSignaturesAreRejected() {
        val bytes = 128 * 80 * 4
        val shape = intArrayOf(1, 1, 80)
        val signature = intArrayOf(-1, -1, 80)
        val input = intArrayOf(1, 512, 32)
        rejects { readCrnnLogits(ByteBuffer.allocate(bytes - 4), bytes, shape, signature, "FLOAT32", input) }
        rejects { readCrnnLogits(ByteBuffer.allocate(bytes), bytes - 1, shape, signature, "FLOAT32", input) }
        rejects { readCrnnLogits(ByteBuffer.allocate(bytes), bytes, shape, signature, "INT8", input) }
        rejects { readCrnnLogits(ByteBuffer.allocate(bytes), bytes, shape, intArrayOf(-1, -1, 79), "FLOAT32", input) }
        rejects { readCrnnLogits(ByteBuffer.allocate(bytes), bytes, shape, intArrayOf(-1, 64, 80), "FLOAT32", input) }
    }
}
