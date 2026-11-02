package com.example.note2snap.recognition

import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.structuring.PipelineTextStructurer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrimaryOcrDiagnosticsDeviceTest {
    @Test fun realPaddleAndOfflineCorrectionExportWithoutChangingPixels(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val image = instrumentation.context.assets.open("paddle_reference/fractional.png")
            .use { BitmapFactory.decodeStream(it) }
        val original = image.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "primary-ocr-diagnostics")
        val writer = PrimaryOcrDiagnostics(context, enabled = true, root = root)
        val engine = PaddlePrimaryLineRecognizer({ PaddleOcrLineRecognizer(context) },
            { error("The clear fixture must use Paddle") }, writer)
        val corrector = configuredTextCorrector(context, true)
        assertNotNull(corrector)
        val region = Region(7, Rect(0, 0, image.width, image.height), RegionType.TEXT, 1000, image)
        try {
            writer.begin(image, corrector != null)
            writer.source(image, listOf(region))
            val raw = engine.recognize(listOf(region))
            assertTrue(raw.isNotEmpty())
            val corrections = correctRecognizedLines(raw, corrector!!)
            val corrected = raw.mapIndexed { index, line -> line.copy(text = corrections[index].result.correctedText) }
            val note = PipelineTextStructurer().structure(corrected)
            writer.finish(raw, corrected, corrections, note)
            File(writer.directory, "fixture.txt").writeText("Instrumentation fixture paddle_reference/fractional.png; not the user\'s four-sentence whiteboard.")
            val report = JSONObject(File(writer.directory, "scan.json").readText())
            assertEquals("completed", report.getString("status"))
            assertTrue(report.getBoolean("correctionExecuted"))
            assertEquals(PaddleModelAssets.MODEL_SHA256, report.getString("modelSha256"))
            assertEquals(PaddleModelAssets.DICTIONARY_SHA256, report.getString("dictionarySha256"))
            assertEquals(raw.size, report.getJSONArray("outputs").length())
            assertEquals("paddle", report.getJSONArray("lines").getJSONObject(0).getString("selectedEngine"))
            assertEquals(raw.first().text, report.getJSONArray("outputs").getJSONObject(0).getString("rawText"))
            assertTrue(image.sameAs(original))
        } finally {
            engine.close()
            image.recycle(); original.recycle()
        }
    }

    /** Retains a labelled fixture export; never writes a note or touches the user's gallery. */
    @Test fun configuredResearchPipelineRecordsActualCorrectionExecution(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Full fixture requires an explicit bounded research run",
            InstrumentationRegistry.getArguments().getString("runFullPrimaryFixture") == "true")
        org.junit.Assume.assumeTrue(com.example.note2snap.BuildConfig.ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS &&
            com.example.note2snap.BuildConfig.SINGLE_SCAN_RECOGNITION_MODE == "PADDLE_PRIMARY_WITH_CRNN_FALLBACK")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "primary-ocr-diagnostics")
        val before = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        val image = instrumentation.context.assets.open("board_test_5.jpg").use { BitmapFactory.decodeStream(it) }
        val pipeline = WhiteboardRecognitionPipeline(context)
        val result = try { pipeline.process(image) } finally { pipeline.close(); image.recycle() }
        val directory = root.listFiles()!!.single { it.name !in before }
        val report = JSONObject(File(directory, "scan.json").readText())
        // This label belongs only to the test fixture and is not part of the recognition path.
        File(directory, "fixture.txt").writeText("Instrumentation fixture board_test_5.jpg; not the user's four-sentence whiteboard.")
        assertEquals("completed", report.getString("status"))
        assertEquals("PADDLE_PRIMARY_WITH_CRNN_FALLBACK", report.getString("recognitionMode"))
        assertEquals(com.example.note2snap.BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION, report.getBoolean("correctionExecuted"))
        assertTrue(result.recognizedLines.isNotEmpty())
        assertEquals(result.recognizedLines.size, report.getJSONArray("outputs").length())
        result.recognizedLines.forEachIndexed { index, line ->
            assertEquals(line.text, report.getJSONArray("outputs").getJSONObject(index).getString("rawText"))
            assertEquals(result.correctedLines[index].text, report.getJSONArray("outputs").getJSONObject(index).getString("correctedText"))
        }
        assertEquals(PaddleModelAssets.MODEL_SHA256, report.getString("modelSha256"))
        assertEquals(PaddleModelAssets.DICTIONARY_SHA256, report.getString("dictionarySha256"))
    }
}
