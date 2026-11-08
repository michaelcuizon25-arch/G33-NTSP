package com.example.note2snap.recognition

import android.content.Context
import android.util.Log
import com.example.note2snap.ccl.Region
import com.example.note2snap.ccl.RegionType
import com.example.note2snap.diagnostics.PerformanceDiagnostics
import com.example.note2snap.diagnostics.PerfMetric
import com.example.note2snap.diagnostics.PerfStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Explicit opt-in: Paddle first, CRNN only on a supported text crop needing fallback. */
internal class PaddlePrimaryLineRecognizer(
    private val paddleFactory: () -> PaddleOcrLineRecognizer,
    private val crnnFactory: () -> LineRecognizer,
    internal val diagnostics: PrimaryOcrDiagnostics? = null
) : LineRecognizer {
    constructor(context: Context, mode: CrnnPreprocessingMode) : this(
        { PaddleOcrLineRecognizer(context.applicationContext,
            refineCrops = com.example.note2snap.BuildConfig.ENABLE_PADDLE_CROP_REFINEMENT) },
        {
            val runtime = TensorFlowLiteCrnnRuntime(context.applicationContext)
            try {
                CrnnLineRecognizer(context.applicationContext, mode, runtime, loadCrnnCharset(context.applicationContext),
                    physicalLineProvider = { region -> listOf(PhysicalLine(region.croppedBitmap, region.boundingBox)) })
            } catch (error: Throwable) { runtime.close(); throw error }
        },
        if (com.example.note2snap.BuildConfig.ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS) PrimaryOcrDiagnostics(context.applicationContext) else null
    )
    private var paddle: PaddleOcrLineRecognizer? = null
    private var crnn: LineRecognizer? = null
    private var paddleCreationFailure: Throwable? = null
    private var crnnCreationFailure: Throwable? = null
    private var closed = false

    private fun paddle(): PaddleOcrLineRecognizer {
        check(!closed)
        paddle?.let { return it }
        paddleCreationFailure?.let { throw IllegalStateException("Paddle creation previously failed", it) }
        return try { paddleFactory().also { paddle = it } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { paddleCreationFailure = error; throw error }
        catch (error: LinkageError) { paddleCreationFailure = error; throw error }
    }
    private fun crnn(): LineRecognizer {
        check(!closed)
        crnn?.let { return it }
        crnnCreationFailure?.let { throw IllegalStateException("CRNN fallback creation previously failed", it) }
        return try { crnnFactory().also { crnn = it } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { crnnCreationFailure = error; throw error }
        catch (error: LinkageError) { crnnCreationFailure = error; throw error }
    }

    override suspend fun recognize(regions: List<Region>): List<RecognizedLine> = withContext(Dispatchers.Default) {
        check(!closed)
        val results = mutableListOf<RecognizedLine>()
        var failure: Throwable? = null
        var lineId = 0
        for (region in regions) {
            coroutineContext.ensureActive()
            if (region.type != RegionType.TEXT || region.pixelArea <= 0 || region.boundingBox.isEmpty) {
                diagnostics?.regionDecision(region, "invalid_or_non_text")
                continue
            }
            val rejectedRegion = PaddlePrimaryInputGuard.rejectionReason(region.croppedBitmap)
            diagnostics?.regionDecision(region, rejectedRegion ?: "accepted")
            if (rejectedRegion != null) { decision(region.id, "skip", rejectedRegion); continue }
            val lines = PerformanceDiagnostics.measure(PerfStage.SEGMENTATION) { PhysicalLineSegmenter().splitIntoPhysicalLines(region) }
            PerformanceDiagnostics.metric(PerfMetric.PHYSICAL_LINES, lines.size.toLong(), add = true)
            if (lines.isEmpty()) diagnostics?.regionDecision(region, "no_physical_lines")
            try {
                for (line in lines) {
                    coroutineContext.ensureActive()
                    val id = ++lineId
                    val rejected = PaddlePrimaryInputGuard.rejectionReason(line.bitmap)
                    diagnostics?.line(id, region.id, line, rejected)
                    if (rejected != null) { decision(region.id, "skip", rejected); continue }
                    var fallbackReason = "unusable_output"
                    val paddleStarted = System.nanoTime()
                    var paddleFailure: Throwable? = null
                    val prediction = try {
                        val result = paddle().recognizeLine(line.bitmap)
                        diagnostics?.refinement(id, line.bitmap, line.boundingBox, result.cropRefinement, result.refinementNs)
                        result.prediction
                    }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { paddleFailure = error; fallbackReason = error.javaClass.simpleName; Log.w(TAG, "Paddle line failed; using CRNN region=${region.id}", error); null }
                    catch (error: LinkageError) { paddleFailure = error; fallbackReason = error.javaClass.simpleName; Log.w(TAG, "Paddle runtime unavailable; using CRNN", error); null }
                    diagnostics?.attempt(id, "paddle", prediction?.text, System.nanoTime() - paddleStarted, paddleFailure)
                    val text = prediction?.text?.trim()?.replace(Regex("\\s+"), " ")
                    if (text != null && usable(text)) {
                        diagnostics?.selected(id, "paddle", "usable_text", results.size, 1)
                        results.add(RecognizedLine(region.id, line.boundingBox, text, prediction.meanSelectedProbability))
                        decision(region.id, "paddle", "usable_text")
                    } else {
                        decision(region.id, "crnn_fallback", fallbackReason)
                        val outputStart = results.size
                        val crnnStarted = System.nanoTime()
                        try {
                            val crop = region.copy(boundingBox = line.boundingBox, croppedBitmap = line.bitmap)
                            val recognizer = crnn()
                            var rawFallback: String? = null
                            if (diagnostics != null && recognizer is CrnnLineRecognizer) {
                                recognizer.researchRawPrediction = { rawFallback = it }
                            }
                            val fallback = try { recognizer.recognize(listOf(crop)) }
                            finally { (recognizer as? CrnnLineRecognizer)?.researchRawPrediction = null }
                            diagnostics?.attempt(id, "crnn", rawFallback ?: fallback.joinToString("\n") { it.text }, System.nanoTime() - crnnStarted)
                            results.addAll(fallback)
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { diagnostics?.attempt(id, "crnn", null, System.nanoTime() - crnnStarted, error); failure = failure ?: error; Log.w(TAG, "CRNN fallback failed region=${region.id}", error) }
                        catch (error: LinkageError) { diagnostics?.attempt(id, "crnn", null, System.nanoTime() - crnnStarted, error); failure = failure ?: error; Log.w(TAG, "CRNN fallback runtime unavailable", error) }
                        diagnostics?.selected(id, "crnn_fallback", fallbackReason, outputStart, results.size - outputStart)
                    }
                }
            } finally {
                lines.map { it.bitmap }.distinct().forEach {
                    if (it !== region.croppedBitmap && !it.isRecycled) it.recycle()
                }
            }
        }
        if (results.isEmpty() && failure != null) throw IllegalStateException("Single-scan recognition failed", failure)
        results
    }

    override fun close() {
        if (closed) return
        closed = true
        try { paddle?.close() } finally { crnn?.close() }
    }
    private fun decision(regionId: Int, engine: String, reason: String) {
        Log.i(TAG, "region=$regionId engine=$engine reason=$reason")
    }
    companion object {
        const val TAG = "PADDLE_PRIMARY"
        fun usable(text: String): Boolean = text.isNotBlank() &&
            text.none { it.isISOControl() || it == '\uFFFD' } &&
            text.any { it.isLetterOrDigit() || it in "=+-*/<>∑∫√≤≥±×÷" }
    }
}
