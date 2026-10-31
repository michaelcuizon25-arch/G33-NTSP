package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import com.example.note2snap.preprocessing.PreprocessingResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires a device: runs the official Android OpenCV JNI and unchanged CRNN/Flex/CTC. */
@RunWith(AndroidJUnit4::class)
class OpenCvEnhancementDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null), "opencv-comparison").apply { mkdirs() }
    private fun photo(name: String): Bitmap = instrumentation.context.assets.open(name).use {
        requireNotNull(BitmapFactory.decodeStream(it))
    }
    private fun save(name: String, bitmap: Bitmap) {
        File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun release(prepared: PreprocessingResult, source: Bitmap) {
        listOf(prepared.binarizedBitmap, prepared.recognitionBitmap, prepared.originalRecognitionBitmap)
            .distinct().filter { it !== source }.forEach { it.recycle() }
    }
    private fun recognize(bitmap: Bitmap, enabled: Boolean, mode: CrnnPreprocessingMode): JSONObject = runBlocking {
        val prepared = WhiteboardPreprocessor(enabled).process(bitmap)
        if (enabled) assertTrue("OpenCV must actually execute, not silently fall back: ${prepared.enhancementReason}", prepared.enhancementApplied)
        assertEquals(prepared.recognitionBitmap.width, prepared.binarizedBitmap.width)
        assertEquals(prepared.recognitionBitmap.height, prepared.binarizedBitmap.height)
        val regions = ConnectedComponentLabeler().label(prepared.binarizedBitmap, prepared.recognitionBitmap)
        val recognizer = CrnnLineRecognizer(context, mode)
        val lines = try { recognizer.recognize(regions.filter { it.type == RegionType.TEXT }) } finally { recognizer.close() }
        JSONObject().put("enabled", enabled).put("mode", mode.name)
            .put("enhancementMs", prepared.enhancementElapsedMs)
            .put("textRegions", regions.count { it.type == RegionType.TEXT })
            .put("diagramRegions", regions.count { it.type == RegionType.NON_TEXT })
            .put("lines", JSONArray(lines.map { it.text })).also {
                regions.map { it.croppedBitmap }.distinct().forEach { it.recycle() }
                release(prepared, bitmap)
            }
    }

    @Test fun compareRealBoardsFaintWritingLightingAndResolutions() {
        val report = JSONArray()
        val names = (1..6).map { "board_test_$it.${if (it >= 4) "jpg" else "png"}" } +
            (1..4).map { "test_whiteboard_$it.png" }
        for (name in names) {
            val source = photo(name)
            val before = source.copy(Bitmap.Config.ARGB_8888, false)
            try {
                for (enabled in listOf(false, true)) {
                    val result = WhiteboardPreprocessor(enabled).process(source)
                    if (enabled) assertTrue(result.enhancementApplied)
                    save("$name-${if (enabled) "enhanced" else "original"}.png", result.recognitionBitmap)
                    // Native enhancement must not consume or mutate caller-owned pixels.
                    assertTrue(source.sameAs(before))
                    for (mode in CrnnPreprocessingMode.values()) {
                        report.put(recognize(source, enabled, mode).put("fixture", name))
                    }
                    release(result, source)
                }
            } finally { source.recycle(); before.recycle() }
        }
        val source = photo("board_test_5.jpg")
        val bounded = WhiteboardPreprocessor(false).process(source).recognitionBitmap
        for (variant in listOf("faint", "uneven-lighting", "half-resolution", "double-resolution")) {
            val image = when (variant) {
                "half-resolution" -> Bitmap.createScaledBitmap(bounded, bounded.width / 2, bounded.height / 2, true)
                "double-resolution" -> Bitmap.createScaledBitmap(bounded, bounded.width * 2, bounded.height * 2, true)
                else -> bounded.copy(Bitmap.Config.ARGB_8888, true).apply {
                    val pixels = IntArray(width * height)
                    getPixels(pixels, 0, width, 0, 0, width, height)
                    for (i in pixels.indices) {
                        val factor = if (variant == "faint") 0.35 else 0.45 + 0.55 * (i % width).toDouble() / width
                        fun value(v: Int) = if (variant == "faint") (255 - (255 - v) * factor).toInt() else (v * factor).toInt()
                        pixels[i] = Color.rgb(value(Color.red(pixels[i])), value(Color.green(pixels[i])), value(Color.blue(pixels[i])))
                    }
                    setPixels(pixels, 0, width, 0, 0, width, height)
                }
            }
            for (enabled in listOf(false, true)) for (mode in CrnnPreprocessingMode.values()) {
                report.put(recognize(image, enabled, mode).put("fixture", variant))
            }
            image.recycle()
        }
        File(output, "comparisons.json").writeText(report.toString(2))
        if (bounded !== source) bounded.recycle()
        source.recycle()
    }

    @Test fun knownTitleCerWerComparisonUsesSameCropAndUnchangedDecoder() = runBlocking {
        val source = photo("board_test_5.jpg")
        val report = JSONArray()
        val truth = "Mobile Application Development"
        // Existing photo regression identifies this complete title at 1800px scale.
        val box = Rect(210, 330, 965, 400)
        for (enabled in listOf(false, true)) {
            val prepared = WhiteboardPreprocessor(enabled).process(source)
            if (enabled) assertTrue(prepared.enhancementApplied)
            val crop = Bitmap.createBitmap(prepared.recognitionBitmap, box.left, box.top, box.width(), box.height())
            for (mode in CrnnPreprocessingMode.values()) {
                val recognizer = CrnnLineRecognizer(context, mode)
                val lines = try { recognizer.recognize(listOf(Region(1, box, RegionType.TEXT, 1, crop))) }
                    finally { recognizer.close() }
                val prediction = lines.joinToString(" ") { it.text }
                report.put(JSONObject().put("enabled", enabled).put("mode", mode.name)
                    .put("truth", truth).put("prediction", prediction)
                    .put("cer", distance(truth.toList(), prediction.toList()).toDouble() / truth.length)
                    .put("wer", distance(truth.split(" "), prediction.split(" ").filter { it.isNotEmpty() }).toDouble() / truth.split(" ").size))
            }
            crop.recycle()
            release(prepared, source)
        }
        File(output, "title-accuracy.json").writeText(report.toString(2))
        // Scores are observational: improvement has not yet been established.
        source.recycle()
    }

    @Test fun faintThinStrokesAndDiagramRemainVisibleAndSourceIsUntouched() {
        val source = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 20..140) setPixel(x, 35, Color.rgb(185, 185, 185))
            for (x in 40..120) { setPixel(x, 65, Color.BLUE); setPixel(x, 100, Color.BLUE) }
            for (y in 65..100) { setPixel(40, y, Color.BLUE); setPixel(120, y, Color.BLUE) }
        }
        val before = source.copy(Bitmap.Config.ARGB_8888, false)
        val prepared = WhiteboardPreprocessor(true).process(source)
        assertTrue(prepared.enhancementApplied)
        assertTrue(source.sameAs(before))
        fun brightness(x: Int, y: Int): Int {
            val c = prepared.recognitionBitmap.getPixel(x, y)
            return (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
        }
        assertTrue(brightness(80, 35) < brightness(80, 30) - 20)
        assertTrue(brightness(80, 65) < brightness(80, 60) - 20)
        source.recycle(); before.recycle(); prepared.recognitionBitmap.recycle(); prepared.binarizedBitmap.recycle()
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
