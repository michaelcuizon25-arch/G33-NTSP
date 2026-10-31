# Stage 2.5: performance and pipeline consistency

Implemented locally on `feature/ocr-accuracy-improvements`, HEAD
`036ce9ef17c6b377344fc3651e4bce0ecdd89703`. The pre-existing, uncommitted performance
instrumentation was backed up and preserved. No commit, push or merge was made.
No trained weights, vocabulary, tensor normalization, CTC decoding, engine
selection, rule-engine behavior or dependency versions were changed.

## Diagnostic exports

`ENABLE_CRNN_DIAGNOSTIC_EXPORTS` is a new **false-by-default** build option:

```sh
gradle assembleDebug -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=true
```

Normal scans do not create a CRNN diagnostic session directory, hash assets, save
PNGs/tensors, construct the detailed record payloads or export JSON/HTML. Lightweight
`NOTE2SNAP_PERF` remains independent. Its tag controls are unchanged:

```sh
adb shell setprop log.tag.NOTE2SNAP_PERF DEBUG
adb logcat -v threadtime NOTE2SNAP_PERF:I '*:S'
# Disable new timings for comparison runs:
adb shell setprop log.tag.NOTE2SNAP_PERF WARN
```

Research mode retains per-line pending/final JSON, exact crop/input/output tensors,
source images, correction metadata and note records. Global `lines.jsonl` and
`review.html` are flushed when recognition finishes or the recognizer closes;
unchanged review records are not rewritten on every update. Individual record
updates still reflect genuine state transitions. Asset hashes are cached per
session recorder instead of rereading weights for repeated model metadata. Partial
records can be flushed on close after cancellation/failure. Existing diagnostic
failure isolation is preserved. Research files and old recognition logs can contain
note content; use the performance tag alone for content-free measurements.

Tests that inspect research files now explicitly opt in. Their existing assertions
were retained; unrelated failing tests were not rewritten or disabled.

## Camera processing

CameraX still captures the same JPEG. On its main-thread callback the app snapshots
only primitive preview/guide dimensions and offsets. `GuideCropGeometry` reproduces
the previous FILL_CENTER arithmetic and clamping. `CameraPagePreparer` performs the
unchanged full-resolution EXIF decode/rotation, crop, JPEG quality-95 encoding and
existing sampled image-quality gate on `Dispatchers.IO`.

The exact prepared path is validated once before handoff. Camera single calls
`processImageUri(..., validateQuality=false)` only after that file passed the gate;
camera batch enqueues it after the same gate. Other callers retain their prior
quality policies. Gallery and tutorial behavior is not made more permissive.

Preparation uses the captured view lifecycle owner. Cancellation/view replacement
cannot enqueue a late page into a new view. Capture/queue processing guards remain
active until preparation completes; abandoned camera cache files are cleaned up,
and owned crop/orientation bitmaps are released in finally blocks. Crop worker code
no longer reads Views. Camera callbacks still deliver UI updates on main.

## Image loading and memory

`PdfViewerActivity` now requests local source files asynchronously through the
existing Coil 2.6 dependency, at display/screen dimensions. It uses Coil's managed
image request/cache lifetime, cancels obsolete requests and records asynchronous
`DISPLAY_IMAGE_LOAD` timing. Recognition and export still reference the untouched
source file; display sampling does not feed OCR or export bitmaps.

Recognition/camera decoding was deliberately **not** downsampled earlier: that
would change JPEG/interpolation/stroke pixels and needs a larger accuracy study.
The existing single-scan 1800px bound is unchanged. Diagnostic export removal avoids
per-line tensor copies, preview bitmaps and PNG compression during normal scans.
Batch enhancement uses the same bounded Stage 1 image preparation without creating
an unused adaptive binary mask. Owned batch bitmaps are released when the ML Kit
Task completes, including if its awaiting coroutine has already been cancelled.
They are not recycled while ML Kit may still read them. Borrowed bitmap inputs are
not recycled. Batch pages remain sequential, keeping one ML Kit client per batch.

No device heap/native/PSS measurements are claimed. The code removes known copies
and full-size display decoding; Android allocation/GC and memory-pressure behavior
still require device profiling.

## Input methods and feature consistency

Both existing feature flags remain false by default and independent:

