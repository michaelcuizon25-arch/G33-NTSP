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
    private val crnnFactory: () -> LineRecognizer
) : LineRecognizer {
    constructor(context: Context, mode: CrnnPreprocessingMode) : this(
        { PaddleOcrLineRecognizer(context.applicationContext) },
        {
            val runtime = TensorFlowLiteCrnnRuntime(context.applicationContext)
            try {
                CrnnLineRecognizer(context.applicationContext, mode, runtime, loadCrnnCharset(context.applicationContext),
                    physicalLineProvider = { region -> listOf(PhysicalLine(region.croppedBitmap, region.boundingBox)) })
            } catch (error: Throwable) { runtime.close(); throw error }
        }
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
        for (region in regions) {
            coroutineContext.ensureActive()
            if (region.type != RegionType.TEXT || region.pixelArea <= 0 || region.boundingBox.isEmpty) continue
            val rejectedRegion = PaddlePrimaryInputGuard.rejectionReason(region.croppedBitmap)
            if (rejectedRegion != null) { decision(region.id, "skip", rejectedRegion); continue }
            val lines = PerformanceDiagnostics.measure(PerfStage.SEGMENTATION) { PhysicalLineSegmenter().splitIntoPhysicalLines(region) }
            PerformanceDiagnostics.metric(PerfMetric.PHYSICAL_LINES, lines.size.toLong(), add = true)
            try {
                for (line in lines) {
                    coroutineContext.ensureActive()
                    val rejected = PaddlePrimaryInputGuard.rejectionReason(line.bitmap)
                    if (rejected != null) { decision(region.id, "skip", rejected); continue }
                    var fallbackReason = "unusable_output"
                    val prediction = try { paddle().recognizeLine(line.bitmap).prediction }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { fallbackReason = error.javaClass.simpleName; Log.w(TAG, "Paddle line failed; using CRNN region=${region.id}", error); null }
                    catch (error: LinkageError) { fallbackReason = error.javaClass.simpleName; Log.w(TAG, "Paddle runtime unavailable; using CRNN", error); null }
                    val text = prediction?.text?.trim()?.replace(Regex("\\s+"), " ")
                    if (text != null && usable(text)) {
                        results.add(RecognizedLine(region.id, line.boundingBox, text, prediction.meanSelectedProbability))
                        decision(region.id, "paddle", "usable_text")
                    } else {
                        decision(region.id, "crnn_fallback", fallbackReason)
                        try {
                            val crop = region.copy(boundingBox = line.boundingBox, croppedBitmap = line.bitmap)
                            results.addAll(crnn().recognize(listOf(crop)))
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { failure = failure ?: error; Log.w(TAG, "CRNN fallback failed region=${region.id}", error) }
                        catch (error: LinkageError) { failure = failure ?: error; Log.w(TAG, "CRNN fallback runtime unavailable", error) }
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
