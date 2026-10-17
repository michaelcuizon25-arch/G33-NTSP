package com.example.note2snap.recognition

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.graphics.BitmapFactory
import android.view.View
import com.example.note2snap.ccl.ConnectedComponentLabeler
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.preprocessing.WhiteboardPreprocessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SingleScanPreprocessingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun settings(debug: Boolean = true) = SingleScanPreprocessingSettings(
        context.getSharedPreferences("single_crnn_debug", Context.MODE_PRIVATE), debug)
    private fun debugFlag(enabled: Boolean) {
        context.applicationInfo.flags = if (enabled)
            context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        else context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    @Test fun legacyIsDefaultAndPreferencePersistsAndCanBeReset() {
        assertEquals(CrnnPreprocessingMode.LEGACY, settings().selectedMode)
        assertTrue(settings().select(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE))
        assertEquals(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE, settings().selectedMode)
        settings().select(CrnnPreprocessingMode.LEGACY)
        assertEquals(CrnnPreprocessingMode.LEGACY, settings().selectedMode)
    }

    @Test fun releaseIgnoresStoredDebugModeAndRejectsSelection() {
        settings().select(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE)
        assertEquals(CrnnPreprocessingMode.LEGACY, settings(debug = false).selectedMode)
        assertFalse(settings(debug = false).select(CrnnPreprocessingMode.LEGACY))
        assertEquals(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE, settings().selectedMode)
    }

    @Test fun unknownPreferenceFallsBackAndScanSnapshotCannotChangeMidScan() {
        context.getSharedPreferences("single_crnn_debug", Context.MODE_PRIVATE).edit()
            .putString("preprocessing_mode", "unknown-mode").commit()
        val before = settings().snapshot(SingleScanSource.CAMERA)
        assertEquals(CrnnPreprocessingMode.LEGACY, before.mode)
        settings().select(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE)
        assertEquals(CrnnPreprocessingMode.LEGACY, before.mode)
        assertEquals(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE, settings().snapshot(SingleScanSource.GALLERY).mode)
    }

    private class CapturingRuntime : CrnnInferenceRuntime {
        override val inputShape = intArrayOf(1, 512, 32)
        override val outputShape = intArrayOf(1, 128, 80)
        override val inputType = "FLOAT32"
        override val outputType = "FLOAT32"
        val inputs = mutableListOf<ByteBuffer>()
        var closed = false
        override fun run(input: ByteBuffer): Array<Array<FloatArray>> {
            inputs.add(ByteBuffer.allocate(input.remaining()).order(input.order()).apply { put(input.duplicate()); rewind() })
            return Array(1) { Array(128) { FloatArray(80).apply { this[79] = 10f } } }
        }
        override fun close() { closed = true }
    }

    @Test fun cameraAndGalleryPassSelectedModeThroughSharedPipelineIntoActualCrnnPreprocessing() = runBlocking {
        val photo = javaClass.classLoader!!.getResourceAsStream("board_test_5.jpg").use { BitmapFactory.decodeStream(it) }
        val preprocessed = WhiteboardPreprocessor().process(photo)
        val regions = ConnectedComponentLabeler().label(preprocessed.binarizedBitmap, preprocessed.recognitionBitmap)
        val crops = regions.filter { it.type == RegionType.TEXT }.flatMap { PhysicalLineSegmenter().splitIntoPhysicalLines(it) }
        assertTrue(crops.isNotEmpty())
        val charset = " !\"#&'()*+,-./0123456789:;?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".map { it.toString() }
        for (mode in CrnnPreprocessingMode.values()) for (source in listOf(SingleScanSource.CAMERA, SingleScanSource.GALLERY)) {
            settings().select(mode)
            val request = settings().snapshot(source)
            val runtime = CapturingRuntime()
            var receivedMode: CrnnPreprocessingMode? = null
            val factory = SingleScanPipelineFactory { appContext, selectedMode ->
                assertSame(context.applicationContext, appContext)
                receivedMode = selectedMode
                CrnnLineRecognizer(appContext, selectedMode, runtime, charset)
            }
            val pipeline = factory.create(context, request)
            try { pipeline.process(photo) } finally { pipeline.close() }
            assertEquals("Wrong mode for $source", mode, receivedMode)
            assertEquals(crops.size, runtime.inputs.size)
            crops.forEachIndexed { index, crop ->
                assertEquals("Wrong actual CRNN input for $source/$mode line $index",
                    CrnnPreprocessing(mode).prepare(crop.bitmap).input, runtime.inputs[index])
            }
            assertTrue(runtime.closed)
        }
    }

    @Test fun pipelineConstructorStillDefaultsToLegacy() {
        var receivedMode: CrnnPreprocessingMode? = null
        val runtime = CapturingRuntime()
        val pipeline = WhiteboardRecognitionPipeline(context, recognizerFactory = { _, mode ->
            receivedMode = mode
            object : LineRecognizer {
                override suspend fun recognize(regions: List<com.example.note2snap.ccl.Region>) = emptyList<RecognizedLine>()
                override fun close() = runtime.close()
            }
        })
        assertEquals(CrnnPreprocessingMode.LEGACY, receivedMode)
        pipeline.close()
    }

    @Test fun debugLongPressSelectsReferenceWhileNormalTabClickIsPreserved() {
        debugFlag(true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val tab = View(activity)
            var clicks = 0
            tab.setOnClickListener { clicks++ }
            SingleScanDebugControls.install(tab, activity) { false }
            tab.performClick()
            assertEquals(1, clicks)
            assertTrue(tab.performLongClick())
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(0, dialog.listView.checkedItemPosition)
            dialog.listView.performItemClick(null, 1, 1)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE, SingleScanPreprocessingSettings(activity).selectedMode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cancellingSelectorKeepsModeAndBusyScanDoesNotOpenIt() {
        debugFlag(true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val tab = View(activity)
            SingleScanDebugControls.install(tab, activity) { false }
            tab.performLongClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(null, 1, 1)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CrnnPreprocessingMode.LEGACY, SingleScanPreprocessingSettings(activity).selectedMode)
            SingleScanDebugControls.install(tab, activity) { true }
            tab.performLongClick()
            assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
            assertFalse(dialog.isShowing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun releaseInstallsNoDebugLongPressAndAlwaysUsesLegacy() {
        debugFlag(false)
        settings().select(CrnnPreprocessingMode.SIMPLE_HTR_REFERENCE)
        val tab = View(context)
        SingleScanDebugControls.install(tab, context) { false }
        assertFalse(tab.isLongClickable)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(CrnnPreprocessingMode.LEGACY, SingleScanPreprocessingSettings(context).selectedMode)
    }
}
