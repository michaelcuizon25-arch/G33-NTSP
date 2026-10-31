package com.example.note2snap.recognition

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in host audit. Enhanced PNGs come from a host OpenCV mirror, not Android JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenCvHostComparisonTest {
    @Test fun captureInputsOrDecodeNativeOutputs() = runBlocking {
        val location = System.getProperty("note2snap.opencv.audit")
        assumeTrue("Set note2snap.opencv.audit to the prepared host comparison directory", location != null)
        val directory = File(location!!)
        val charset = File(System.getProperty("user.dir"), "src/main/assets/ocr/note2snap_crnn_v2_epoch03_charlist.txt")
            .readText().removePrefix("\uFEFF").trimEnd('\r', '\n').map { it.toString() }
        val cases = JSONArray(File(directory, "cases.json").readText())
        val report = JSONArray()
        var sequence = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val bitmap = BitmapFactory.decodeFile(case.getString("image"))
            val prepared = WhiteboardPreprocessor(false).process(bitmap)
            val truth = if (case.isNull("truth")) null else case.getString("truth")
            val regions = if (truth == null) {
                ConnectedComponentLabeler().label(prepared.binarizedBitmap, prepared.recognitionBitmap)
                    .filter { it.type == RegionType.TEXT }
            } else listOf(Region(1, Rect(0, 0, bitmap.width, bitmap.height), RegionType.TEXT, 1, bitmap))
            val modes = if (truth == null) listOf(CrnnPreprocessingMode.LEGACY) else CrnnPreprocessingMode.values().toList()
            for (mode in modes) {
                var inferred = 0
                val runtime = object : CrnnInferenceRuntime {
                    override val inputShape = intArrayOf(1, 512, 32)
                    override val outputShape = intArrayOf(1, 128, 80)
                    override val inputType = "FLOAT32"
                    override val outputType = "FLOAT32"
                    override fun run(input: ByteBuffer): Array<Array<FloatArray>> {
                        val id = "%04d".format(sequence++)
                        val bytes = ByteArray(input.remaining()).also { input.duplicate().get(it) }
                        val inputFile = File(directory, "input-$id.f32")
                        if (inputFile.exists()) assertArrayEquals("Audit ordering/input changed", inputFile.readBytes(), bytes)
                        else inputFile.writeBytes(bytes)
                        val output = File(directory, "output-$id.f32")
                        if (!output.exists()) return Array(1) { Array(128) { FloatArray(80).apply { this[79] = 10f } } }
                        inferred++
                        val buffer = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                        return readCrnnLogits(buffer, output.length().toInt(), outputShape,
                            intArrayOf(-1, -1, 80), "FLOAT32", inputShape)
                    }
                    override fun close() = Unit
                }
                val context: Context = RuntimeEnvironment.getApplication()
                val recognizer = CrnnLineRecognizer(context, mode, runtime, charset)
                val lines = try { recognizer.recognize(regions) } finally { recognizer.close() }
                val prediction = lines.joinToString(" ") { it.text }
                val record = JSONObject(case.toString()).put("mode", mode.name)
                    .put("textRegions", regions.size).put("inferredLines", inferred)
                    .put("prediction", prediction)
                if (truth != null && inferred > 0) {
                    record.put("cer", distance(truth.toList(), prediction.toList()).toDouble() / truth.length)
                        .put("wer", distance(truth.split(" "), prediction.split(" ").filter { it.isNotEmpty() }).toDouble() / truth.split(" ").size)
                }
                report.put(record)
            }
            regions.map { it.croppedBitmap }.distinct().filter { it !== bitmap }.forEach { it.recycle() }
            prepared.binarizedBitmap.recycle()
            bitmap.recycle()
        }
        File(directory, "kotlin-comparison.json").writeText(report.toString(2))
    }

    private fun <T> distance(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.size]
    }
}
