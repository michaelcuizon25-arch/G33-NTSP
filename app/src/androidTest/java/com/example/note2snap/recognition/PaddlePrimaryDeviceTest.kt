package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Minimum phone gates; test-only explicit primary instances do not change ordinary builds. */
@RunWith(AndroidJUnit4::class)
class PaddlePrimaryDeviceTest {
    @Test fun blankNoiseAndFramesDoNotInitializeEitherEngine(): Unit = runBlocking {
        val engine = PaddlePrimaryLineRecognizer({ error("Blank must not run Paddle") }, { error("Blank must not run CRNN") })
        try {
            for (kind in listOf("white", "off_white", "noise", "dot", "frame")) {
                val bitmap = Bitmap.createBitmap(320, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                try {
                    when (kind) {
                        "off_white" -> bitmap.eraseColor(Color.rgb(245, 245, 245))
                        "noise" -> {
                            val random = java.util.Random(42)
                            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                                val v = 245 + random.nextInt(11); bitmap.setPixel(x, y, Color.rgb(v, v, v))
                            }
                        }
                        "dot" -> bitmap.setPixel(160, 24, Color.BLACK)
                        "frame" -> Canvas(bitmap).drawRect(20f, 8f, 290f, 39f,
                            Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f })
                    }
                    val region = Region(7, Rect(0, 0, 320, 48), RegionType.TEXT, 1000, bitmap)
                    assertTrue("Fabricated output for $kind", engine.recognize(listOf(region)).isEmpty())
                } finally { bitmap.recycle() }
            }
        } finally { engine.close() }
    }

    @Test fun bundledHandwritingUsesRealPaddleWithoutStartingCrnn(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("paddle_reference/fractional.png").use { BitmapFactory.decodeStream(it) }
        val engine = PaddlePrimaryLineRecognizer({ PaddleOcrLineRecognizer(instrumentation.targetContext) },
            { error("Clear fixture should not need CRNN fallback; inspect Paddle error logs") })
        try {
            val result = engine.recognize(listOf(Region(7, Rect(10, 20, 10 + bitmap.width, 20 + bitmap.height),
                RegionType.TEXT, 1000, bitmap)))
            assertTrue(RecognitionReadability.hasReadableWriting(result))
            assertTrue(result.all { it.sourceRegionId == 7 && it.boundingBox.left >= 10 && it.boundingBox.top >= 20 })
        } finally { try { engine.close() } finally { bitmap.recycle() } }
    }
}