- `ENABLE_OPENCV_ENHANCEMENT`
- `ENABLE_OFFLINE_TEXT_CORRECTION`

| Workflow | Engine | Optional OpenCV | Optional correction | Note assembly |
| --- | --- | --- | --- | --- |
| Camera single | CRNN | Shared Stage 1 preparation | Stage 2 after CTC | Existing rule structurer |
| Gallery single | CRNN | Same | Same | Same |
| Tutorial | CRNN | Same | Same | Same |
| Camera batch | ML Kit | New shared preparation adapter | New Stage 2 hook after ML Kit | Existing batch logic |
| Gallery batch | ML Kit | Same adapter | Same hook | Existing batch logic |

When batch flags are off, the adapter returns the **same original InputImage** and
unchanged recognized text. The existing ML Kit file-input orientation handling,
readability checks, review checks, page ordering and paragraph/diagram HTML assembly
remain in place. No batch is routed to CRNN or `PipelineTextStructurer`.

When enhancement is enabled, `WhiteboardImagePreparation` runs the exact existing
resize/enhancement code once per batch page. Both single/batch use that implementation;
its extracted preparation and downscale code was checked byte-for-byte against the
investigation baseline. ML Kit's file factory provides an upright bitmap with zero
rotation. The adapter uses it, bounds/enhances it and avoids a second file decode.
If enhancement fails, the original InputImage and scale are retained.
`BatchImageGeometry.toOriginal` supplies floor/ceil/clamped mapping from recognition
boxes to the upright source coordinate space. The raw ML Kit result remains
unchanged, with its native coordinate space available alongside that mapping.

Diagrams still use the original source path and original batch diagram threshold,
CCL and export. Enhancement is not applied twice to them. The diagram processing
implementation is unchanged. Existing single-scan returned visual regions remain
preserved; this stage does not redesign how single-scan crops appear in notes.

Batch correction runs once after successful raw recognition and review checks,
before the existing text-plus-diagram assembly. It uses the same cached dictionary
and Stage 2 conservative policy. Correction errors preserve raw text and cancellation
propagates. Empty/unreadable OCR is not invented into a page. Original page OCR is
retained separately and forwarded as `ORIGINAL_OCR_PAGES` in the review Activity
intent (single scans also supply it). This is current-review data, not a new Room
history schema; original image files are retained as before. Durable raw-text
history beyond the current intent would need a separately designed storage change.

No cloud API, runtime model/vocabulary download, Internet requirement, duplicate OCR
request, cross-model confidence comparison or new library was introduced. Existing
ML Kit fallback semantics are retained.

## Regression and validation

Investigation baseline: 122 tests: **107 passed, 14 failed, one skipped**.
Stage 2.5: 130 tests: **115 passed, the same 14 failed, one skipped**.
All eight additional tests pass, with no new or resolved baseline failures.

Coverage includes:

- Export-off creates no files and does not traverse a supplied diagnostic tensor.
- Research export remains usable; repeated clean flush does not rewrite the review.
- Export ON/OFF produces identical CRNN input bytes, raw/corrected text and structured
  notes in the pipeline regression fixture.
- Existing visual-region and Stage 1/2 regression tests remain passing.
- Flags-off batch preserves InputImage identity, raw text and coordinate identity.
- Enabled batch calls enhancement once, preserves source pixels and maps boxes back.
- Native enhancement failure retains the original ML Kit input.
- Input bitmap cleanup waits for ML Kit Task completion, not coroutine cancellation.
- Known guide/crop rectangles and edge clamping match previous geometry.
- Preparation runs off the calling/UI thread and validates the exact prepared path once.

The new ML Kit bitmap-input tests initialize its required common test context. This
is test setup, not a change to the pre-existing ML Kit test or production engine.
The pre-existing ML Kit context failure remains in the baseline.

Debug, unsigned release and instrumentation APK tasks pass with cached offline
dependencies. The combined `--continue` command returns failure because the same
14 known unit assertions still fail. No device/emulator was available to execute
instrumentation tests or real camera/gallery/Coil/ML Kit lifecycle interactions.
Feature combinations with actual native OpenCV/ML Kit need device testing.

## Performance observations

### Host pipeline (native graphics, scripted interpreter)

