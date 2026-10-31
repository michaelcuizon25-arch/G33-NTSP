package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.note2snap.ccl.Region
import com.example.note2snap.correction.BundledTextCorrector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks real APK assets/flag wiring; OCR is injected, not a native-model benchmark. */
@RunWith(AndroidJUnit4::class)
class OfflineCorrectionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledDictionaryIsAvailableOfflineAndCached() {
        val first = BundledTextCorrector.load(context)
        assertSame(first, BundledTextCorrector.load(context))
        assertEquals("computer algorithm", first.correct("cornputer algorithrn").correctedText)
        assertNull(configuredTextCorrector(context, false))
    }

    @Test fun contextConstructorCanEnableAndDisableCorrectionWithoutChangingRecognizerMode(): Unit = runBlocking {
        val original = listOf(RecognizedLine(1, Rect(10, 10, 100, 30), "cornputer", 1f))
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            for (enabled in listOf(false, true)) {
                var closed = false
                val pipeline = WhiteboardRecognitionPipeline(context, enableTextCorrection = enabled,
                    recognizerFactory = { _, mode ->
                        assertEquals(CrnnPreprocessingMode.LEGACY, mode)
                        object : LineRecognizer {
                            override suspend fun recognize(regions: List<Region>) = original
                            override fun close() { closed = true }
                        }
                    })
                val result = try { pipeline.process(image) } finally { pipeline.close() }
                assertTrue(closed)
                assertSame(original, result.recognizedLines)
                assertEquals(if (enabled) "computer" else "cornputer", result.correctedLines.single().text)
                assertEquals(original.single().boundingBox, result.correctedLines.single().boundingBox)
                assertEquals(original.single().confidence, result.correctedLines.single().confidence)
            }
        } finally { image.recycle() }
    }
}
