package com.example.note2snap.recognition
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfStage

import com.example.note2snap.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.note2snap.ccl.Region
import com.example.note2snap.model.StructuredNote
import com.example.note2snap.preprocessing.PreprocessingResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** Observational only: diagnostic failures must never change recognition results. */
internal class CrnnDiagnostics(context: Context, val enabled: Boolean = BuildConfig.ENABLE_CRNN_DIAGNOSTIC_EXPORTS) {
    private val root = File(context.getExternalFilesDir(null) ?: context.cacheDir, "crnn-diagnostics")
    var directory = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}")
        private set
    private var sourceRecorded = false
    private val records = linkedMapOf<Int, JSONObject>()
    private var sequence = 0
    private var reviewDirty = false
    private val assetHashes = mutableMapOf<String, String>()

    init {
        safely {
            check(directory.mkdirs() || directory.isDirectory)
            Log.i(TAG, "Diagnostic session: ${directory.absolutePath}")
        }
    }

    fun recordModel(context: Context, model: String, charsetFile: String, charset: List<String>,
                    inputShape: IntArray, outputShape: IntArray, inputType: String, outputType: String,
                    outputShapeStage: String = "Before inference; dynamic output tensor deliberately unqueried",
                    inputSignature: IntArray = inputShape, outputSignature: IntArray = outputShape,
                    cachedOutputShape: IntArray = outputShape) = safely {
        fun hashAsset(name: String): String = assetHashes.getOrPut(name) {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(name).use { stream ->
                val bytes = ByteArray(8192)
                var count = stream.read(bytes)
                while (count >= 0) {
                    digest.update(bytes, 0, count)
                    count = stream.read(bytes)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        writeJson("model.json", JSONObject()
            .put("modelAsset", model).put("modelSha256", hashAsset(model))
            .put("charsetAsset", charsetFile).put("charsetSha256", hashAsset(charsetFile))
            .put("charset", JSONArray(charset)).put("blankIndex", 79)
            .put("inputShape", JSONArray(inputShape.toList())).put("inputType", inputType)
            .put("outputShape", JSONArray(outputShape.toList())).put("outputType", outputType)
            .put("inputShapeSignature", JSONArray(inputSignature.toList()))
            .put("outputShapeSignature", JSONArray(outputSignature.toList()))
            .put("cachedOutputTensorShape", JSONArray(cachedOutputShape.toList()))
            .put("tensorByteOrder", ByteOrder.nativeOrder().toString())
            .put("inputLayout", "x-major: offset=(x*32+y)*4")
            .put("normalization", "gray / 255 - 0.5; dark ink negative, white padding +0.5")
            .put("trainingParity", "Per-line preprocessing records identify the selected transform. Legacy fits 512x32 at top-left; reference uses height=32, dynamic width +16, centered affine margin and multiple-of-4 width.")
            .put("outputShapeStage", outputShapeStage)
            .put("confidenceSemantics", "Existing average of selected raw outputs; notebook exports logits, not calibrated probabilities"))
    }

    fun recordSource(photo: Bitmap, preprocessed: PreprocessingResult, regions: List<Region>) = safely {
        if (sourceRecorded || records.isNotEmpty()) {
            flushReview()
            val modelMetadata = File(directory, "model.json").takeIf { it.exists() }?.readText()
            directory = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}")
            check(directory.mkdirs() || directory.isDirectory)
            records.clear()
            sequence = 0
            if (modelMetadata != null) File(directory, "model.json").writeText(modelMetadata)
            Log.i(TAG, "Diagnostic session: ${directory.absolutePath}")
            reviewDirty = false
        }
        sourceRecorded = true
        saveBitmap("source.png", photo)
        saveBitmap("recognition.png", preprocessed.recognitionBitmap)
        if (preprocessed.enhancementRequested) {
            saveBitmap("recognition-original.png", preprocessed.originalRecognitionBitmap)
        }
        saveBitmap("ccl-binary.png", preprocessed.binarizedBitmap)
        val regionJson = JSONArray()
        regions.forEach { region ->
            saveBitmap("region-${region.id}.png", region.croppedBitmap)
            regionJson.put(JSONObject().put("id", region.id).put("type", region.type.name)
                .put("box", box(region.boundingBox)).put("pixelArea", region.pixelArea)
                .put("image", "region-${region.id}.png"))
        }
        writeJson("source.json", JSONObject()
            .put("sourceWidth", photo.width).put("sourceHeight", photo.height)
            .put("recognitionWidth", preprocessed.recognitionBitmap.width)
            .put("recognitionHeight", preprocessed.recognitionBitmap.height)
            .put("coordinateSpace", "recognition.png pixels; right/bottom exclusive")
            .put("sourceScaleX", photo.width.toDouble() / preprocessed.recognitionBitmap.width)
            .put("sourceScaleY", photo.height.toDouble() / preprocessed.recognitionBitmap.height)
            .put("enhancement", JSONObject()
                .put("requested", preprocessed.enhancementRequested)
                .put("applied", preprocessed.enhancementApplied)
                .put("reason", preprocessed.enhancementReason ?: JSONObject.NULL)
                .put("elapsedMs", preprocessed.enhancementElapsedMs)
                .put("perspectiveApplied", false))
            .put("regions", regionJson))
    }

    fun beginLine(region: Region, line: PhysicalLine, input: ByteBuffer,
                  prepared: PreparedCrnnInput? = null): Int {
        val id = sequence++
        safely {
            val prefix = "line-%04d".format(id)
            saveBitmap("$prefix-crop.png", line.bitmap)
            // Read a duplicate: inference receives the SAME buffer, position and bytes.
            val snapshot = input.duplicate().order(input.order()).apply { rewind() }
            FileOutputStream(File(directory, "$prefix-input.f32")).use { stream ->
                while (snapshot.hasRemaining()) stream.channel.write(snapshot)
            }
            val floats = input.duplicate().order(input.order()).apply { rewind() }.asFloatBuffer()
            val preview = Bitmap.createBitmap(512, 32, Bitmap.Config.ARGB_8888)
            for (x in 0 until 512) for (y in 0 until 32) {
                val gray = ((floats.get(x * 32 + y) + 0.5f) * 255).roundToInt().coerceIn(0, 255)
                preview.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
            saveBitmap("$prefix-input.png", preview)
            preview.recycle()
            records[id] = JSONObject().put("id", id).put("sourceRegionId", region.id)
                .put("regionBox", box(region.boundingBox)).put("box", box(line.boundingBox))
                .put("cropWidth", line.bitmap.width).put("cropHeight", line.bitmap.height)
                .put("cropImage", "$prefix-crop.png").put("inputImage", "$prefix-input.png")
                .put("inputTensor", "$prefix-input.f32").put("inputBytes", input.limit())
                .put("inputByteOrder", input.order().toString())
                .put("status", "pending").put("cropCompleteness", "unreviewed")
                .put("containsCompleteHandwrittenLine", JSONObject.NULL)
                .put("completenessEvidence", "Human review against source.png required")
                .put("groundTruth", JSONObject.NULL)
                .put("preprocessing", JSONObject()
                    .put("requestedMode", (prepared?.requestedMode ?: CrnnPreprocessingMode.LEGACY).name)
                    .put("actualMode", (prepared?.actualMode ?: CrnnPreprocessingMode.LEGACY).name)
                    .put("referenceWidth", prepared?.referenceWidth ?: JSONObject.NULL)
                    .put("fallbackReason", prepared?.fallbackReason ?: JSONObject.NULL))
            persist(id)
        }
        return id
    }

    fun completeLine(id: Int, rawText: String, text: String, confidence: Float,
                     output: Array<Array<FloatArray>>) = safely {
        val record = records[id] ?: return@safely
        val prefix = "line-%04d".format(id)
        val size = output.sumOf { batch -> batch.sumOf { it.size } }
        val bytes = ByteBuffer.allocate(size * 4).order(ByteOrder.nativeOrder())
        output.forEach { batch -> batch.forEach { step -> step.forEach { bytes.putFloat(it) } } }
        File(directory, "$prefix-output.f32").writeBytes(bytes.array())
        record.put("rawPredictedText", rawText).put("predictedText", text)
            .put("confidence", if (confidence.isFinite()) confidence else JSONObject.NULL)
            .put("outputTensor", "$prefix-output.f32").put("status", "recognized")
            .put("outputBufferShape", JSONArray(listOf(output.size, output[0].size, output[0][0].size)))
        persist(id)
        Log.i(TAG, "line=$id region=${record.getInt("sourceRegionId")} box=${record.getJSONObject("box")} predicted=${JSONObject.quote(text)}")
    }

    fun failLine(id: Int, error: Exception, stage: String = "inference") = safely {
        val record = records[id] ?: return@safely
        record.put("status", "${stage}_error").put("failureStage", stage).put("error", error.toString())
        persist(id)
    }

    fun recordRecognitionSummary(summary: JSONObject) = safely {
        writeJson("recognition-summary.json", summary)
        flushReview()
    }

    fun recordCorrections(enabled: Boolean, corrections: List<LineTextCorrection>) = safely {
        val lines = JSONArray()
        for (line in corrections) {
            val result = line.result
            val suggestions = JSONArray()
            for (change in result.suggestedCorrections) {
                suggestions.put(JSONObject().put("start", change.start).put("endExclusive", change.endExclusive)
                    .put("original", change.original).put("replacement", change.replacement)
                    .put("applied", change.applied).put("evidence", change.evidence.source.name)
                    .put("editDistance", change.evidence.editDistance).put("reason", change.evidence.reason))
            }
            lines.put(JSONObject().put("lineIndex", line.lineIndex).put("sourceRegionId", line.sourceRegionId)
                .put("originalText", result.originalText).put("correctedText", result.correctedText)
                .put("skippedReason", result.skippedReason ?: JSONObject.NULL).put("suggestions", suggestions))
        }
        writeJson("text-corrections.json", JSONObject().put("enabled", enabled)
            .put("offsetEncoding", "UTF-16, original line, end exclusive")
            .put("confidence", "No calibrated correction probability is available")
            .put("lines", lines))
    }

    fun recordStructured(note: StructuredNote) = safely {
        val blocks = JSONArray()
        note.blocks.forEach { block ->
            blocks.put(JSONObject().put("text", block.rawText).put("type", block.type.name)
                .put("box", block.boundingBox?.let { box(it) } ?: JSONObject.NULL))
        }
        writeJson("structured.json", JSONObject().put("title", note.title).put("blocks", blocks))
    }

    private fun persist(id: Int) {
        writeJson("line-%04d.json".format(id), records.getValue(id))
        reviewDirty = true
    }

    fun flush() = safely { flushReview() }

    private fun flushReview() {
        if (!reviewDirty) return
        File(directory, "lines.jsonl").writeText(records.values.joinToString("\n", postfix = "\n") { it.toString() })
        writeReview()
        reviewDirty = false
    }

    private fun writeReview() {
        val data = JSONArray(records.values.toList()).toString().replace("<", "\\u003c")
        File(directory, "review.html").writeText("""
            <!doctype html><meta charset="utf-8"><title>CRNN crop review</title>
            <style>body{font:16px system-ui;margin:24px}article{border-top:1px solid #aaa;padding:16px 0}
            img{display:block;max-width:100%;margin:8px 0}input{width:70%}button,select{padding:8px}</style>
            <h1>CRNN crop review</h1><p>Compare each crop against the full board. A readable fragment is still a fragment.
            One physical handwritten row is complete even when its sentence wraps onto another row.</p>
            <p><a href="source.png">Original photo</a> · <a href="recognition.png">Coordinate reference</a> ·
            <a href="ccl-binary.png">CCL binary</a> · <a href="structured.json">Structured blocks</a></p>
            <button id="save">Export reviewed labels</button><main id="lines"></main>
            <script>
            const rows=$data;
            for(const r of rows){
              const a=document.createElement('article');
              const h=document.createElement('h2');h.textContent='Line '+r.id+' · region '+r.sourceRegionId;a.append(h);
              const p=document.createElement('p');p.textContent=JSON.stringify(r.box)+' · '+r.status+' · prediction: '+(r.predictedText??'unavailable');a.append(p);
              for(const src of [r.cropImage,r.inputImage]){const img=document.createElement('img');img.src=src;img.alt=src;a.append(img);}
              const select=document.createElement('select');
              for(const v of ['unreviewed','complete','fragment','multiple_lines','noise','uncertain']){
                const o=document.createElement('option');o.value=v;o.textContent=v;select.append(o);}
              select.value=r.cropCompleteness;select.onchange=()=>{
                r.cropCompleteness=select.value;r.containsCompleteHandwrittenLine=select.value==='complete'?true:
                  ['fragment','multiple_lines','noise'].includes(select.value)?false:null;
                r.completenessEvidence='Manual review against original photo';};a.append(select);
              const t=document.createElement('input');t.placeholder='Transcribe exactly the visible ink (ground truth)';
              t.value=r.groundTruth??'';t.oninput=()=>r.groundTruth=t.value;a.append(t);document.getElementById('lines').append(a);
            }
            document.getElementById('save').onclick=()=>{
              const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(rows,null,2)],{type:'application/json'}));
              a.download='reviewed-lines.json';a.click();URL.revokeObjectURL(a.href);};
            </script>
        """.trimIndent())
    }

    private fun writeJson(name: String, value: JSONObject) = File(directory, name).writeText(value.toString(2))
    private fun saveBitmap(name: String, bitmap: Bitmap) {
        FileOutputStream(File(directory, name)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun box(rect: Rect) = JSONObject().put("left", rect.left).put("top", rect.top)
        .put("right", rect.right).put("bottom", rect.bottom)
    private inline fun safely(action: () -> Unit) {
        if (!enabled) return
        try { PerformanceDiagnostics.measure(PerfStage.DIAGNOSTIC_IO) { action() } } catch (error: Exception) { Log.w(TAG, "Diagnostic write failed; recognition continues", error) }
    }
    private companion object { const val TAG = "CRNN_DIAGNOSTICS" }
}