The paired test uses the same existing photo, actual CCL/segmentation/input
preprocessing/CTC/correction/structuring and a scripted interpreter. Latest results:

| Mode | Total | Detailed diagnostic I/O |
| --- | ---: | ---: |
| Research exports ON | 2,110.33ms | 1,242.34ms |
| Normal scanning, exports OFF | 688.80ms | 0ms / no export spans |

The prior investigation host snapshot was about 2,894.67ms total / 2,221.56ms detailed
I/O. These are different host runs, not an Android speedup guarantee. Even the paired
ON-then-OFF test has warmup/cache/order effects. The firm result is zero detailed file
operations in normal mode and equal recognition inputs/outputs.

Other latest paired host timings (inclusive; do not sum nested rows):

| Stage | Exports ON | Exports OFF |
| --- | ---: | ---: |
| Preprocessing | 301.40ms | 55.07ms |
| CCL | 96.94ms | 33.77ms |
| Segmentation | 130.75ms | 146.20ms |
| CRNN input preparation | 61.87ms | 95.65ms |
| CTC decode | 146.11ms | 267.14ms |
| Correction | 0.45ms | 0.32ms |
| Structuring | 2.73ms | 1.11ms |

Variation in untouched stages illustrates why these are profiling observations,
not claims that CCL or preprocessing were optimized. The scripted invocation cost
is not a real CRNN inference benchmark. The test writes its current results to
`app/build/performance-stage25.json`.

### Unchanged model, actual host CPU/Flex

The same 20 inputs, three warmups, TensorFlow CPU/Flex 2.16.1 and TFLite configured
for one thread were measured before/after:

| Measurement | Before | After |
| --- | ---: | ---: |
| Initialization/allocation | 9.07ms | 9.38ms |
| Median native invocation | 64.37ms | 64.62ms |
| p95 native invocation | 65.37ms | 69.18ms |

No native inference improvement is claimed; weights/runtime/CTC are unchanged and
these differences are host noise/concurrent-work effects. Android native inference,
ML Kit request time, batch elapsed time, main-thread/frame time, asynchronous display
latency and real peak memory require the existing timing tag and device profiling.

## Accuracy and remaining limits

No recognition sampling, normalization, tensor format, model, CTC or structuring
change was made. Default CRNN inputs/text/notes are identical in the regression
comparison; no new CER/WER gain is claimed. Existing captured title predictions
and Stage 2 correction evaluation remain applicable without changing their scores.
Optional batch enhancement changes ML Kit pixels by design, so fresh labeled
batch CER/WER cannot be established without an Android ML Kit/device run. Both
features remain disabled until that evaluation supports enabling them.

Exercise camera/gallery single and batch plus tutorial, rotated/mirrored inputs,
faint strokes, diagrams, cancellation/view recreation, rapid mode changes and
large source files. Use all four enhancement/correction combinations and exports
ON/OFF, recording p50/p95, first-use versus warm time, input dimensions/line counts,
heap/native/PSS and frame stalls. Verify fresh-install airplane-mode recognition.

Full-resolution recognition and camera EXIF decoding remain intentionally intact.
Tutorial resource preparation and other viewer formatting can still use main;
interpreter reuse, CTC changes, broad batch concurrency and further buffer work
were not introduced. Current-review raw OCR increases intent text payload; no Room
migration or new durable raw-text storage was added.

## Files

New production components: `activities/CameraPagePreparer.kt`,
`preprocessing/GuideCropGeometry.kt`, `preprocessing/WhiteboardImagePreparation.kt`,
`recognition/BatchRecognitionFeatures.kt`.

Stage 2.5 modifies `app/build.gradle.kts`, `ScanFragment.kt`,
`PdfViewerActivity.kt`, `WhiteboardPreprocessor.kt`, `CrnnDiagnostics.kt`,
`CrnnLineRecognizer.kt` and the preserved `PerformanceDiagnostics.kt` stage labels.
Research-file tests explicitly opt in; `PerformancePipelineParityTest` adds the
paired export benchmark. New `Stage25FeaturesTest` and `CameraPagePreparerTest`
cover the new adapters and worker/geometry contracts. Earlier instrumentation
modifications remain uncommitted and preserved alongside these changes.

There is no PaddleOCR or LiteRT integration. Commit/push requires explicit approval.
