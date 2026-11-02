package com.example.note2snap.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.note2snap.BuildConfig
import com.example.note2snap.ccl.Region
import com.example.note2snap.model.StructuredNote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit research exports only. No second inference, bitmap mutation, or text in Logcat.
 * All I/O/serialization failures are isolated from recognition. Coordinates use the decoded
 * recognition image, left/top inclusive and right/bottom exclusive.
 */
internal class PrimaryOcrDiagnostics(
    private val context: Context,
    private val enabled: Boolean = BuildConfig.ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS,
    private val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "primary-ocr-diagnostics")
) {
    var directory: File? = null
        private set
    private var report = JSONObject()
    private var lines = JSONArray()
    private var decisions = JSONArray()
    private var active = false

    private inline fun observe(block: () -> Unit) {
        if (!enabled) return
        try { block() }
        catch (_: Exception) { active = false; Log.w(TAG, "Research export unavailable") }
        catch (_: LinkageError) { active = false; Log.w(TAG, "Research export unavailable") }
    }

    fun begin(photo: Bitmap, correctionLoaded: Boolean) = observe {
        active = false
        val id = UUID.randomUUID().toString()
        directory = File(root, id)
        check(directory!!.mkdirs())
        lines = JSONArray()
        decisions = JSONArray()
        report = JSONObject().put("schemaVersion", 1).put("scanId", id)
            .put("status", "started").put("sourceWidth", photo.width).put("sourceHeight", photo.height)
            .put("recognitionMode", BuildConfig.SINGLE_SCAN_RECOGNITION_MODE)
            .put("correctionRequested", BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION)
            .put("correctionLoaded", correctionLoaded)
            .put("openCvRequested", BuildConfig.ENABLE_OPENCV_ENHANCEMENT)
            .put("borderFilterRevision", "frame-track-v1.1+side-border-587e0e6")
            .put("coordinateSpace", "recognition image; right/bottom exclusive")
            .put("lines", lines).put("filterDecisions", decisions)
        png("source.png", photo)
        report.put("decodedSourcePngSha256", hash(File(directory, "source.png").readBytes()))
        report.put("modelSha256", assetHash(PaddleModelAssets.MODEL))
            .put("dictionarySha256", assetHash(PaddleModelAssets.DICTIONARY))
        active = true
        flush()
    }

    fun component(stage: String, box: Rect, accepted: Boolean, median: Int) = observe {
        if (!active) return@observe
        decisions.put(JSONObject().put("stage", stage).put("box", box(box))
            .put("accepted", accepted).put("medianTextHeight", median))
        // Written once CCL finishes, avoiding one disk write per component.
    }

    fun source(recognition: Bitmap, regions: List<Region>) = observe {
        if (!active) return@observe
        png("recognition.png", recognition)
        report.put("recognitionWidth", recognition.width).put("recognitionHeight", recognition.height)
        report.put("regions", JSONArray().apply {
            regions.forEach { region ->
                put(JSONObject().put("regionId", region.id).put("box", box(region.boundingBox))
                    .put("type", region.type.name).put("pixelArea", region.pixelArea))
                png("region-${region.id}.png", region.croppedBitmap)
            }
        })
        flush()
    }

    fun regionDecision(region: Region, reason: String) = observe {
        if (!active) return@observe
        decisions.put(JSONObject().put("stage", "primary_region").put("regionId", region.id)
            .put("box", box(region.boundingBox)).put("reason", reason))
        flush()
    }

    fun line(id: Int, regionId: Int, crop: PhysicalLine, rejection: String?) = observe {
        if (!active) return@observe
        png("line-$id.png", crop.bitmap)
        lines.put(JSONObject().put("lineId", id).put("regionId", regionId)
            .put("box", box(crop.boundingBox)).put("cropWidth", crop.bitmap.width)
            .put("cropHeight", crop.bitmap.height).put("crop", "line-$id.png")
            .put("selectedEngine", if (rejection == null) "pending" else "skip")
            .put("reason", rejection ?: JSONObject.NULL))
        flush()
    }

    fun attempt(id: Int, engine: String, text: String?, elapsedNs: Long, failure: Throwable? = null) = observe {
        if (!active) return@observe
        row(id)?.put("${engine}RawText", text ?: JSONObject.NULL)
            ?.put("${engine}ElapsedNs", elapsedNs)
            ?.put("${engine}Failure", failure?.javaClass?.name ?: JSONObject.NULL)
        flush()
    }

    fun selected(id: Int, engine: String, reason: String, outputStart: Int, outputCount: Int) = observe {
        if (!active) return@observe
        row(id)?.put("selectedEngine", engine)?.put("reason", reason)
            ?.put("outputIndices", JSONArray((outputStart until outputStart + outputCount).toList()))
        flush()
    }

    fun finish(raw: List<RecognizedLine>, corrected: List<RecognizedLine>,
               corrections: List<LineTextCorrection>, note: StructuredNote) = observe {
        if (!active) return@observe
        val output = JSONArray()
        raw.forEachIndexed { index, line ->
            val correction = corrections.firstOrNull { it.lineIndex == index }?.result
            output.put(JSONObject().put("outputIndex", index).put("regionId", line.sourceRegionId)
                .put("box", box(line.boundingBox)).put("rawText", line.text)
                .put("correctedText", corrected[index].text)
                .put("correctionSkippedReason", correction?.skippedReason ?: JSONObject.NULL)
                .put("suggestions", JSONArray().apply {
                    correction?.suggestedCorrections?.forEach { change ->
                        put(JSONObject().put("original", change.original).put("replacement", change.replacement)
                            .put("start", change.start).put("endExclusive", change.endExclusive)
                            .put("applied", change.applied).put("evidence", change.evidence.source.name)
                            .put("editDistance", change.evidence.editDistance).put("reason", change.evidence.reason))
                    }
                }))
        }
        report.put("outputs", output).put("correctionExecuted", report.getBoolean("correctionLoaded"))
            .put("structuredNote", JSONObject().put("title", note.title).put("blocks", JSONArray().apply {
                note.blocks.forEach { block ->
                    put(JSONObject().put("rawText", block.rawText).put("formattedText", block.formattedText)
                        .put("type", block.type.name).put("box", block.boundingBox?.let { box(it) } ?: JSONObject.NULL))
                }
            })).put("status", "completed")
        flush()
        active = false
    }

    fun failed(error: Throwable) = observe {
        if (!active) return@observe
        report.put("status", "failed").put("failureType", error.javaClass.name)
        flush()
        active = false
    }

    private fun row(id: Int): JSONObject? = (0 until lines.length())
        .map { lines.getJSONObject(it) }.firstOrNull { it.getInt("lineId") == id }
    private fun flush() { File(directory, "scan.json").writeText(report.toString(2)) }
    private fun png(name: String, bitmap: Bitmap) {
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assetHash(name: String): Any = try {
        context.assets.open(name).use { hash(it.readBytes()) }
    } catch (_: Exception) { JSONObject.NULL }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun box(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    companion object { private const val TAG = "PRIMARY_OCR_EXPORT" }
}
