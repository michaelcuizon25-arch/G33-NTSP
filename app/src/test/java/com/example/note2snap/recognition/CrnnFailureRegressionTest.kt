package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.structuring.PipelineTextStructurer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrnnFailureRegressionTest {
    private val charset = " !\"#&'()*+,-./0123456789:;?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".map { it.toString() }
    private class FakeRuntime(val action: (ByteBuffer) -> Array<Array<FloatArray>>) : CrnnInferenceRuntime {
        override val inputShape = intArrayOf(1, 512, 32)
        override val outputShape = intArrayOf(1, 128, 80)
        override val inputType = "FLOAT32"
        override val outputType = "FLOAT32"
        var calls = 0
        var closed = false
        override fun run(input: ByteBuffer): Array<Array<FloatArray>> { calls++; return action(input) }
        override fun close() { closed = true }
    }
    private fun recognizer(runtime: FakeRuntime, mode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY) =
        CrnnLineRecognizer(RuntimeEnvironment.getApplication(), mode, runtime, charset, exportDiagnostics = true)
    private fun region(twoLines: Boolean = false): Region {
        val b = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in 8..19) for (x in 5..114) b.setPixel(x, y, Color.BLACK)
        if (twoLines) for (y in 32..43) for (x in 5..114) b.setPixel(x, y, Color.BLACK)
        return Region(7, Rect(0, 0, 120, 60), RegionType.TEXT, 1000, b)
    }
    private fun logits(text: String = ""): Array<Array<FloatArray>> {
        val result = Array(1) { Array(128) { FloatArray(80) { -5f }.apply { this[79] = 5f } } }
        text.forEachIndexed { i, c -> result[0][i * 2][charset.indexOf(c.toString())] = 10f }
        return result
    }
    private fun summary(r: CrnnLineRecognizer) = JSONObject(File(r.diagnostics.directory, "recognition-summary.json").readText())

    /** Models the confirmed TFLite 2.16.1 Java cache semantics, not model math. */
    private class DynamicOutputInterpreter : CrnnInterpreterAccess {
        var invoked = false
        var outputQueries = 0
        private var cachedShape: IntArray? = null
        override fun inputShape() = intArrayOf(1, 512, 32)
        override fun inputType() = "FLOAT32"
        override fun inputShapeSignature() = intArrayOf(1, 512, 32)
        override fun outputShape(): IntArray {
            outputQueries++
            if (cachedShape == null) cachedShape = if (invoked) intArrayOf(1, 128, 80) else intArrayOf(1, 1, 80)
            return cachedShape!!
        }
        override fun outputType(): String { outputShape(); return "FLOAT32" }
        override fun outputShapeSignature() = intArrayOf(-1, -1, 80)
        override fun outputNumBytes() = if (invoked) 128 * 80 * 4 else 80 * 4
        override fun invoke(input: ByteBuffer) { invoked = true }
        override fun outputBuffer(): ByteBuffer = ByteBuffer.allocateDirect(outputNumBytes())
            .order(ByteOrder.nativeOrder()).apply {
                repeat(128 * 80) { putFloat(it.toFloat()) }
                rewind()
            }.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        fun copyToArray(input: ByteBuffer, output: Array<Array<FloatArray>>) {
            invoke(input) // native output resolves; ByteBuffer does not trigger Java cache refresh
            val shape = outputShape()
            require(shape.contentEquals(intArrayOf(output.size, output[0].size, output[0][0].size))) {
                "Cannot copy from a TensorFlowLite tensor with shape ${shape.contentToString()} to [1,128,80]"
            }
        }
        override fun close() {}
    }

    @Test fun cachedPlaceholderBreaksArrayCopyButNativeBufferRetainsAllResolvedSteps() {
        val premature = DynamicOutputInterpreter()
        assertArrayEquals(intArrayOf(1, 1, 80), premature.outputShape())
        val input = CrnnInputPreprocessor().preprocess(region().croppedBitmap)
        try { premature.copyToArray(input, Array(1) { Array(128) { FloatArray(80) } }); fail("Must reproduce stale output shape") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("[1, 1, 80]")) }

        val adapter = DynamicOutputInterpreter()
        adapter.outputShape() // Deliberately recreate the stale cache before invocation.
        val runtime = TensorFlowLiteCrnnRuntime(adapter)
        assertTrue(runtime.outputShape.isEmpty())
        assertEquals("UNRESOLVED_BEFORE_INFERENCE", runtime.outputType)
        val recognizer = CrnnLineRecognizer(RuntimeEnvironment.getApplication(),
            CrnnPreprocessingMode.LEGACY, runtime, charset)
        assertEquals(1, adapter.outputQueries)
        val output = runtime.run(input)
        assertEquals(128, output[0].size)
        assertEquals(10239f, output[0][127][79], 0f)
        assertArrayEquals(intArrayOf(1, 1, 80), runtime.cachedOutputShape)
        assertArrayEquals(intArrayOf(-1, -1, 80), runtime.outputShapeSignature)
        recognizer.close()
        assertArrayEquals(intArrayOf(1, 128, 80), runtime.outputShape)
        assertEquals("FLOAT32", runtime.outputType)
        assertEquals(128, runtime.run(input)[0].size)
    }

    @Test fun inferenceFailuresPropagateWithOriginalCauseAndStageInsteadOfBecomingEmptyText() = runBlocking {
        val cause = IllegalArgumentException("Injected TFLite output shape mismatch")
        val runtime = FakeRuntime { throw cause }
        val r = recognizer(runtime)
        try {
            r.recognize(listOf(region()))
            fail("Inference failure must not return an empty successful recognition")
        } catch (e: CrnnRecognitionFailure) {
            assertSame(cause, e.cause)
            assertEquals(1, e.failedLines)
            assertEquals(1, e.physicalLines)
        } finally { r.close() }
        assertTrue(runtime.closed)
        assertEquals("recognition_failed", summary(r).getString("status"))
        val line = JSONObject(File(r.diagnostics.directory, "line-0000.json").readText())
        assertEquals("inference_error", line.getString("status"))
        assertTrue(ShadowLog.getLogsForTag(RECOGNITION_LOG_TAG).any { it.msg.contains("stage=inference") && it.throwable === cause })
    }

    @Test fun successfulBlankOutputsRemainDistinctFromRuntimeFailures() = runBlocking {
        val runtime = FakeRuntime { logits() }
        val r = recognizer(runtime)
        assertTrue(r.recognize(listOf(region())).isEmpty())
        assertEquals(1, runtime.calls)
        assertEquals("all_predictions_blank", summary(r).getString("status"))
        assertEquals(0, summary(r).getInt("failedLines"))
        assertEquals(1, summary(r).getInt("blankPredictions"))
        r.close()
    }

    @Test fun partialFailureKeepsReadableLinesAndRecordsCounts() = runBlocking {
        var call = 0
        val r = recognizer(FakeRuntime { if (call++ == 0) error("Injected failure") else logits("Notes") })
        val lines = r.recognize(listOf(region(twoLines = true)))
        assertEquals(listOf("Notes"), lines.map { it.text })
        assertEquals("partial_success", summary(r).getString("status"))
        assertEquals(2, summary(r).getInt("physicalLines"))
        assertEquals(1, summary(r).getInt("failedLines"))
        r.close()
    }

    @Test fun cancellationIsNeverConvertedToRecognitionFailure() = runBlocking {
        val cause = CancellationException("Cancelled scan")
        val r = recognizer(FakeRuntime { throw cause })
        try { r.recognize(listOf(region())); fail("Cancellation must propagate") }
        catch (e: CancellationException) { assertSame(cause, e) }
        finally { r.close() }
    }

    @Test fun bothModesStillSendTheirExactPreparedInputAndUseTheSameDecoder() = runBlocking {
        for (mode in CrnnPreprocessingMode.values()) {
            val region = region()
            val crop = PhysicalLineSegmenter().splitIntoPhysicalLines(region).single()
            val expected = CrnnPreprocessing(mode).prepare(crop.bitmap).input
            val runtime = FakeRuntime { input ->
                assertEquals(0, input.position())
                assertEquals(expected, input)
                logits("ELEMENTS")
            }
            val r = recognizer(runtime, mode)
            assertEquals("ELEMENTS", r.recognize(listOf(region)).single().text)
            r.close()
        }
    }

    @Test fun decoderReceivesLastNativeCtcFrameInsteadOfTruncatedPlaceholder() = runBlocking {
        val runtime = FakeRuntime {
            val frames = logits("A")
            frames[0][127][charset.indexOf("Z")] = 10f
            val buffer = ByteBuffer.allocate(128 * 80 * 4).order(ByteOrder.nativeOrder())
            frames[0].forEach { step -> step.forEach { buffer.putFloat(it) } }
            buffer.rewind()
            readCrnnLogits(buffer, buffer.remaining(), intArrayOf(1, 1, 80),
                intArrayOf(-1, -1, 80), "FLOAT32", intArrayOf(1, 512, 32))
        }
        val r = recognizer(runtime)
        assertEquals("AZ", r.recognize(listOf(region())).single().text)
        r.close()
    }

    @Test fun capturedNativeFlexOutputsDecodeExactlyLikePythonInBothPreprocessingModes() = runBlocking {
        fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("crnn_dynamic_native/$name")!!
        val manifest = JSONObject(resource("manifest.json").bufferedReader().use { it.readText() })
        val cases = manifest.getJSONArray("cases")
        assertEquals(2, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val bytes = GZIPInputStream(resource(c.getString("tensor"))).use { it.readBytes() }
            assertEquals(40960, bytes.size)
            val littleEndianFloats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val native = ByteBuffer.allocate(bytes.size).order(ByteOrder.nativeOrder())
            while (littleEndianFloats.hasRemaining()) native.putFloat(littleEndianFloats.get())
            native.rewind()
            val mode = CrnnPreprocessingMode.valueOf(c.getString("actualMode"))
            val r = recognizer(FakeRuntime {
                readCrnnLogits(native, native.remaining(), intArrayOf(1, 1, 80),
                    intArrayOf(-1, -1, 80), "FLOAT32", intArrayOf(1, 512, 32))
            }, mode)
            assertEquals(c.getString("prediction"), r.recognize(listOf(region())).single().text)
            r.close()
        }
    }

    @Test fun titleOnlyRecognitionRemainsReadableAndProducesEscapedEditorContent() {
        val lines = listOf(RecognizedLine(1, Rect(0, 0, 400, 40), "ELEMENTS OF DESIGN", 1f))
        val note = PipelineTextStructurer().structure(lines)
        assertTrue("Reproduces previous body-only rejection", note.blocks.isEmpty())
        assertEquals("ELEMENTS OF DESIGN", note.title)
        assertTrue(RecognitionReadability.hasReadableWriting(lines))
        assertEquals("<h1>ELEMENTS OF DESIGN</h1>", RecognitionReadability.htmlContent(note))
        assertEquals("<h1>A &amp; B &lt; C</h1>", RecognitionReadability.htmlContent(StructuredNote("A & B < C", emptyList())))
        assertFalse(RecognitionReadability.hasReadableWriting(emptyList()))
        assertFalse(RecognitionReadability.hasReadableWriting(listOf(lines.single().copy(text = "!?"))))
    }

    @Test fun fullPhotoPipelineLogsCountsAndPropagatesCrnnFailureBeforeStructuring() = runBlocking {
        val photo = javaClass.classLoader!!.getResourceAsStream("board_test_5.jpg").use { BitmapFactory.decodeStream(it) }
        val runtime = FakeRuntime { error("Injected phone inference failure") }
        val r = recognizer(runtime)
        val pipeline = WhiteboardRecognitionPipeline(r)
        try { pipeline.process(photo); fail("Pipeline must not structure a failed recognition as an empty note") }
        catch (e: CrnnRecognitionFailure) { assertTrue(e.failedLines > 0) }
        finally { pipeline.close() }
        assertTrue(runtime.calls > 0)
        assertTrue(summary(r).getInt("textRegions") > 0)
        assertTrue(ShadowLog.getLogsForTag(RECOGNITION_LOG_TAG).any { it.msg.startsWith("CCL totalRegions=") })
    }

    @Test fun noRegionsAndNoSupportedPhysicalLinesHaveSeparateDiagnosticStatuses() = runBlocking {
        val runtime = FakeRuntime { fail("No inference expected"); logits() }
        val r = recognizer(runtime)
        assertTrue(r.recognize(emptyList()).isEmpty())
        assertEquals("no_text_regions", summary(r).getString("status"))
        val white = region().also { it.croppedBitmap.eraseColor(Color.WHITE) }
        assertTrue(r.recognize(listOf(white)).isEmpty())
        assertEquals("no_physical_lines", summary(r).getString("status"))
        assertEquals(0, runtime.calls)
        r.close()
    }
}
