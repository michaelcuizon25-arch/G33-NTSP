package com.example.note2snap.recognition

import com.example.note2snap.BuildConfig
import com.example.note2snap.ccl.Region
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

internal interface CrnnLineComparison : Closeable {
    fun source(photo: Bitmap, recognition: Bitmap, regions: List<Region>) { }
    fun skipped(region: Region) { }
    suspend fun failed(bitmap: Bitmap, regionId: Int, box: Rect, error: Exception) { }
    suspend fun compare(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
                         crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long)
}

/** Research opt-in only. Independent local records; never returns replacement note text. */
internal class PaddleOcrComparison(context: Context, private val mode: CrnnPreprocessingMode = CrnnPreprocessingMode.LEGACY,
    private val exports: Boolean = BuildConfig.ENABLE_OCR_EVALUATION_EXPORTS,
    private val engineFactory: () -> PaddleOcrLineRecognizer = { PaddleOcrLineRecognizer(context.applicationContext) }) : CrnnLineComparison {
    private val appContext = context.applicationContext
    private var recognizer: PaddleOcrLineRecognizer? = null
    private var file = File(appContext.filesDir, "paddle-comparisons/${UUID.randomUUID()}.jsonl")
    private var assets = File(file.parentFile, file.nameWithoutExtension + "-assets")
    private var sourceRecorded = false
    private var sequence = 0
    private val boxes = mutableMapOf<String, String>()
    private fun save(name: String, bitmap: Bitmap) {
        check(assets.mkdirs() || assets.isDirectory)
        File(assets, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    override fun source(photo: Bitmap, recognition: Bitmap, regions: List<Region>) {
        if (!exports) return
        if (sourceRecorded) {
            file = File(appContext.filesDir, "paddle-comparisons/${UUID.randomUUID()}.jsonl")
            assets = File(file.parentFile, file.nameWithoutExtension + "-assets")
            sequence = 0; boxes.clear()
        }
        sourceRecorded = true
        save("source.png", photo); save("recognition.png", recognition)
        File(assets, "source.json").writeText(JSONObject().put("coordinateSpace", "recognition.png pixels; right/bottom exclusive")
            .put("sourceWidth", photo.width).put("sourceHeight", photo.height)
            .put("recognitionWidth", recognition.width).put("recognitionHeight", recognition.height)
            .put("regions", org.json.JSONArray(regions.map { r -> JSONObject().put("regionId", r.id)
                .put("type", r.type.name).put("pixelArea",r.pixelArea).put("box", org.json.JSONArray(listOf(r.boundingBox.left,r.boundingBox.top,r.boundingBox.right,r.boundingBox.bottom))) }))
            .put("schemaVersion",2).put("enhancementEnabled",BuildConfig.ENABLE_OPENCV_ENHANCEMENT)
            .put("correctionEnabled",BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION).put("crnnMode", mode.name).put("crnnModelAsset", CrnnModelAssets.MODEL)
            .put("paddleModelSha256", PaddleModelAssets.MODEL_SHA256) .toString(2))
    }
    override fun skipped(region: Region) {
        if (!exports) return
        check(assets.mkdirs() || assets.isDirectory)
        File(assets, "events.jsonl").appendText(JSONObject().put("regionId",region.id).put("status","no_physical_lines").toString()+"\n")
    }
    override suspend fun failed(bitmap: Bitmap, regionId: Int, box: Rect, error: Exception) {
        if (exports) record(bitmap,regionId,box,"",0,0,0,error.javaClass.simpleName+": "+error.message)
    }
    override suspend fun compare(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
                                 crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long): Unit = record(bitmap,regionId,box,crnnText,crnnPreprocessingNs,crnnInferenceNs,crnnDecodingNs,null)
    private suspend fun record(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
        crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long, error: String?): Unit = withContext(Dispatchers.Default) {
        val record = JSONObject().put("regionId", regionId).put("imageWidth", bitmap.width).put("imageHeight", bitmap.height)
            .put("box", org.json.JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
            .put("crnn", JSONObject().put("text", crnnText).put("preprocessingNs", crnnPreprocessingNs)
                .put("inferenceNs", crnnInferenceNs).put("decodingNs", crnnDecodingNs))
            .put("paddleModelSha256", PaddleModelAssets.MODEL_SHA256)
        if (error != null) record.put("crnnError",error)
        if (exports) {
            val id = "line-%04d".format(++sequence)
            val previous = boxes.putIfAbsent(box.toShortString(),id)
            record.put("lineId",id).put("scanId",file.nameWithoutExtension)
                .put("cropFile",assets.name+"/"+id+".png").put("sourceFile",assets.name+"/source.png")
                .put("recognitionFile",assets.name+"/recognition.png").put("rawBeforeCorrectionAndStructuring",true)
                .put("engines",org.json.JSONArray(listOf("CRNN","en_PP-OCRv5_mobile_rec")))
            if (previous != null) record.put("duplicateBoxOf",previous)
            try { withContext(Dispatchers.IO) { save(id+".png",bitmap) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { record.put("cropExportError",e.javaClass.simpleName) }
        }
        try {
            val engine = recognizer ?: engineFactory().also { recognizer = it }
            val result = engine.recognizeLine(bitmap)
            record.put("paddle", JSONObject().put("text", result.prediction.text)
                .put("meanSelectedProbabilityUncalibrated", result.prediction.meanSelectedProbability)
                .put("preprocessingNs", result.preprocessingNs).put("inferenceIncludingColdInitNs", result.inferenceNs)
                .put("decodingNs", result.decodingNs))
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { record.put("paddleError", JSONObject().put("type", error.javaClass.simpleName).put("message", error.message))
        } catch (error: LinkageError) { record.put("paddleError", JSONObject().put("type", error.javaClass.simpleName).put("message", error.message)) }
        try {
            withContext(Dispatchers.IO) {
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                file.appendText(record.toString() + "\n")
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Log.w("PADDLE_COMPARISON", "Comparison record unavailable", error) }
    }
    override fun close() { recognizer?.close(); recognizer = null }
}
