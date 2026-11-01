package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddleRecognitionTest {
    private val characters = listOf("") + File("src/main/assets/ocr/paddle/ppocrv5_en_dict.txt").readLines() + " "
    private fun output(vararg indices: Int) = PaddleOutput(FloatArray(indices.size * 438).apply {
        indices.forEachIndexed { step, index -> this[step * 438 + index] = 1f }
    }, indices.size, 438)

    @Test fun dictionaryAndBundledHashesMatchPinnedContract() {
        val root = File("src/main/assets/ocr/paddle")
        fun hash(name: String) = java.security.MessageDigest.getInstance("SHA-256").digest(File(root, name).readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(438, characters.size)
        assertEquals(PaddleModelAssets.MODEL_SHA256, hash("en_PP-OCRv5_mobile_rec.onnx"))
        assertEquals(PaddleModelAssets.DICTIONARY_SHA256, hash("ppocrv5_en_dict.txt"))
    }

    @Test fun ctcCollapsesRunsBeforeRemovingBlankAndPreservesSpacesAndUnicode() {
        val a = characters.indexOf("A"); val psi = characters.indexOf("𝜓")
        val result = PaddleCtcDecoder(characters).decode(output(0, a, a, 0, a, 437, 437, psi, 0))
        assertEquals("AA 𝜓", result.text)
        assertEquals(1f, result.meanSelectedProbability, 0f)
        assertEquals("", PaddleCtcDecoder(characters).decode(output(0, 0, 0)).text)
    }

    @Test fun decoderRejectsNonFiniteOrIncompatibleOutput() {
        for (bad in listOf(PaddleOutput(FloatArray(80), 1, 80), output(0).also { it.values[7] = Float.NaN })) {
            try { PaddleCtcDecoder(characters).decode(bad); fail("Must reject incompatible output") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun dynamicGeometryMatchesOfficialFractionalWidthsWithoutClippingLongLines() {
        assertEquals(320 to 48, PaddleInputPreprocessor.geometry(48, 48))
        assertEquals(517 to 517, PaddleInputPreprocessor.geometry(970, 90))
        assertEquals(3200 to 3200, PaddleInputPreprocessor.geometry(3200, 48))
        try { PaddleInputPreprocessor.geometry(3201, 48); fail("No silent truncation") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun tensorIsBgrNchwNormalizedWithZeroRightPaddingAndUnchangedSource() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val preprocessor = PaddleInputPreprocessor { source, width ->
            assertSame(bitmap, source); assertEquals(48, width)
            ByteArray(48 * width * 3).apply { for (i in indices step 3) this[i + 2] = 255.toByte() }
        }
        val input = preprocessor.prepare(bitmap)
        assertArrayEquals(longArrayOf(1, 3, 48, 320), input.shape)
        assertEquals(0, input.buffer.position())
        assertEquals(-1f, input.buffer.getFloat(0), 0f)
        assertEquals(-1f, input.buffer.getFloat(48 * 320 * 4), 0f)
        assertEquals(1f, input.buffer.getFloat(2 * 48 * 320 * 4), 0f)
        assertEquals(0f, input.buffer.getFloat(48 * 4), 0f)
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        bitmap.recycle()
    }

    private val crnnCharset = File("src/main/assets/ocr/note2snap_crnn_v2_epoch03_charlist.txt")
        .readText().trimEnd('\r', '\n').map { it.toString() }
    private class Runtime : CrnnInferenceRuntime {
        override val inputShape = intArrayOf(1, 512, 32)
        override val outputShape = intArrayOf(1, 128, 80)
        override val inputType = "FLOAT32"
        override val outputType = "FLOAT32"
        val inputs = mutableListOf<ByteArray>()
        var closed = false
        override fun run(input: ByteBuffer): Array<Array<FloatArray>> {
            inputs += ByteArray(input.remaining()).also { input.duplicate().get(it) }
            val a = File("src/main/assets/ocr/note2snap_crnn_v2_epoch03_charlist.txt").readText().indexOf('A')
            return Array(1) { Array(128) { step -> FloatArray(80) { -5f }.apply { this[if (step == 0) a else 79] = 5f } } }
        }
        override fun close() { closed = true }
    }
    private fun region(): Region {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in 8..19) for (x in 5..114) bitmap.setPixel(x, y, Color.BLACK)
        return Region(7, Rect(10, 20, 130, 80), RegionType.TEXT, 1000, bitmap)
    }

    @Test fun comparisonGetsIdenticalLinePixelsAndCannotReplaceCrnnResults() = runBlocking {
        val region = region()
        val expected = PhysicalLineSegmenter().splitIntoPhysicalLines(region)
        val off = Runtime(); val on = Runtime(); var calls = 0; var closed = false
        val observer = object : CrnnLineComparison {
            override suspend fun compare(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
                crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long) {
                assertEquals(7, regionId); assertEquals(expected[calls].boundingBox, box)
                assertTrue(expected[calls].bitmap.sameAs(bitmap)); assertEquals("A", crnnText)
                assertTrue(crnnPreprocessingNs >= 0 && crnnInferenceNs >= 0 && crnnDecodingNs >= 0)
                calls++
                throw IllegalStateException("Secondary engine failure must not suppress primary results")
            }
            override fun close() { closed = true }
        }
        fun crnn(runtime: Runtime, comparison: CrnnLineComparison?) = CrnnLineRecognizer(
            RuntimeEnvironment.getApplication(), CrnnPreprocessingMode.LEGACY, runtime, crnnCharset, comparison = comparison)
        val primary = crnn(off, null); val comparing = crnn(on, observer)
        try {
            assertEquals(primary.recognize(listOf(region)), comparing.recognize(listOf(region)))
            assertEquals(expected.size, calls)
            off.inputs.zip(on.inputs).forEach { (a, b) -> assertArrayEquals(a, b) }
            assertEquals(off.inputs.size, on.inputs.size)
        } finally { primary.close(); comparing.close() }
        assertTrue(off.closed && on.closed && closed)
    }

    @Test fun comparisonCancellationPropagates() = runBlocking {
        val observer = object : CrnnLineComparison {
            override suspend fun compare(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
                crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long) { throw CancellationException("cancel") }
            override fun close() { }
        }
        val recognizer = CrnnLineRecognizer(RuntimeEnvironment.getApplication(), CrnnPreprocessingMode.LEGACY,
            Runtime(), crnnCharset, comparison = observer)
        try { recognizer.recognize(listOf(region())); fail("Cancellation must propagate") }
        catch (_: CancellationException) { } finally { recognizer.close() }
    }
}
