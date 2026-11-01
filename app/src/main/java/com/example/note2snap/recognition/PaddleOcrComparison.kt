package com.example.note2snap.recognition

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
    suspend fun compare(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
                         crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long)
}

/** Research opt-in only. Independent local records; never returns replacement note text. */
internal class PaddleOcrComparison(context: Context) : CrnnLineComparison {
    private val appContext = context.applicationContext
    private var recognizer: PaddleOcrLineRecognizer? = null
    private val file = File(appContext.filesDir, "paddle-comparisons/${UUID.randomUUID()}.jsonl")
    override suspend fun compare(bitmap: Bitmap, regionId: Int, box: Rect, crnnText: String,
                                 crnnPreprocessingNs: Long, crnnInferenceNs: Long, crnnDecodingNs: Long): Unit = withContext(Dispatchers.Default) {
        val record = JSONObject().put("regionId", regionId).put("imageWidth", bitmap.width).put("imageHeight", bitmap.height)
            .put("box", org.json.JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
            .put("crnn", JSONObject().put("text", crnnText).put("preprocessingNs", crnnPreprocessingNs)
                .put("inferenceNs", crnnInferenceNs).put("decodingNs", crnnDecodingNs))
            .put("paddleModelSha256", PaddleModelAssets.MODEL_SHA256)
        try {
            val engine = recognizer ?: PaddleOcrLineRecognizer(appContext).also { recognizer = it }
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
