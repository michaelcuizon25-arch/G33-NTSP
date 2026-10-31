package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.BuildConfig
import com.example.note2snap.correction.bundledCorrectorForTest
import com.example.note2snap.preprocessing.WhiteboardEnhancer
import com.example.note2snap.preprocessing.WhiteboardEnhancement
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.common.InputImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Stage25FeaturesTest {
    @Before fun initializeMlKitForBitmapInputs() {
        com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication(),
            listOf(com.google.mlkit.common.internal.CommonComponentRegistrar()))
    }

    @Test fun normalDiagnosticsWriteNoFilesAndDoNotReadTensor() {
        assertFalse(BuildConfig.ENABLE_CRNN_DIAGNOSTIC_EXPORTS)
        val diagnostics = CrnnDiagnostics(RuntimeEnvironment.getApplication())
        val b = Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888)
        val region = com.example.note2snap.ccl.Region(1,Rect(0,0,20,20),com.example.note2snap.ccl.RegionType.TEXT,1,b)
        // A deliberately empty buffer: disabled export must never traverse it.
        diagnostics.beginLine(region,PhysicalLine(b,region.boundingBox),ByteBuffer.allocate(0))
        diagnostics.flush()
        assertFalse(diagnostics.directory.exists())
    }

    @Test fun researchReviewIsFlushedOnceAndUpdatedRecordsRemainAvailable() {
        val d = CrnnDiagnostics(RuntimeEnvironment.getApplication(), enabled=true)
        val b = Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val region = com.example.note2snap.ccl.Region(1,Rect(0,0,20,20),com.example.note2snap.ccl.RegionType.TEXT,1,b)
        d.beginLine(region,PhysicalLine(b,region.boundingBox),CrnnInputPreprocessor().preprocess(b))
        assertTrue(File(d.directory,"line-0000.json").exists())
        assertFalse(File(d.directory,"review.html").exists())
        d.flush()
        val review = File(d.directory,"review.html")
        assertTrue(review.exists())
        val markerTime = 1_600_000_000_000L
        assertTrue(review.setLastModified(markerTime))
        d.flush()
        assertEquals(markerTime,review.lastModified())
    }

    @Test fun batchFlagsOffReturnSameMlKitImageAndOriginalText() {
        assertFalse(BuildConfig.ENABLE_OPENCV_ENHANCEMENT)
        assertFalse(BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION)
        val bitmap = Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888)
        val original = InputImage.fromBitmap(bitmap,0)
        val features = BatchRecognitionFeatures(false,null,WhiteboardEnhancer { error("must not run") })
        val prepared = features.prepare(original)
        assertSame(original, prepared.image)
        assertEquals(Rect(5,7,30,40),prepared.geometry.toOriginal(Rect(5,7,30,40)))
        assertEquals("cornputer\nalgorithrn",features.correct("cornputer\nalgorithrn").correctedText)
        prepared.close()
        assertFalse("Caller-owned source must survive", bitmap.isRecycled)
    }

    @Test fun batchEnhancesExactlyOnceAndRetainsOriginalCoordinatesAndPixels() {
        val source=Bitmap.createBitmap(2400,1200,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        var calls=0
        val features=BatchRecognitionFeatures(true,bundledCorrectorForTest(),WhiteboardEnhancer {
            calls++; WhiteboardEnhancement(it.copy(Bitmap.Config.ARGB_8888,true),true)
        })
        val input=features.prepare(InputImage.fromBitmap(source,0))
        assertEquals(1,calls)
        assertEquals(1800,input.image.width)
        assertEquals(Rect(400,200,800,400),input.geometry.toOriginal(Rect(300,150,600,300)))
        assertEquals(Color.WHITE,source.getPixel(10,10))
        val correction=features.correct("cornputer\n\nalgorithrn")
        assertEquals("cornputer\n\nalgorithrn",correction.originalText)
        assertEquals("computer\n\nalgorithm",correction.correctedText)
        input.close(); assertFalse(source.isRecycled)
    }

    @Test fun batchFallbackKeepsOriginalScaleAndResourcesWaitForTaskCompletion() {
        val source=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888)
        val image=InputImage.fromBitmap(source,0)
        val features=BatchRecognitionFeatures(true,null,WhiteboardEnhancer { throw IllegalStateException("native unavailable") })
        val input=features.prepare(image,ownsOriginal=true)
        assertSame(image,input.image)
        val task=TaskCompletionSource<String>()
        input.releaseAfter(task.task)
        assertFalse(source.isRecycled) // Coroutine cancellation must not trigger this cleanup.
        task.setResult("done")
        assertTrue(source.isRecycled)
    }
}
