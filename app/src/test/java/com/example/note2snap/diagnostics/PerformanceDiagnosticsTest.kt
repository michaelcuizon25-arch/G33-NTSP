package com.example.note2snap.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PerformanceDiagnosticsTest {
    @Test fun aggregateAndPerLineEventsUseMonotonicIntervalsAndFixedLabels() {
        val time = AtomicLong(100)
        val events = mutableListOf<PerfEvent>()
        val session = PerformanceSession(PerfFlow.SINGLE_CAMERA, true, time::get, { true }, events::add)
        val first = session.begin(PerfStage.CRNN_INFERENCE)
        time.addAndGet(7); first.finish()
        val second = session.begin(PerfStage.CRNN_INFERENCE)
        time.addAndGet(13); second.finish(false)
        session.metric(PerfMetric.PHYSICAL_LINES, 2)
        session.close()
        val lines = events.filter { it.kind == PerfEventKind.LINE_INFERENCE }
        assertEquals(listOf(7L, 13L), lines.map { it.elapsedNs })
        assertEquals(listOf(1L, 2L), lines.map { it.count })
        val total = events.single { it.kind == PerfEventKind.STAGE }
        assertEquals(20, total.elapsedNs); assertEquals(13, total.maxNs)
        assertEquals(2, total.count); assertEquals(1, total.failures)
        assertEquals(2, total.mainThreadStarts)
        assertEquals(2L, events.last().metrics[PerfMetric.PHYSICAL_LINES])
    }

    @Test fun disabledSessionsDoNotReadClockOrEmitEvents() {
        val session = PerformanceSession(PerfFlow.PIPELINE, false, { error("clock called") },
            { error("thread queried") }, { error("event emitted") })
        session.begin(PerfStage.CCL).finish()
        session.metric(PerfMetric.TEXT_REGIONS, 20)
        session.close()
    }

    @Test fun loggerFailureCannotReplaceRecognitionResult() {
        val session = PerformanceSession(PerfFlow.PIPELINE, true, { 10 }, { false }, { error("sink unavailable") })
        val expected = Any()
        val result = PerformanceDiagnostics.onThread(session) {
            PerformanceDiagnostics.measure(PerfStage.CRNN_INFERENCE) { expected }
        }
        session.close()
        assertSame(expected, result)
        assertNull(PerformanceDiagnostics.current())
    }

    @Test fun errorsAreRethrownUnchangedAndRecordedWithoutErrorMessages() {
        val events = mutableListOf<PerfEvent>()
        val session = PerformanceSession(PerfFlow.PIPELINE, true, { 10 }, { false }, events::add)
        val failure = IllegalStateException("PRIVATE_NOTE_AND_PATH")
        var caught: IllegalStateException? = null
        try {
            PerformanceDiagnostics.onThread(session) {
                PerformanceDiagnostics.measure(PerfStage.CRNN_INFERENCE) { throw failure }
            }
        } catch (actual: IllegalStateException) { caught = actual }
        assertSame(failure, caught)
        session.close()
        assertTrue(events.none { it.toString().contains("PRIVATE_NOTE_AND_PATH") })
        assertEquals(1L, events.first().failures)
        assertNull(PerformanceDiagnostics.current())
    }

    @Test fun contextSurvivesDispatcherChangesAndNestedPipelineScopes() = runBlocking {
        val time = AtomicLong(1)
        val events = mutableListOf<PerfEvent>()
        val session = PerformanceSession(PerfFlow.GALLERY_BATCH, true, time::get, { false }, events::add)
        withContext(PerformanceDiagnostics.context(session)) {
            PerformanceDiagnostics.withSession(PerfFlow.PIPELINE) {
                PerformanceDiagnostics.measureSuspending(PerfStage.MLKIT_RECOGNITION) {
                    withContext(Dispatchers.Default) {
                        assertSame(session, PerformanceDiagnostics.current())
                        time.addAndGet(50)
                    }
                }
            }
        }
        assertNull(PerformanceDiagnostics.current())
        session.close()
        assertEquals(1, events.count { it.kind == PerfEventKind.TOTAL })
        assertTrue(events.all { it.id == session.id && it.flow == PerfFlow.GALLERY_BATCH })
        assertEquals(50, events.single { it.kind == PerfEventKind.STAGE }.elapsedNs)
    }

    @Test fun cancellationClosesSpanAndKeepsTheOriginalException() = runBlocking {
        val events = mutableListOf<PerfEvent>()
        val session = PerformanceSession(PerfFlow.PIPELINE, true, { 10 }, { false }, events::add)
        val failure = CancellationException("private reason")
        var caught: CancellationException? = null
        try {
            PerformanceDiagnostics.onThread(session) {
                PerformanceDiagnostics.measureSuspending(PerfStage.RECOGNITION) { throw failure }
            }
        } catch (actual: CancellationException) { caught = actual }
        assertSame(failure, caught)
        session.close()
        assertEquals(1, events.single { it.kind == PerfEventKind.STAGE }.failures)
        assertFalse(events.toString().contains("private reason"))
    }

    @Test fun asynchronousPageSpansEmitIndividualDurationsAndCloseIdempotently() {
        val time = AtomicLong(0)
        val events = mutableListOf<PerfEvent>()
        val session = PerformanceSession(PerfFlow.CAMERA_BATCH, true, time::get, { false }, events::add)
        repeat(2) {
            val span = session.begin(PerfStage.BATCH_PAGE)
            time.addAndGet(20); span.finish(); span.finish()
        }
        session.close(); session.close()
        assertEquals(listOf(20L, 20L), events.filter { it.kind == PerfEventKind.BATCH_IMAGE }.map { it.elapsedNs })
        assertEquals(1, events.count { it.kind == PerfEventKind.TOTAL })
    }
}
