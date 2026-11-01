package com.example.note2snap.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThesisComparisonExportTest {
    @Test fun exportsImagesIdsSkippedEventsDuplicatesAndIndependentCrnnFailure() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir,"paddle-comparisons")
        val before = root.listFiles()?.map { it.name }?.toSet().orEmpty()
        val chars = listOf("") + File("src/main/assets/ocr/paddle/ppocrv5_en_dict.txt").readLines() + " "
        var calls=0
        val runtime=object : PaddleRecognitionRuntime {
            override fun run(input: PaddleInput): PaddleOutput {
                calls++
                return PaddleOutput(FloatArray(438).apply { this[chars.indexOf("A")]=1f },1,438)
            }
            override fun close() { }
        }
        val recorder=PaddleOcrComparison(context, exports=true, engineFactory={PaddleOcrLineRecognizer(runtime,
            PaddleInputPreprocessor { _,w -> ByteArray(w*48*3) },PaddleCtcDecoder(chars))})
        val image=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val box=Rect(0,0,80,40)
        val region=Region(7,box,RegionType.TEXT,100,image)
        try {
            recorder.source(image,image,listOf(region))
            recorder.skipped(region)
            recorder.compare(image,7,box,"Raw CRNN",1,2,3)
            recorder.compare(image,7,box,"I",1,2,3)
            recorder.failed(image,7,box,IllegalStateException("CRNN failed"))
        } finally { recorder.close(); image.recycle() }
        val file=root.listFiles()!!.single { it.name !in before && it.extension=="jsonl" }
        val rows=file.readLines().map(::JSONObject)
        assertEquals(3,rows.size);assertEquals(3,calls)
        assertEquals("Raw CRNN",rows[0].getJSONObject("crnn").getString("text"))
        assertEquals("A",rows[2].getJSONObject("paddle").getString("text"))
        assertTrue(rows[2].has("crnnError"))
        assertEquals(rows[0].getString("lineId"),rows[1].getString("duplicateBoxOf"))
        assertTrue(File(root,rows[0].getString("sourceFile")).isFile)
        rows.forEach { assertTrue(File(root,it.getString("cropFile")).isFile) }
        val assets=File(root,file.nameWithoutExtension+"-assets")
        assertTrue(File(assets,"recognition.png").isFile)
        assertEquals("no_physical_lines",JSONObject(File(assets,"events.jsonl").readText()).getString("status"))
        assertEquals("TEXT",JSONObject(File(assets,"source.json").readText()).getJSONArray("regions").getJSONObject(0).getString("type"))
    }
}
