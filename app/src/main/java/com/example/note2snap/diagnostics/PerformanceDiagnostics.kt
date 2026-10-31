package com.example.note2snap.diagnostics

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.note2snap.BuildConfig
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/** Fixed labels only: the timing API deliberately accepts no note text or file paths. */
internal enum class PerfFlow { CAMERA_CAPTURE, CAMERA_PREPARATION, SINGLE_CAMERA, SINGLE_GALLERY, TUTORIAL, TUTORIAL_PREPARATION, CAMERA_BATCH, GALLERY_BATCH, PIPELINE, COMPONENT, NAVIGATION, VIEWER_RENDER }
internal enum class PerfStage {
    CAPTURE_WAIT, URI_COPY, IMAGE_DECODE, IMAGE_ENCODE, DISPLAY_IMAGE_LOAD, EXIF_DECODE_ROTATE, GUIDE_CROP, QUALITY_CHECK,
    IMAGE_RESIZE, PREPROCESSING, OPENCV, ADAPTIVE_THRESHOLD, CCL, SEGMENTATION,
    PIPELINE_INITIALIZATION, MODEL_INITIALIZATION, CORRECTOR_INITIALIZATION,
    RECOGNITION, CRNN_RECOGNITION, CRNN_PREPROCESSING, CRNN_INFERENCE, CRNN_OUTPUT_READ,
    CTC_DECODE, CRNN_CONFIDENCE, MLKIT_INITIALIZATION, MLKIT_RECOGNITION,
    OFFLINE_CORRECTION, STRUCTURING, DIAGNOSTIC_IO, DIAGRAM_PROCESSING,
    DIAGRAM_THRESHOLD, DIAGRAM_EXPORT, BATCH_PAGE, BATCH_ASSEMBLY, HISTORY_ACCESS, UI_QUEUE_WAIT, VIEWER_RENDER
}
internal enum class PerfMetric { SOURCE_WIDTH, SOURCE_HEIGHT, RECOGNITION_WIDTH, RECOGNITION_HEIGHT, TEXT_REGIONS, VISUAL_REGIONS, PHYSICAL_LINES, OPENCV_REQUESTED, OPENCV_APPLIED, CORRECTION_ENABLED, BATCH_INPUT_PAGES, BATCH_ACCEPTED_PAGES }
internal enum class PerfEventKind { LINE_INFERENCE, BATCH_IMAGE, MLKIT_REQUEST, STAGE, TOTAL }
internal data class PerfEvent(
    val kind: PerfEventKind, val id: Long, val flow: PerfFlow, val stage: PerfStage? = null,
    val count: Long = 0, val elapsedNs: Long = 0, val maxNs: Long = 0,
    val failures: Long = 0, val mainThreadStarts: Long = 0,
    val metrics: Map<PerfMetric, Long> = emptyMap(), val startedNs: Long = 0
)

/** A bounded aggregate per stage; per-line inference is streamed, never retained. */
internal class PerformanceSession(
    val flow: PerfFlow,
    private val enabled: Boolean,
    private val clock: () -> Long,
    private val onMain: () -> Boolean,
    private val sink: (PerfEvent) -> Unit
) : Closeable {
    private data class Stats(var count: Long = 0, var total: Long = 0, var max: Long = 0,
                             var failures: Long = 0, var mainStarts: Long = 0)
    val id: Long = if (enabled) ids.incrementAndGet() else 0
    private val started = if (enabled) clock() else 0
    private val stages = EnumMap<PerfStage, Stats>(PerfStage::class.java)
    private val metrics = EnumMap<PerfMetric, Long>(PerfMetric::class.java)
    private var closed = false

    fun begin(stage: PerfStage): PerformanceSpan = if (enabled)
        PerformanceSpan(this, stage, clock(), onMain()) else PerformanceSpan.NONE

    @Synchronized fun metric(key: PerfMetric, value: Long, add: Boolean = false) {
        if (enabled && !closed) metrics[key] = if (add) (metrics[key] ?: 0) + value else value
    }

    @Synchronized internal fun record(stage: PerfStage, start: Long, success: Boolean, main: Boolean) {
        if (!enabled || closed) return
        val duration = (clock() - start).coerceAtLeast(0)
        val stats = stages.getOrPut(stage) { Stats() }
        stats.count++; stats.total += duration; stats.max = maxOf(stats.max, duration)
        if (!success) stats.failures++
        if (main) stats.mainStarts++
        val kind = when (stage) {
            PerfStage.CRNN_INFERENCE -> PerfEventKind.LINE_INFERENCE
            PerfStage.BATCH_PAGE -> PerfEventKind.BATCH_IMAGE
            PerfStage.MLKIT_RECOGNITION -> PerfEventKind.MLKIT_REQUEST
            else -> null
        }
        if (kind != null) emit(PerfEvent(kind, id, flow, stage,
            count = stats.count, elapsedNs = duration, failures = if (success) 0 else 1, mainThreadStarts = if (main) 1 else 0))
    }

    @Synchronized override fun close() {
        if (!enabled || closed) return
        closed = true
        val duration = (clock() - started).coerceAtLeast(0)
        stages.forEach { (stage, stats) -> emit(PerfEvent(PerfEventKind.STAGE, id, flow, stage,
            stats.count, stats.total, stats.max, stats.failures, stats.mainStarts)) }
        emit(PerfEvent(PerfEventKind.TOTAL, id, flow, elapsedNs = duration, metrics = metrics.toMap(), startedNs = started))
    }

    private fun emit(event: PerfEvent) {
        // Observational: unavailable log sinks must not change recognition or errors.
        try { sink(event) } catch (_: Exception) { }
    }
    private companion object { val ids = AtomicLong() }
}

