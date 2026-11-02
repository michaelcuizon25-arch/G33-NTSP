package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.note2snap.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit research replay of the original exported source; never writes a user's note. */
@RunWith(AndroidJUnit4::class)
class CapturedFramePipelineDeviceTest {
    @Test fun originalPangramHasFourLinesWithoutFrameEntries(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(BuildConfig.SINGLE_SCAN_RECOGNITION_MODE == "PADDLE_PRIMARY_WITH_CRNN_FALLBACK" &&
            BuildConfig.ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sourcePath=InstrumentationRegistry.getArguments().getString("capturedSource")
        org.junit.Assume.assumeTrue("Pass an explicit capturedSource path",!sourcePath.isNullOrBlank())
        val bitmap=BitmapFactory.decodeFile(sourcePath!!)
        assertNotNull("Captured source must be available on device",bitmap)
        val original=bitmap.copy(Bitmap.Config.ARGB_8888,false)
        val root=File(context.getExternalFilesDir(null),"primary-ocr-diagnostics")
        val before=root.listFiles()?.map{it.name}?.toSet().orEmpty()
        val pipeline=WhiteboardRecognitionPipeline(context)
        val result=try{pipeline.process(bitmap)}finally{pipeline.close()}
        val directory=root.listFiles()!!.single{it.name !in before}
        File(directory,"fixture.txt").writeText("Explicit original-pangram source replay; no note saved.")
        val report=JSONObject(File(directory,"scan.json").readText())
        try {
            assertTrue(bitmap.sameAs(original))
            assertEquals("completed",report.getString("status"))
            assertEquals("236b9f79d837acbe5ef9ee08f17b01a13ba324e247466afb161a808ef561f9c6",report.getString("decodedSourcePngSha256"))
            assertEquals(4,result.recognizedLines.size)
            assertEquals(4,result.structuredNote.blocks.size)
            assertEquals(result.recognizedLines.map{it.text},result.structuredNote.blocks.map{it.rawText})
            assertEquals("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",result.recognizedLines[1].text.removeSuffix("."))
            assertEquals("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG",result.recognizedLines[3].text.removeSuffix("."))
            assertFalse(result.recognizedLines.any{it.text=="I"})
            assertTrue(report.getBoolean("correctionExecuted"))
            val rows=report.getJSONArray("lines")
            assertEquals(4,rows.length())
            for(i in 0 until rows.length()) assertEquals("paddle",rows.getJSONObject(i).getString("selectedEngine"))
            val events=report.getJSONArray("filterDecisions")
            assertTrue((0 until events.length()).count{events.getJSONObject(it).getString("stage")=="inset_frame_rejected"}>=3)
            assertEquals(PaddleModelAssets.MODEL_SHA256,report.getString("modelSha256"))
            assertEquals(PaddleModelAssets.DICTIONARY_SHA256,report.getString("dictionarySha256"))
        } finally {bitmap.recycle();original.recycle()}
    }

    @Test fun editedNoteCanBeSavedAndReloadedInIsolatedDatabase(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=androidx.room.Room.inMemoryDatabaseBuilder(context,
            com.example.note2snap.data.AppDatabase::class.java).build()
        try {
            val dao=database.appDao()
            val initial=com.example.note2snap.model.Note(title="OCR validation",content="first line<br/>second line",
                imagePath="research-fixture.png",dateEdited="test")
            val id=dao.insertNote(initial).toInt()
            val loaded=dao.getNoteById(id)!!
            assertEquals(initial.content,loaded.content)
            dao.updateNote(loaded.copy(title="OCR validation edited",content="first line<br/>edited second line"))
            val edited=dao.getNoteById(id)!!
            assertEquals("OCR validation edited",edited.title)
            assertEquals("first line<br/>edited second line",edited.content)
            assertEquals(initial.imagePath,edited.imagePath)
        } finally { database.close() }
    }

    @Test fun injectedPaddleFailureStillUsesNativeCrnnOnCapturedBlankFrame(): Unit = runBlocking {
        val sourcePath=InstrumentationRegistry.getArguments().getString("capturedSource")
        org.junit.Assume.assumeTrue(!sourcePath.isNullOrBlank())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val crop=BitmapFactory.decodeFile(File(File(sourcePath!!).parentFile,"line-5.png").absolutePath)
        assertNotNull(crop)
        var fallbackCreations=0
        val primary=PaddlePrimaryLineRecognizer({throw IllegalStateException("Injected test-only Paddle failure")},{
            fallbackCreations++
            val runtime=TensorFlowLiteCrnnRuntime(context)
            try {
                CrnnLineRecognizer(context,CrnnPreprocessingMode.LEGACY,runtime,loadCrnnCharset(context),
                    physicalLineProvider={region->listOf(PhysicalLine(region.croppedBitmap,region.boundingBox))})
            } catch(error:Throwable){runtime.close();throw error}
        })
        try {
            val region=com.example.note2snap.ccl.Region(7,android.graphics.Rect(0,0,crop.width,crop.height),
                com.example.note2snap.ccl.RegionType.TEXT,529,crop)
            assertTrue(primary.recognize(listOf(region)).isEmpty())
            assertEquals(1,fallbackCreations)
        } finally {primary.close();crop.recycle()}
    }
}
