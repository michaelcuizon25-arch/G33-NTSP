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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrnnDiagnosticsTest {
    private fun bitmap(width: Int, height: Int): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width)
            b.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
        return b
    }
    private fun bytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate().apply { rewind() }
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    @Test fun extractedPreprocessingMatchesPreviousImplementationByteForByte() {
        for ((w, h) in listOf(30 to 80, 1300 to 24, 512 to 32, 200 to 40)) {
            val b = bitmap(w, h)
            assertArrayEquals("Changed preprocessing for ${w}x$h",
                bytes(LegacyEpoch3Input().preprocess(b)), bytes(CrnnInputPreprocessor().preprocess(b)))
        }
    }

    @Test fun recorderPreservesExactCropTensorBufferAndBlankPrediction() {
        val d = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        val b = bitmap(60, 20)
        val line = PhysicalLine(b, Rect(10, 20, 70, 40))
        val region = Region(19, Rect(8, 18, 72, 42), RegionType.TEXT, 500, b)
        val input = ByteBuffer.allocateDirect(512 * 32 * 4).order(ByteOrder.nativeOrder())
        for (x in 0 until 512) for (y in 0 until 32) input.putFloat(if (x == 7 && y == 9) -0.5f else 0.5f)
        input.rewind()
        val expected = bytes(input)
        val id = d.beginLine(region, line, input)
        assertEquals(0, input.position())
        assertArrayEquals(expected, bytes(input))
        assertArrayEquals(expected, File(d.directory, "line-0000-input.f32").readBytes())
        val crop = BitmapFactory.decodeFile(File(d.directory, "line-0000-crop.png").path)
        assertEquals(60, crop.width)
        assertEquals(b.getPixel(12, 8), crop.getPixel(12, 8))
        val preview = BitmapFactory.decodeFile(File(d.directory, "line-0000-input.png").path)
        assertEquals(Color.BLACK, preview.getPixel(7, 9))
        assertEquals(Color.WHITE, preview.getPixel(9, 7))
        d.completeLine(id, " ", "", 0f, arrayOf(arrayOf(floatArrayOf(0.2f, 0.8f))))
        val record = JSONObject(File(d.directory, "line-0000.json").readText())
        assertEquals("", record.getString("predictedText"))
        assertEquals(" ", record.getString("rawPredictedText"))
        assertEquals(19, record.getInt("sourceRegionId"))
        assertEquals(10, record.getJSONObject("box").getInt("left"))
        assertTrue(record.isNull("containsCompleteHandwrittenLine"))
        assertEquals("unreviewed", record.getString("cropCompleteness"))
        assertEquals(8L, File(d.directory, "line-0000-output.f32").length())
    }

    @Test fun photoFixturePreprocessingAuditWithoutInference() {
        val photo = javaClass.classLoader!!.getResourceAsStream("board_test_5.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        val preprocessed = com.example.note2snap.preprocessing.WhiteboardPreprocessor().process(photo)
        val regions = com.example.note2snap.ccl.ConnectedComponentLabeler().label(
            preprocessed.binarizedBitmap, preprocessed.recognitionBitmap)
        val d = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        d.recordSource(photo, preprocessed, regions)
        File(d.directory, "audit-mode.json").writeText(
            "{\"mode\":\"Robolectric preprocessing audit; no model inference\"}")
        var count = 0
        for (region in regions.filter { it.type == RegionType.TEXT }) {
            for (line in PhysicalLineSegmenter().splitIntoPhysicalLines(region)) {
                val actual = CrnnInputPreprocessor().preprocess(line.bitmap)
                assertArrayEquals(bytes(LegacyEpoch3Input().preprocess(line.bitmap)), bytes(actual))
                d.beginLine(region, line, actual)
                count++
            }
        }
        assertTrue(count > 0)
        val output = File(System.getProperty("user.dir"), "build/crnn-preprocessing-audit")
        if (output.exists()) output.deleteRecursively()
        assertTrue(d.directory.copyRecursively(output))
        println("PREPROCESSING AUDIT (NO INFERENCE): " + output.absolutePath)
    }

    @Test fun sessionsAreUniqueAndInferenceErrorsRetainTheirCrops() {
        val a = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        val b = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        assertNotEquals(a.directory, b.directory)
        val bitmap = bitmap(20, 20)
        val box = Rect(0, 0, 20, 20)
        val id = a.beginLine(Region(1, box, RegionType.TEXT, 10, bitmap), PhysicalLine(bitmap, box),
            CrnnInputPreprocessor().preprocess(bitmap))
        a.failLine(id, IllegalStateException("Test inference failure"))
        val record = JSONObject(File(a.directory, "line-0000.json").readText())
        assertEquals("inference_error", record.getString("status"))
        assertTrue(File(a.directory, "line-0000-input.f32").exists())
    }

    @Test fun storageFailureCannotInterruptRecognition() {
        val d = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled = true)
        d.directory.deleteRecursively()
        d.directory.writeText("Simulate unwritable directory")
        val b = bitmap(20, 20)
        val box = Rect(0, 0, 20, 20)
        val id = d.beginLine(Region(1, box, RegionType.TEXT, 10, b), PhysicalLine(b, box),
            CrnnInputPreprocessor().preprocess(b))
        d.completeLine(id, "text", "text", 0.9f, arrayOf(arrayOf(floatArrayOf(1f))))
        d.failLine(id, IllegalStateException("failure"))
    }
}
