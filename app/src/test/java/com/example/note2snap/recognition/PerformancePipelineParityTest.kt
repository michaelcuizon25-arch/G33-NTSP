package com.example.note2snap.recognition

import android.graphics.BitmapFactory
import com.example.note2snap.correction.bundledCorrectorForTest
import com.example.note2snap.diagnostics.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

/** Native image processing + existing CRNN transforms/CTC, with a fake native interpreter. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerformancePipelineParityTest {
    private val charset = File("src/main/assets/ocr/note2snap_crnn_v2_epoch03_charlist.txt")
        .readText().trimEnd('\r', '\n').map { it.toString() }
    private inner class Interpreter : CrnnInterpreterAccess {
        val inputs = mutableListOf<ByteArray>()
        var closed = false
        private val logits = ByteBuffer.allocate(128 * 80 * 4).order(ByteOrder.nativeOrder()).apply {
            val word = "cornputer"
            for (t in 0 until 128) for (c in 0 until 80) {
                val token = if (t % 2 == 0 && t / 2 < word.length) charset.indexOf(word[t / 2].toString()) else 79
                putFloat(if (c == token) 12f else -12f)
            }
            rewind()
        }
        override fun inputShape() = intArrayOf(1, 512, 32)
        override fun inputType() = "FLOAT32"
        override fun inputShapeSignature() = inputShape()
        override fun outputShape() = intArrayOf(1, 1, 80)
        override fun outputType() = "FLOAT32"
        override fun outputShapeSignature() = intArrayOf(-1, -1, 80)
        override fun outputNumBytes() = 128 * 80 * 4
        override fun invoke(input: ByteBuffer) { inputs += ByteArray(input.remaining()).also { input.duplicate().get(it) } }
        override fun outputBuffer() = logits.asReadOnlyBuffer()
        override fun close() { closed = true }
    }
    private data class Run(val result: RecognitionPipelineResult, val inputs: List<ByteArray>, val events: List<PerfEvent>)

    private suspend fun execute(enabled: Boolean, exports: Boolean = true): Run {
        val events = mutableListOf<PerfEvent>()
        val session = PerformanceSession(PerfFlow.SINGLE_GALLERY, enabled, System::nanoTime, { false }, events::add)
        val interpreter = Interpreter()
        val photo = javaClass.classLoader!!.getResourceAsStream("board_test_5.jpg").use { BitmapFactory.decodeStream(it) }
        val result = try {
            withContext(PerformanceDiagnostics.context(session)) {
                val runtime = TensorFlowLiteCrnnRuntime(interpreter)
                val recognizer = CrnnLineRecognizer(RuntimeEnvironment.getApplication(), CrnnPreprocessingMode.LEGACY, runtime, charset, exportDiagnostics = exports)
                val pipeline = WhiteboardRecognitionPipeline(recognizer, bundledCorrectorForTest())
                try { pipeline.process(photo) } finally { pipeline.close() }
            }
        } finally { photo.recycle(); session.close() }
        assertTrue(interpreter.closed)
        return Run(result, interpreter.inputs, events)
    }

    @Test fun instrumentationPreservesTensorsDecodedTextCorrectionStructuringAndVisuals() = runBlocking {
        val off = execute(false)
        val on = execute(true)
        assertTrue(off.events.isEmpty())
        assertTrue(on.inputs.isNotEmpty())
        assertEquals(off.inputs.size, on.inputs.size)
        off.inputs.zip(on.inputs).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertEquals(off.result.recognizedLines, on.result.recognizedLines)
        assertEquals(off.result.correctedLines, on.result.correctedLines)
        assertTrue(on.result.recognizedLines.all { it.text == "cornputer" })
        assertTrue(on.result.correctedLines.all { it.text == "computer" })
        assertEquals(off.result.structuredNote, on.result.structuredNote)
        assertTrue(off.result.diagramRegions.isNotEmpty())
        off.result.diagramRegions.zip(on.result.diagramRegions).forEach { (a, b) ->
            assertEquals(a.boundingBox, b.boundingBox)
            assertTrue(a.croppedBitmap.sameAs(b.croppedBitmap))
        }
        val inference = on.events.single { it.kind == PerfEventKind.STAGE && it.stage == PerfStage.CRNN_INFERENCE }
        assertEquals(on.inputs.size.toLong(), inference.count)
        assertEquals(on.inputs.size, on.events.count { it.kind == PerfEventKind.LINE_INFERENCE })
        assertTrue(on.events.any { it.stage == PerfStage.DIAGNOSTIC_IO })
        assertTrue(on.events.any { it.stage == PerfStage.OFFLINE_CORRECTION })
        val total = on.events.single { it.kind == PerfEventKind.TOTAL }
        assertEquals(on.inputs.size.toLong(), total.metrics[PerfMetric.PHYSICAL_LINES])
        assertEquals(0L, total.metrics[PerfMetric.OPENCV_REQUESTED])
        assertEquals(1L, total.metrics[PerfMetric.CORRECTION_ENABLED])
        val rows = JSONArray()
        on.events.filter { it.kind == PerfEventKind.STAGE }.forEach { event ->
            rows.put(JSONObject().put("stage", event.stage!!.name).put("count", event.count)
                .put("totalMs", event.elapsedNs / 1_000_000.0).put("maxMs", event.maxNs / 1_000_000.0))
        }
        File("build/performance-host-pipeline.json").writeText(JSONObject()
            .put("scope", "Host Robolectric native graphics; fake interpreter output, not phone/Flex inference")
            .put("totalMs", total.elapsedNs / 1_000_000.0).put("stages", rows).toString(2))
    }
    @Test fun defaultExportsOffPreserveRecognitionAndRemoveFileIo() = runBlocking {
        val detailed = execute(true, exports = true)
        val normal = execute(true, exports = false)
        assertEquals(detailed.result.recognizedLines, normal.result.recognizedLines)
        assertEquals(detailed.result.correctedLines, normal.result.correctedLines)
        assertEquals(detailed.result.structuredNote, normal.result.structuredNote)
        detailed.inputs.zip(normal.inputs).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertEquals(detailed.inputs.size, normal.inputs.size)
        assertFalse(normal.events.any { it.stage == PerfStage.DIAGNOSTIC_IO })
        fun report(run: Run) = JSONObject().put("totalMs", run.events.single { it.kind == PerfEventKind.TOTAL }.elapsedNs / 1_000_000.0)
            .put("diagnosticIoMs", run.events.filter { it.kind == PerfEventKind.STAGE && it.stage == PerfStage.DIAGNOSTIC_IO }.sumOf { it.elapsedNs } / 1_000_000.0)
            .put("stages", JSONArray(run.events.filter { it.kind == PerfEventKind.STAGE }.map {
                JSONObject().put("stage", it.stage!!.name).put("ms", it.elapsedNs / 1_000_000.0)
            }))
        File("build/performance-stage25.json").writeText(JSONObject()
            .put("scope", "Host native graphics with fake interpreter; ON/OFF ordered pair, not Android latency")
            .put("researchExports", report(detailed)).put("normalScanning", report(normal)).toString(2))
    }

}