internal class PerformanceSpan(
    private val session: PerformanceSession?, private val stage: PerfStage?,
    private val started: Long, private val main: Boolean,
    private val closeSession: Boolean = false
) {
    private var finished = false
    @Synchronized fun finish(success: Boolean = true) {
        if (finished) return
        finished = true
        if (session != null && stage != null) session.record(stage, started, success, main)
        if (closeSession) session?.close()
    }
    companion object { val NONE = PerformanceSpan(null, null, 0, false) }
}

internal object PerformanceDiagnostics {
    const val TAG = "NOTE2SNAP_PERF"
    private val local = ThreadLocal<PerformanceSession?>()
    // INFO default enables debug-build tracing; WARN disables it for A/B checks.
    // Release builds require an explicit DEBUG log-tag override.
    private fun enabled() = Log.isLoggable(TAG, Log.DEBUG) ||
        (BuildConfig.DEBUG && Log.isLoggable(TAG, Log.INFO))

    fun session(flow: PerfFlow): PerformanceSession = PerformanceSession(flow, enabled(),
        { SystemClock.elapsedRealtimeNanos() }, { Looper.myLooper() == Looper.getMainLooper() }, ::log)
    fun context(session: PerformanceSession) = local.asContextElement(session)
    fun current() = local.get()
    fun metric(key: PerfMetric, value: Long, add: Boolean = false) { local.get()?.metric(key, value, add) }

    /** Detached spans are useful for asynchronous camera callbacks; they retain no image. */
    fun detached(flow: PerfFlow, stage: PerfStage): PerformanceSpan {
        if (!enabled()) return PerformanceSpan.NONE
        val session = session(flow)
        return PerformanceSpan(session, stage, SystemClock.elapsedRealtimeNanos(),
            Looper.myLooper() == Looper.getMainLooper(), closeSession = true)
    }

    inline fun <T> measure(stage: PerfStage, action: () -> T): T {
        val span = current()?.begin(stage) ?: return action()
        var success = false
        try { val value = action(); success = true; return value }
        finally { span.finish(success) }
    }

    suspend fun <T> measureSuspending(stage: PerfStage, action: suspend () -> T): T {
        val span = current()?.begin(stage) ?: return action()
        var success = false
        try { val value = action(); success = true; return value }
        finally { span.finish(success) }
    }

    suspend fun <T> withSession(flow: PerfFlow, action: suspend () -> T): T {
        if (current() != null) return action()
        val session = session(flow)
        return try { withContext(context(session)) { action() } } finally { session.close() }
    }

    /** For non-suspending camera preparation on the callback's original thread. */
    inline fun <T> onThread(session: PerformanceSession, action: () -> T): T {
        val previous = swap(session)
        try { return action() } finally { swap(previous) }
    }
    fun swap(session: PerformanceSession?): PerformanceSession? = local.get().also { local.set(session) }

    private fun log(event: PerfEvent) {
        val json = JSONObject().put("kind", event.kind.name).put("session", event.id).put("flow", event.flow.name)
            .put("elapsedNs", event.elapsedNs).put("startedNs", event.startedNs)
        event.stage?.let { json.put("stage", it.name).put("count", event.count).put("maxNs", event.maxNs)
            .put("failures", event.failures).put("mainThreadStarts", event.mainThreadStarts) }
        if (event.metrics.isNotEmpty()) {
            val values = JSONObject()
            event.metrics.forEach { (key, value) -> values.put(key.name, value) }
            json.put("metrics", values)
        }
        Log.i(TAG, json.toString())
    }
}
