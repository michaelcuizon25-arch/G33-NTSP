package com.example.note2snap.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import com.example.note2snap.BuildConfig
import com.example.note2snap.ccl.ConnectedComponentLabeler
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhiteboardEnhancementTest {
    private fun board(width: Int = 120, height: Int = 80) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 20..90) for (y in 25..27) setPixel(x, y, Color.DKGRAY)
        }

    @Test fun disabledDoesNotInvokeEnhancerAndPreservesOriginalPixelsAndBinary() {
        val source = board()
        val baseline = WhiteboardPreprocessor(false).process(source)
        val result = WhiteboardPreprocessor(false, WhiteboardEnhancer { error("must not run") }).process(source)
        assertSame(source, result.originalRecognitionBitmap)
        assertSame(source, result.recognitionBitmap)
        assertTrue(baseline.binarizedBitmap.sameAs(result.binarizedBitmap))
        assertFalse(result.enhancementRequested)
        assertFalse(result.enhancementApplied)
    }

    @Test fun enhancementUsesBoundedImageAndKeepsCclAndRecognitionAligned() {
        val source = board(2400, 1600)
        val before = source.copy(Bitmap.Config.ARGB_8888, false)
        val result = WhiteboardPreprocessor(true, WhiteboardEnhancer { bounded ->
            assertEquals(1800, bounded.width)
            assertEquals(1200, bounded.height)
            WhiteboardEnhancement(bounded.copy(Bitmap.Config.ARGB_8888, true), true)
        }).process(source)
        assertTrue(source.sameAs(before))
        assertNotSame(result.originalRecognitionBitmap, result.recognitionBitmap)
        assertEquals(result.recognitionBitmap.width, result.binarizedBitmap.width)
        assertEquals(result.recognitionBitmap.height, result.binarizedBitmap.height)
        assertTrue(result.enhancementApplied)
        ConnectedComponentLabeler().label(result.binarizedBitmap, result.recognitionBitmap).forEach {
            assertEquals(it.boundingBox.width(), it.croppedBitmap.width)
            assertEquals(it.boundingBox.height(), it.croppedBitmap.height)
        }
    }

    @Test fun enhancedPixelsDriveBothThresholdingAndRecognition() {
        val source = board()
        val enhanced = board().apply { for (x in 25..85) for (y in 50..53) setPixel(x, y, Color.BLACK) }
        val expected = WhiteboardPreprocessor(false).process(enhanced)
        val actual = WhiteboardPreprocessor(true, WhiteboardEnhancer { WhiteboardEnhancement(enhanced, true) }).process(source)
        assertSame(enhanced, actual.recognitionBitmap)
        assertSame(source, actual.originalRecognitionBitmap)
        assertTrue(expected.binarizedBitmap.sameAs(actual.binarizedBitmap))
    }

    @Test fun processingAndNativeLoadingFailuresReturnExactLegacyPath() {
        val source = board()
        val expected = WhiteboardPreprocessor(false).process(source)
        for (failure in listOf(IllegalStateException("processing"), UnsatisfiedLinkError("native"))) {
            val actual = WhiteboardPreprocessor(true, WhiteboardEnhancer { throw failure }).process(source)
            assertSame(source, actual.recognitionBitmap)
            assertFalse(actual.enhancementApplied)
            assertTrue(actual.enhancementRequested)
            assertTrue(expected.binarizedBitmap.sameAs(actual.binarizedBitmap))
            assertNotNull(actual.enhancementReason)
        }
    }

    @Test fun coordinateChangingEnhancementIsRejected() {
        val source = board()
        val result = WhiteboardPreprocessor(true, WhiteboardEnhancer {
            WhiteboardEnhancement(board(100, 80), true)
        }).process(source)
        assertSame(source, result.recognitionBitmap)
        assertFalse(result.enhancementApplied)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverSwallowed() {
        WhiteboardPreprocessor(true, WhiteboardEnhancer { throw CancellationException() }).process(board())
    }

    @Test fun defaultConfigurationIsDisabled() {
        assertFalse("Run defaults-off regression suite without the opt-in Gradle property", BuildConfig.ENABLE_OPENCV_ENHANCEMENT)
        assertFalse(WhiteboardPreprocessor().process(board()).enhancementRequested)
    }
}
