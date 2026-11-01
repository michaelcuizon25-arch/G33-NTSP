package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfFlow
import com.example.note2snap.diagnostics.PerfStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Direct-input investigation, bypassing CCL/quality gates only inside this instrumentation test.
 * Uses both real runtimes and the unchanged CRNN decoder; never asserts invented accuracy targets.
 */
@RunWith(AndroidJUnit4::class)
class OcrBlankProbeDeviceTest {
    @Test fun recordBlankAndNonTextPredictionsOnIdenticalBitmaps(): Unit = runBlocking {
        withContext(Dispatchers.Default) {
            PerformanceDiagnostics.withSession(PerfFlow.COMPONENT) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val id = "direct-probes-${UUID.randomUUID()}"
                val records = File(context.filesDir, "paddle-comparisons/$id.jsonl")
                check(records.parentFile!!.mkdirs() || records.parentFile!!.isDirectory)
                val imageFolder = File(context.filesDir, "paddle-probe-images/$id")
                check(imageFolder.mkdirs())
                val runtime = TensorFlowLiteCrnnRuntime(context)
                val crnn = CrnnLineRecognizer(context, CrnnPreprocessingMode.LEGACY, runtime,
                    loadCrnnCharset(context), exportDiagnostics = false)
                try {
                    val paddle = PaddleOcrLineRecognizer(context)
                    try {
                        // Reflection is test-only, preserving the exact existing beam decoder with no production seam.
                        val decode = CrnnLineRecognizer::class.java.getDeclaredMethod("decodeCtc", Class.forName("[[[F"))
                            .apply { isAccessible = true }
                        val cases = listOf("white_320", "white_1280", "off_white", "white_noise", "small_dot", "diagram")
                        cases.forEachIndexed { index, name ->
                            val bitmap = Bitmap.createBitmap(if (name == "white_1280") 1280 else 320, 48, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(if (name == "off_white") Color.rgb(245, 245, 245) else Color.WHITE)
                                val canvas = Canvas(bitmap)
                                val pen = Paint().apply { color = Color.BLACK; strokeWidth = 1f }
                                when (name) {
                                    "white_noise" -> {
                                        val random = java.util.Random(42)
                                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                                            val gray = 245 + random.nextInt(11)
                                            bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
                                        }
                                    }
                                    "small_dot" -> canvas.drawCircle(160f, 24f, 1f, pen)
                                    "diagram" -> {
                                        canvas.drawLine(20f, 10f, 290f, 10f, pen)
                                        canvas.drawLine(290f, 10f, 290f, 38f, pen)
                                        canvas.drawLine(290f, 38f, 20f, 38f, pen)
                                        canvas.drawLine(20f, 38f, 20f, 10f, pen)
                                    }
                                }
                                File(imageFolder, "$name.png").outputStream().use {
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                val start = System.nanoTime()
                                val input = PerformanceDiagnostics.measure(PerfStage.CRNN_PREPROCESSING) { CrnnInputPreprocessor().preprocess(bitmap) }
                                val prepared = System.nanoTime()
                                val output = runtime.run(input)
                                val inferred = System.nanoTime()
                                val text = PerformanceDiagnostics.measure(PerfStage.CTC_DECODE) { decode.invoke(crnn, output as Any) as String }
                                val decoded = System.nanoTime()
                                val secondary = paddle.recognizeLine(bitmap)
                                val row = JSONObject().put("kind", "DIRECT_PROBE").put("caseName", name)
                                    .put("source", "direct_probe").put("category", if (name in listOf("small_dot", "diagram")) "diagram" else "blank")
                                    .put("regionId", index).put("box", JSONArray(listOf(0, 0, bitmap.width, bitmap.height)))
                                    .put("imageWidth", bitmap.width).put("imageHeight", bitmap.height)
                                    .put("paddleModelSha256", PaddleModelAssets.MODEL_SHA256)
                                    .put("crnn", JSONObject().put("text", text).put("preprocessingNs", prepared - start)
                                        .put("inferenceNs", inferred - prepared).put("decodingNs", decoded - inferred))
                                    .put("paddle", JSONObject().put("text", secondary.prediction.text)
                                        .put("preprocessingNs", secondary.preprocessingNs)
                                        .put("inferenceIncludingColdInitNs", secondary.inferenceNs).put("decodingNs", secondary.decodingNs)
                                        .put("meanSelectedProbabilityUncalibrated", secondary.prediction.meanSelectedProbability))
                                records.appendText(row.toString() + "\n")
                            } finally { bitmap.recycle() }
                        }
                    } finally { paddle.close() }
                } finally { crnn.close() }
            }
        }
    }
}
