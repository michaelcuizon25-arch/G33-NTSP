package com.example.note2snap.recognition

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.BuildConfig
import com.example.note2snap.ccl.Region
import com.example.note2snap.correction.CorrectionResult
import com.example.note2snap.correction.OfflineTextCorrector
import com.example.note2snap.correction.bundledCorrectorForTest
import com.example.note2snap.structuring.PipelineTextStructurer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineCorrectionPipelineTest {
    private fun photo() = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        for (x in 15..95) for (y in 20..23) setPixel(x, y, Color.BLACK)
    }
    private val raw = listOf(
        RecognizedLine(7, Rect(10, 10, 100, 25), "cornputer", .64f),
        RecognizedLine(7, Rect(10, 30, 100, 45), "algorithrn", 1f)
    )
    private class FakeRecognizer(private val output: List<RecognizedLine>) : LineRecognizer {
        var closed = false
        override suspend fun recognize(regions: List<Region>) = output
        override fun close() { closed = true }
    }

    @Test fun disabledPathPreservesRawObjectsAndExistingStructuring() = runBlocking {
        assertFalse(BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION)
        val recognizer = FakeRecognizer(raw)
        val pipeline = WhiteboardRecognitionPipeline(recognizer)
        val result = try { pipeline.process(photo()) } finally { pipeline.close() }
        assertSame(raw, result.recognizedLines)
        assertSame(raw, result.correctedLines)
        assertTrue(result.textCorrections.isEmpty())
        assertEquals(PipelineTextStructurer().structure(raw), result.structuredNote)
        assertTrue(recognizer.closed)
    }

    @Test fun correctionRunsAfterEitherRecognizerContractAndBeforeUnchangedRules() = runBlocking {
        val pipeline = WhiteboardRecognitionPipeline(FakeRecognizer(raw), bundledCorrectorForTest())
        val result = try { pipeline.process(photo()) } finally { pipeline.close() }
        assertSame(raw, result.recognizedLines)
        assertEquals(listOf("computer", "algorithm"), result.correctedLines.map { it.text })
        for (i in raw.indices) {
            assertEquals(raw[i].boundingBox, result.correctedLines[i].boundingBox)
            assertSame(raw[i].boundingBox, result.correctedLines[i].boundingBox)
            assertEquals(raw[i].sourceRegionId, result.correctedLines[i].sourceRegionId)
            assertEquals(raw[i].confidence, result.correctedLines[i].confidence)
            assertEquals(i, result.textCorrections[i].lineIndex)
        }
        assertEquals(PipelineTextStructurer().structure(result.correctedLines), result.structuredNote)
        assertEquals(listOf("cornputer", "algorithrn"), raw.map { it.text })
    }

    @Test fun correctionCannotChangeDiagramRegions() = runBlocking {
        val image = javaClass.classLoader!!.getResourceAsStream("board_test_2.png").use { BitmapFactory.decodeStream(it) }
        val off = WhiteboardRecognitionPipeline(FakeRecognizer(raw))
        val on = WhiteboardRecognitionPipeline(FakeRecognizer(raw), bundledCorrectorForTest())
        val original = try { off.process(image) } finally { off.close() }
        val corrected = try { on.process(image) } finally { on.close() }
        assertTrue("Fixture must exercise preserved visual content", original.diagramRegions.isNotEmpty())
        assertEquals(original.diagramRegions.map { it.boundingBox }, corrected.diagramRegions.map { it.boundingBox })
        assertEquals(original.diagramRegions.map { it.type }, corrected.diagramRegions.map { it.type })
        original.diagramRegions.zip(corrected.diagramRegions).forEach { (a, b) -> assertTrue(a.croppedBitmap.sameAs(b.croppedBitmap)) }
    }

    @Test fun correctionFailureIsIsolatedPerLineAndRawTextSurvives() = runBlocking {
        val normal = bundledCorrectorForTest()
        val flaky = OfflineTextCorrector { if (it == "cornputer") error("failure") else normal.correct(it) }
        val pipeline = WhiteboardRecognitionPipeline(FakeRecognizer(raw), flaky)
        val result = try { pipeline.process(photo()) } finally { pipeline.close() }
        assertEquals(listOf("cornputer", "algorithm"), result.correctedLines.map { it.text })
        assertEquals("correction_error", result.textCorrections.first().result.skippedReason)
    }

    @Test fun incorrectOriginalReturnedByCorrectorFallsBack() {
        val results = correctRecognizedLines(raw, OfflineTextCorrector { CorrectionResult("other", "invented") })
        assertEquals(raw.map { it.text }, results.map { it.result.correctedText })
        assertTrue(results.all { it.result.skippedReason == "correction_error" })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsPropagated() {
        correctRecognizedLines(raw, OfflineTextCorrector { throw CancellationException() })
    }

    @Test fun emptyRecognitionDoesNotCreateContent() = runBlocking {
        val pipeline = WhiteboardRecognitionPipeline(FakeRecognizer(emptyList()), bundledCorrectorForTest())
        val result = try { pipeline.process(photo()) } finally { pipeline.close() }
        assertTrue(result.recognizedLines.isEmpty())
        assertTrue(result.correctedLines.isEmpty())
        assertTrue(result.structuredNote.blocks.isEmpty())
    }

    @Test fun correctionDiagnosticsPreserveRawTextSuggestionsAndEvidence() {
        val diagnostics = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        diagnostics.recordCorrections(true, correctRecognizedLines(raw, bundledCorrectorForTest()))
        val report = JSONObject(File(diagnostics.directory, "text-corrections.json").readText())
        assertTrue(report.getBoolean("enabled"))
        val first = report.getJSONArray("lines").getJSONObject(0)
        assertEquals("cornputer", first.getString("originalText"))
        assertEquals("computer", first.getString("correctedText"))
        assertEquals("REVIEWED_OCR_ALIAS", first.getJSONArray("suggestions").getJSONObject(0).getString("evidence"))
        assertTrue(report.getString("confidence").contains("No calibrated"))
    }

    @Test(expected = IllegalStateException::class)
    fun recognitionFailuresAreNotTurnedIntoSuccessfulCorrections(): Unit = runBlocking {
        val recognizer = object : LineRecognizer {
            override suspend fun recognize(regions: List<Region>): List<RecognizedLine> = error("recognition failed")
            override fun close() = Unit
        }
        val pipeline = WhiteboardRecognitionPipeline(recognizer, OfflineTextCorrector { error("must not run") })
        try { pipeline.process(photo()) } finally { pipeline.close() }
    }
}
