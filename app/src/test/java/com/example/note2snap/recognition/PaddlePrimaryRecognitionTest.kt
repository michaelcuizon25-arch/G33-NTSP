package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.note2snap.correction.bundledCorrectorForTest
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddlePrimaryRecognitionTest {
    private val characters = listOf("") + File("src/main/assets/ocr/paddle/ppocrv5_en_dict.txt").readLines() + " "
    private fun handwriting(color: Int = Color.BLACK): Bitmap = Bitmap.createBitmap(300, 60, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawText("hello world", 8f, 42f, Paint().apply { this.color = color; textSize = 32f })
    }
    private fun region(id: Int = 7, bitmap: Bitmap = handwriting(), type: RegionType = RegionType.TEXT) =
        Region(id, Rect(10, 20, 10 + bitmap.width, 20 + bitmap.height), type, 1000, bitmap)
    private fun output(text: String): PaddleOutput {
        val tokens = text.flatMap { listOf(characters.indexOf(it.toString()), 0) }.ifEmpty { listOf(0) }
        return PaddleOutput(FloatArray(tokens.size * 438) { i ->
            if (i % 438 == tokens[i / 438]) 1f / 438 + 1e-6f else 1f / 438
        }, tokens.size, 438)
    }
    private class Runtime(val action: () -> PaddleOutput) : PaddleRecognitionRuntime {
        var calls = 0; var closes = 0
        override fun run(input: PaddleInput): PaddleOutput { calls++; return action() }
        override fun close() { closes++ }
    }
    private fun paddle(runtime: Runtime) = PaddleOcrLineRecognizer(runtime,
        PaddleInputPreprocessor { _, width -> ByteArray(width * 48 * 3) }, PaddleCtcDecoder(characters))

    @Test fun lowProbabilityUsableTextWinsWithoutConstructingCrnnAndSessionIsReused() = runBlocking {
        val runtime = Runtime { output("primary") }; var creations = 0
        val engine = PaddlePrimaryLineRecognizer({ creations++; paddle(runtime) }, { error("CRNN must stay lazy") })
        val inputs = listOf(region(), region(8))
        val expected = inputs.flatMap { PhysicalLineSegmenter().splitIntoPhysicalLines(it) }
        try {
            val result = engine.recognize(inputs)
            assertEquals(2, result.size); assertTrue(result.all { it.text == "primary" && it.confidence < 0.01f })
            assertEquals(listOf(7, 8), result.map { it.sourceRegionId })
            assertEquals(expected.map { it.boundingBox }, result.map { it.boundingBox })
            assertEquals(1, creations); assertEquals(2, runtime.calls)
        } finally { engine.close(); engine.close() }
        assertEquals(1, runtime.closes)
    }

    @Test fun failedPaddleConstructionIsNotRetriedAndFallbackGetsExactPhysicalCrops() = runBlocking {
        val inputs = listOf(region(), region(8)); val expected = inputs.flatMap { PhysicalLineSegmenter().splitIntoPhysicalLines(it) }
        var paddleCreations = 0; var crnnCreations = 0; var fallbackCalls = 0; var closed = false
        val engine = PaddlePrimaryLineRecognizer({ paddleCreations++; error("Asset unavailable") }, {
            crnnCreations++
            object : LineRecognizer {
                override suspend fun recognize(regions: List<Region>): List<RecognizedLine> {
                    val r = regions.single(); val line = expected[fallbackCalls++]
                    assertEquals(line.boundingBox, r.boundingBox); assertTrue(line.bitmap.sameAs(r.croppedBitmap))
                    return listOf(RecognizedLine(r.id, r.boundingBox, "fallback", 0.1f))
                }
                override fun close() { closed = true }
            }
        })
        try { assertEquals(listOf("fallback", "fallback"), engine.recognize(inputs).map { it.text }) }
        finally { engine.close() }
        assertEquals(1, paddleCreations); assertEquals(1, crnnCreations); assertEquals(2, fallbackCalls); assertTrue(closed)
    }

    @Test fun blankAndUnusablePredictionsInvokeFallbackRatherThanManufacturingText() = runBlocking {
        for (prediction in listOf("", "...")) {
            var fallbacks = 0
            val engine = PaddlePrimaryLineRecognizer({ paddle(Runtime { output(prediction) }) }, {
                object : LineRecognizer {
                    override suspend fun recognize(regions: List<Region>): List<RecognizedLine> {
                        fallbacks++; val r = regions.single(); return listOf(RecognizedLine(r.id, r.boundingBox, "CRNN text", 0.1f))
                    }
                    override fun close() { }
                }
            })
            try { assertEquals("CRNN text", engine.recognize(listOf(region())).single().text) } finally { engine.close() }
            assertEquals(1, fallbacks)
        }
    }

    @Test fun unsupportedDimensionsOrNativeFailureUseCrnnFallback() = runBlocking {
        for (error in listOf(IllegalArgumentException("width unsupported"), UnsatisfiedLinkError("ABI unavailable"))) {
            var calls = 0
            val runtime = Runtime { throw error }
            val engine = PaddlePrimaryLineRecognizer({ paddle(runtime) }, {
                object : LineRecognizer {
                    override suspend fun recognize(regions: List<Region>): List<RecognizedLine> { calls++; return emptyList() }
                    override fun close() { }
                }
            })
            try { assertTrue(engine.recognize(listOf(region())).isEmpty()) } finally { engine.close() }
            assertEquals(1, calls)
        }
    }

    @Test fun uniformNoiseDotsFramesAndNonTextNeverInitializeEitherEngine() = runBlocking {
        val blank = Bitmap.createBitmap(320, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val offWhite = blank.copy(Bitmap.Config.ARGB_8888, true).apply { eraseColor(Color.rgb(245, 245, 245)) }
        val noise = blank.copy(Bitmap.Config.ARGB_8888, true).apply {
            val random = java.util.Random(42)
            for (y in 0 until height) for (x in 0 until width) { val v = 245 + random.nextInt(11); setPixel(x, y, Color.rgb(v, v, v)) }
        }
        val dot = blank.copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(160, 24, Color.BLACK) }
        val frame = blank.copy(Bitmap.Config.ARGB_8888, true).apply {
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
            Canvas(this).drawRect(20f, 8f, 290f, 39f, paint)
        }
        val engine = PaddlePrimaryLineRecognizer({ error("No Paddle inference on non-text") }, { error("No CRNN inference on non-text") })
        try {
            val inputs = listOf(blank, offWhite, noise, dot, frame).mapIndexed { i, b -> region(i, b) } + region(99, type = RegionType.NON_TEXT)
            assertTrue(engine.recognize(inputs).isEmpty())
        } finally { engine.close() }
    }

    @Test fun faintLocalizedHandwritingIsNotRejectedByTheNoiseGuard() = runBlocking {
        assertNull(PaddlePrimaryInputGuard.rejectionReason(handwriting(Color.rgb(250, 250, 250))))
        // Existing segmenter intentionally clamps its ink threshold to 230; do not change that contract here.
        val bitmap = handwriting(Color.rgb(225, 225, 225))
        assertNull(PaddlePrimaryInputGuard.rejectionReason(bitmap))
        val engine = PaddlePrimaryLineRecognizer({ paddle(Runtime { output("faint") }) }, { error("Do not reject solely on low probability") })
        try { assertEquals("faint", engine.recognize(listOf(region(bitmap = bitmap))).single().text) }
        finally { engine.close() }
    }

    @Test fun cancellationDoesNotTriggerFallback() = runBlocking {
        val engine = PaddlePrimaryLineRecognizer({ paddle(Runtime { throw CancellationException("cancel") }) }, { error("No fallback on cancellation") })
        try { engine.recognize(listOf(region())); fail("Cancellation must propagate") }
        catch (_: CancellationException) { } finally { engine.close() }
    }

    @Test fun failureOfBothEnginesDoesNotBecomeSuccessfulEmptyRecognition() = runBlocking {
        val engine = PaddlePrimaryLineRecognizer({ error("Paddle failure") }, { error("CRNN failure") })
        try { engine.recognize(listOf(region())); fail("Both engines failed") }
        catch (error: IllegalStateException) { assertEquals("Single-scan recognition failed", error.message) }
        finally { engine.close() }
    }

    @Test fun primaryOutputPreservesExistingCorrectionStructuringAndDiagramPipeline() = runBlocking {
        val photo = javaClass.classLoader!!.getResourceAsStream("board_test_5.jpg").use { BitmapFactory.decodeStream(it) }
        val original = photo.copy(Bitmap.Config.ARGB_8888, false)
        val primary = PaddlePrimaryLineRecognizer({ paddle(Runtime { output("cornputer") }) }, { error("No fallback expected for fixture") })
        val pipeline = WhiteboardRecognitionPipeline(primary, bundledCorrectorForTest())
        val result = try { pipeline.process(photo) } finally { pipeline.close() }
        assertTrue(result.recognizedLines.isNotEmpty())
        assertTrue(result.recognizedLines.all { it.text == "cornputer" })
        assertTrue(result.correctedLines.all { it.text == "computer" })
        assertTrue(result.diagramRegions.isNotEmpty())
        assertTrue(photo.sameAs(original))
        val reference = WhiteboardRecognitionPipeline(object : LineRecognizer {
            override suspend fun recognize(regions: List<Region>) = result.recognizedLines
            override fun close() { }
        }, bundledCorrectorForTest())
        val expected = try { reference.process(photo) } finally { reference.close() }
        assertEquals(expected.structuredNote, result.structuredNote)
        assertEquals(expected.correctedLines, result.correctedLines)
        assertEquals(expected.diagramRegions.map { it.boundingBox }, result.diagramRegions.map { it.boundingBox })
        expected.diagramRegions.zip(result.diagramRegions).forEach { (a, b) -> assertTrue(a.croppedBitmap.sameAs(b.croppedBitmap)) }
        photo.recycle(); original.recycle()
    }

    @Test fun fallbackCrnnProviderDoesNotSegmentOrMoveTheSuppliedLineAgain() = runBlocking {
        val input = region(); val expected = CrnnInputPreprocessor().preprocess(input.croppedBitmap)
        var invokes = 0
        val runtime = object : CrnnInferenceRuntime {
            override val inputShape = intArrayOf(1, 512, 32)
            override val outputShape = intArrayOf(1, 128, 80)
            override val inputType = "FLOAT32"; override val outputType = "FLOAT32"
            override fun run(buffer: ByteBuffer): Array<Array<FloatArray>> {
                invokes++; val a = ByteArray(expected.remaining()).also { expected.duplicate().get(it) }
                val b = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }; assertArrayEquals(a, b)
                return Array(1) { Array(128) { FloatArray(80) { -12f }.apply { this[79] = 12f } } }
            }
            override fun close() { }
        }
        val charset = File("src/main/assets/ocr/note2snap_crnn_v2_epoch03_charlist.txt").readText().trimEnd('\r', '\n').map { it.toString() }
        val engine = CrnnLineRecognizer(RuntimeEnvironment.getApplication(), CrnnPreprocessingMode.LEGACY, runtime, charset,
            physicalLineProvider = { listOf(PhysicalLine(it.croppedBitmap, it.boundingBox)) })
        try { assertTrue(engine.recognize(listOf(input)).isEmpty()) } finally { engine.close() }
        assertEquals(1, invokes)
    }
}
