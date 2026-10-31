package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
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
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimpleHtrReferencePreprocessorTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!
    private fun bytes(buffer: ByteBuffer): ByteArray = buffer.duplicate().apply { rewind() }.let {
        ByteArray(it.remaining()).also(it::get)
    }

    @Test fun matchesIndependentPythonGoldenTensorsIncludingFractionalScalingAndEdges() {
        val manifest = JSONObject(resource("crnn_reference/manifest.json").bufferedReader().use { it.readText() })
        val cases = manifest.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bitmap = resource("crnn_reference/$name.png").use { BitmapFactory.decodeStream(it) }
            val result = SimpleHtrReferencePreprocessor().preprocess(bitmap)
            assertEquals(c.getInt("dynamicWidth"), result.dynamicWidth)
            val golden = GZIPInputStream(resource("crnn_reference/$name.f32.gz")).use { it.readBytes() }
            val expected = ByteBuffer.wrap(golden).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val actual = result.input!!.asFloatBuffer()
            var maxDifference = 0f
            for (j in 0 until expected.limit())
                maxDifference = maxOf(maxDifference, kotlin.math.abs(expected.get(j) - actual.get(j)))
            println("PYTHON GOLDEN $name maximum float difference=$maxDifference")
            assertTrue("$name max difference $maxDifference", maxDifference <= 1e-7f)
        }
    }

    @Test fun normalizationMarginsRightPaddingAndWidthMajorLayout() {
        val gray = ByteArray(20 * 32) { 255.toByte() }
        gray[9 * 20 + 7] = 0
        val result = SimpleHtrReferencePreprocessor().preprocessGrayscale(20, 32, gray)
        assertEquals(36, result.dynamicWidth)
        val f = result.input!!.asFloatBuffer()
        assertEquals(-.5f, f.get((8 + 7) * 32 + 9), 0f)
        assertEquals(.5f, f.get(9 * 32 + 15), 0f)
        assertEquals(.5f, f.get(0), 0f)
        assertEquals(.5f, f.get(511 * 32 + 31), 0f)
        assertEquals(0, result.input.position())
        assertEquals(512 * 32 * 4, result.input.remaining())
        assertEquals(ByteOrder.nativeOrder(), result.input.order())
    }

    @Test fun explicitSelectionPreservesDefaultAndOverwideFallbackByteForByte() {
        for ((w, h) in listOf(200 to 40, 496 to 32, 497 to 32, 1300 to 24)) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val legacy = CrnnInputPreprocessor().preprocess(bitmap)
            val default = CrnnPreprocessing().prepare(bitmap)
            assertArrayEquals(bytes(legacy), bytes(default.input))
            assertEquals(CrnnPreprocessingMode.LEGACY, default.actualMode)
            val selected = CrnnPreprocessing(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE).prepare(bitmap)
            if (w >= 497) {
                assertEquals(CrnnPreprocessingMode.LEGACY, selected.actualMode)
                assertEquals("reference_width_exceeds_512", selected.fallbackReason)
                assertArrayEquals(bytes(legacy), bytes(selected.input))
            } else {
                assertEquals(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE, selected.actualMode)
                assertNull(selected.fallbackReason)
                assertFalse(bytes(legacy).contentEquals(bytes(selected.input)))
            }
        }
    }

    @Test fun diagnosticsIdentifyReferenceAndFallbackPerLine() {
        val diagnostics = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        for (w in listOf(200, 900)) {
            val bitmap = Bitmap.createBitmap(w, 32, Bitmap.Config.ARGB_8888)
            val box = Rect(0, 0, w, 32)
            val prepared = CrnnPreprocessing(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE).prepare(bitmap)
            val id = diagnostics.beginLine(Region(w, box, RegionType.TEXT, w, bitmap),
                PhysicalLine(bitmap, box), prepared.input, prepared)
            val record = JSONObject(File(diagnostics.directory, "line-%04d.json".format(id)).readText())
                .getJSONObject("preprocessing")
            assertEquals(prepared.actualMode.name, record.getString("actualMode"))
            assertEquals(prepared.referenceWidth, record.getInt("referenceWidth"))
            assertEquals(prepared.fallbackReason == null, record.isNull("fallbackReason"))
        }
    }

    @Test fun compareBothOptionsOnIdenticalRealWhiteboardLineCropsWithoutInference() {
        val photo = resource("board_test_5.jpg").use { BitmapFactory.decodeStream(it) }
        val processed = com.example.note2snap.preprocessing.WhiteboardPreprocessor().process(photo)
        val regions = com.example.note2snap.ccl.ConnectedComponentLabeler().label(
            processed.binarizedBitmap, processed.recognitionBitmap)
        val directory = File(System.getProperty("user.dir"), "build/crnn-reference-comparison")
        if (directory.exists()) directory.deleteRecursively()
        assertTrue(directory.mkdirs())
        val records = mutableListOf<JSONObject>()
        for (region in regions.filter { it.type == RegionType.TEXT })
            for (line in PhysicalLineSegmenter().splitIntoPhysicalLines(region)) {
                val id = records.size
                val prefix = "line-%04d".format(id)
                val pixels = IntArray(line.bitmap.width * line.bitmap.height)
                line.bitmap.getPixels(pixels, 0, line.bitmap.width, 0, 0, line.bitmap.width, line.bitmap.height)
                val snapshot = Bitmap.createBitmap(pixels, line.bitmap.width, line.bitmap.height, Bitmap.Config.ARGB_8888)
                File(directory, "$prefix-crop.png").outputStream().use {
                    assertTrue(snapshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val legacy = CrnnInputPreprocessor().preprocess(line.bitmap)
                val selected = CrnnPreprocessing(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE).prepare(line.bitmap)
                File(directory, "$prefix-legacy.f32").writeBytes(bytes(legacy))
                File(directory, "$prefix-input.f32").writeBytes(bytes(selected.input))
                records.add(JSONObject().put("id", id).put("cropImage", "$prefix-crop.png")
                    .put("inputTensor", "$prefix-input.f32").put("legacyInputTensor", "$prefix-legacy.f32")
                    .put("inputByteOrder", selected.input.order().toString())
                    .put("actualMode", selected.actualMode.name).put("fallbackReason", selected.fallbackReason ?: JSONObject.NULL)
                    .put("referenceWidth", selected.referenceWidth)
                    .put("box", JSONObject().put("left", line.boundingBox.left).put("top", line.boundingBox.top)
                        .put("right", line.boundingBox.right).put("bottom", line.boundingBox.bottom)))
            }
        assertTrue(records.isNotEmpty())
        File(directory, "lines.jsonl").writeText(records.joinToString("\n", postfix = "\n") { it.toString() })
        println("IDENTICAL-CROP OPTION COMPARISON (NO INFERENCE): ${directory.absolutePath}, ${records.size} crops")
    }
}
