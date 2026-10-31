# Recognition performance and input-path consistency investigation

## Scope and repository state

Investigated on `feature/ocr-accuracy-improvements`, starting from clean Stage 2
commit `036ce9ef17c6b377344fc3651e4bce0ecdd89703`, which includes Stage 1 commit
`7f003bb`. Only timing diagnostics, regression tests and this report were added.
No commit, push, merge, model change, dependency change, feature-default change,
recognition optimization or input-path unification was performed.

Both `ENABLE_OPENCV_ENHANCEMENT` and `ENABLE_OFFLINE_TEXT_CORRECTION` remain false
by default. Consequently they cannot explain default-build latency as enabled
processing stages. Even explicitly enabling them does not activate them in the
current direct ML Kit batch paths.

All application file paths below are under
`app/src/main/java/com/example/note2snap/`, unless stated otherwise.

## Actual paths and consistency matrix

| Input / entry point | Operations before recognition | Recognition and output | OpenCV | Offline correction | Rule structurer |
| --- | --- | --- | --- | --- | --- |
| Camera single: `ScanFragment.takePhoto` -> `processImageUri(CAMERA)` | CameraX saves JPEG; EXIF upright decode; visible-guide crop and JPEG re-encode; sampled quality check on callback/main thread; second quality check on IO; full decode; bounded preprocessing | CCL text regions -> physical lines -> CRNN/Flex -> CTC -> optional correction -> structured note | Supported; flag read | Supported; flag read | Yes |
| Gallery single: `handleSelectedGalleryImages`, one URI -> `processImageUri(GALLERY)` | URI copied to cache on IO; guide crop disabled; quality gate explicitly disabled; full decode; same bounded preprocessing | Same single-scan recognition pipeline as camera | Supported; flag read | Supported; flag read | Yes |
| Tutorial: `runSampleWhiteboardDemo` -> `processImageUri(TUTORIAL)` | Resource decode and PNG write on caller/main thread; known-good asset skips quality gate; same single-scan decode/preprocessing | Same single-scan pipeline | Supported; flag read | Supported; flag read | Yes |
| Camera batch: `processCapturedCameraBatch` | Every capture already has crop/EXIF/JPEG/quality preparation; each saved image loaded into ML Kit | One full-image ML Kit request per page; raw text/review checks; separate sampled diagram decode/global threshold/CCL/export; concatenate pages | Bypassed; flag not consulted | Bypassed; flag not consulted | No |
| Gallery batch: `handleSelectedGalleryImages`, multiple URIs -> `processBatchImages` | Copy each URI; load full image into ML Kit; no guide crop or quality gate | Same direct batch ML Kit/text/review/diagram/page assembly as camera batch | Bypassed; flag not consulted | Bypassed; flag not consulted | No |
| `MlKitLineRecognizer` used as an injected engine | Adapter accepts existing CCL text-region crops; ML Kit supplies lines/fragments | Local boxes translated to global coordinates; nearby fragments normalized; region-text fallback retained | Only through caller's preprocessor | Only through caller's configured pipeline corrector | Only through caller's pipeline |

`WhiteboardRecognitionPipeline` constructs `WhiteboardPreprocessor`, CCL and
`PipelineTextStructurer`. Its real-app Context/factory constructor selects CRNN
and loads the corrector according to the Stage 2 flag. The primary recognizer-only
constructor intentionally defaults to no corrector; callers injecting ML Kit must
also supply/configure one if correction is desired. The ML Kit adapter itself
contains no feature-flag handling. The real app's batch paths do not instantiate
this adapter or the shared pipeline.

Single-scan preprocessing downsizes to a maximum side of 1800; optional OpenCV
runs before the existing adaptive threshold. The binary image and recognition
image remain aligned. The CRNN then splits physical lines and invokes the fixed
FLOAT32 `[1,512,32]` model once per line; native output is `[1,128,80]`. It keeps the
existing eight-beam CTC decoder, then optional correction precedes structuring.

Camera and gallery single scans therefore share the recognition backend but do
not necessarily supply equivalent images. Camera crops/re-encodes/quality-checks
its input; gallery preserves the selected image and skips the quality gate.
Camera's explicit EXIF-upright path is also absent from the gallery single
`BitmapFactory.decodeFile` path. ML Kit file input has its own loading/orientation
handling. Rotated gallery photos require device regression coverage before any
unification; orientation behavior was not changed here.

The batch UI's `STRUCTURING_NOTES` progress state does not mean the rule engine
runs. Batch code joins ML Kit text and diagram HTML. Later viewer sanitation and
reviewer styling are different from `PipelineTextStructurer`/`WhiteboardRuleEngine`.

### Visual-content distinction

The single pipeline returns `diagramRegions`, but `processImageUri` retains only
the structured note and recognized lines in its `Pair`. It does not append the
CCL visual crops to note HTML. The viewer still shows the original source image.
Batch processing explicitly exports up to three diagram PNGs per accepted page
and embeds them in its content. This is an existing difference, preserved here.
The new parity test verifies that instrumentation leaves the returned visual
regions and their pixels unchanged; it does not claim single/batch diagram UX parity.

### Fallback and offline behavior

There is no automatic single-scan CRNN-to-ML Kit fallback. CRNN failures and partial
results retain their previous handling. `MlKitLineRecognizer` retains its fallback
from missing individual line objects to nonblank `result.text`. Batch scans call
ML Kit directly, not as a retry after CRNN. No double CRNN+ML Kit OCR was found for
a normal scan. The live camera analyzer is a quality analyzer, not another OCR engine.

No new network calls, services, runtime downloads or dependencies were added.
Existing model, ML Kit, OpenCV and correction resources/selection remain unchanged.
Fresh-install airplane-mode validation still requires a device, particularly to
verify the existing bundled/Play Services ML Kit dependency combination.

## Confirmed work versus suspected latency causes

| Finding | Confirmed evidence | What remains unmeasured on Android |
| --- | --- | --- |
| Per-line inference | One `runtime.run(input)` per physical line | Real phone per-line distributions and proportion of page time |
| Interpreter initialization | New CRNN/pipeline per single scan, closed after processing; reused across lines of that scan | Cold native/Flex load versus warm construction cost |
| Full-resolution decode | Single scan decodes before 1800px bound; camera crop/EXIF path also starts with a full decode | Decoder latency, peak heap and GC on representative photos |
| Duplicate quality work | Camera validates prepared file in callback and again in single scan | Time saved by avoiding the second already-equivalent check |
| Main-thread work | CameraX callback uses main executor for crop/re-encode/quality; tutorial preparation and viewer source decode are synchronous | Frame stalls, UI queue wait and perceived latency |
| Large diagnostics | CRNN writes source/recognition/binary/crops, 16,384-float inputs and 10,240-float outputs; updates preview/review files | Device storage/PNG compression costs in release/debug builds |
| Repeated diagnostic rewrites | `persist` rewrites all `lines.jsonl` and review HTML on updates; cumulative serialized work grows with line count | Storage sensitivity and practical scaling |
| Image/array allocations | Per-photo integral/gray/binary arrays, CCL arrays/crops, per-line bitmaps/buffers, logits arrays and decoder state | GC pauses, retained memory and which allocations dominate |
| CTC allocation/CPU | Eight beams, 79 nonblank classes, 128 timesteps; lists/maps/sorts per timestep | Real-logit latency and allocator contribution on phones |
| Flex CPU involvement | Select TF Ops dependency and model; host CPU execution uses Flex | Operator-level cost; no claim that Flex alone causes the slowdown |
| Batch serialization | One ML Kit client reused for a batch; pages processed sequentially | How much time is decode/copy versus ML Kit/diagram extraction |
| Separate batch diagram pass | Second sampled decode (maximum side 1200), separate global threshold/CCL, PNG export | Whether sharing image preparation is worth its compatibility risks |
| Live quality work | Analyzer remains bound and checks every 250ms; busy state suppresses hints but is not an analyzer work gate | CPU contention during camera OCR |
| Optional stages | OpenCV/correction default off; batch bypasses them | Enabled OpenCV cost and Android correction cold/warm costs |
| Viewer decode | `PdfViewerActivity.renderContent` decodes original source on main thread on each invocation | Display size sampling, repeated renders, first-frame cost |

The integral-image threshold, CCL and CRNN input loops are CPU/allocation costs,
not proof of a particular device bottleneck. Similarly, a one-interpreter-per-image
policy is confirmed, but the interpreter is not rebuilt per line. Batch already
reuses its ML Kit client. These distinctions prevent prioritizing the wrong fix.

## Implemented timing diagnostics

New `diagnostics/PerformanceDiagnostics.kt` uses monotonic
`SystemClock.elapsedRealtimeNanos()` and coroutine-local session propagation across
IO/Default/Main dispatchers. It retains bounded count/total/max/failure/main-start
aggregates per fixed stage label. Individual CRNN invocation and ML Kit request
rows are streamed, as are batch-page durations. No line tensor/image copies or
extra OCR invocations are introduced by timing.

`NOTE2SNAP_PERF` emits compact JSON with fixed enums, numeric session IDs, counts,
durations, dimensions and feature state only. No note text, image, URI, file path,
exception message or predicted title is accepted by the logging API. Sink failures
are nonfatal. Exceptions and cancellation from processing propagate through the
wrappers. Timing may include suspension/queueing: a stage starting on main is not,
by itself, proof that it blocked main for its entire elapsed duration.

Coverage includes:

- Camera save wait, callback preparation, EXIF decode/rotation, guide crop,
  image encoding and quality checks.
- Gallery copy, image decode, bounded resize, optional OpenCV and existing
  adaptive threshold/noise/border processing.
- CCL, segmentation, per-line CRNN preparation, interpreter construction,
  native invocation, output collection, CTC decoding and confidence calculation.
- ML Kit client construction and recognition await/normalization time. First ML Kit
  model loading may occur within its first recognition request, not client construction.
- Corrector initialization, correction, unchanged note structuring and existing
  diagnostics I/O.
- Each batch image, accepted/input counts, text/diagram assembly, diagram decode,
  threshold, CCL and export.
- Navigation queue wait/history access and viewer source decode/content preparation.

`CRNN_INFERENCE` aggregate `elapsedNs` is the sum of native invocation intervals;
`count` is the invocation count. `LINE_INFERENCE.count` is an inference ordinal,
not a saved diagnostic crop ID. `BATCH_IMAGE` reports individual page elapsed time,
including page preparation and UI handoffs; unreadable/skipped pages still get a
duration when their loop iteration executes. Nested stage durations are inclusive:
**do not add them together**. Use session `TOTAL` for wall time and stage totals to
explain it.

Root flows separate capture wait, camera preparation, single processing, batch
processing, navigation and viewer preparation. `TOTAL.startedNs` permits monotonic
alignment within one app process. Session IDs are not carried through activity
Intents, and GPU draw/layout/first-visible-frame time is not measured. Use Perfetto
for frame-time/jank and complete shutter-to-visible-result measurements.

### Enable/disable and collect

Debug builds trace at the normal INFO tag level. Release builds require an
explicit DEBUG tag override. Selection is captured when each session starts.
Neither OCR feature flag is altered by tracing.

```sh
adb shell setprop log.tag.NOTE2SNAP_PERF DEBUG
adb logcat -v threadtime NOTE2SNAP_PERF:I '*:S'
```

Disable timing for paired runs:

```sh
adb shell setprop log.tag.NOTE2SNAP_PERF WARN
```

This controls only the new timings. Existing `NOTE2SNAP_PIPELINE` and
`CRNN_DIAGNOSTICS` logs/files can contain note content and remain unchanged. Collect
only the timing tag for a content-free performance report. Limiting/gating the
old detailed exports is a proposed optimization, not part of this change.

## Measurements available here

No Android device/emulator is attached. Values below are host observations and
must not be presented as phone performance or proof of general handwriting quality.

### Actual host CPU/Flex inference

Unchanged Epoch 3 model, TensorFlow CPU 2.16.1, TFLite interpreter configured for
one thread, three warm-ups, 20 measured line inputs from the existing captures:

| Measurement | Result |
| --- | ---: |
| Interpreter construction + allocation | 9.07ms |
| Warm invocation median | 64.37ms/line |
| Warm invocation p95 | 65.37ms/line |
| 20 invocations summed | 1,288.37ms |
| Output | All `[1,128,80]`, unchanged model hash |

TensorFlow import time, image loading, input assignment, decoding and Android JNI
are excluded. Flex can use internal runtime workers; a configured interpreter
thread count is not a claim about the whole process's thread count. This confirms
that per-line work accumulates on this host, not the phone's absolute latency or
the fraction specifically caused by Flex.

### Host pipeline parity/timing test

Robolectric native graphics, one existing photo fixture, 14 physical lines,
scripted interpreter output, actual input preprocessing/CTC/correction/structuring
and existing diagnostic export. Latest full-suite observation:

| Stage | Summed host time |
| --- | ---: |
| Pipeline/input-ready session | 2,894.67ms |
| Existing diagnostic I/O, including source PNGs and rewrites | 2,221.56ms |
| Preprocessing including bounded resize | 36.11ms |
| CCL | 67.76ms |
| Segmentation (14 calls) | 164.51ms |
| CRNN input preparation (14 calls) | 101.97ms |
| CTC decode (scripted logits; 14 calls) | 201.84ms |
| Correction (14 short lines) | 0.41ms |
| Structuring | 2.06ms |

These durations are inclusive and do not sum to the wall time. The fake invocation
cost is intentionally omitted as a model benchmark. In earlier observed runs,
session time was approximately 1.79–2.70s and diagnostic I/O 1.15–1.88s, illustrating
cache/JIT/storage/concurrent-build variation. These are not controlled A/B speedup
results. They make diagnostic export a strong profiling candidate; phone effects
still need measurement. The host report is written by the test to
`app/build/performance-host-pipeline.json` and can differ between runs.

## Ranked next changes — recommendations only

| Priority | Recommendation / files | Expected impact | Complexity / accuracy safeguards |
| --- | --- | --- | --- |
| 1 | Make detailed CRNN image/tensor/review export explicitly opt-in; avoid rewriting all records per update (`CrnnDiagnostics.kt`) | High host evidence; device gain unmeasured | Low/medium; no model change, preserve useful failure diagnostics and review workflow |
| 2 | Move camera crop/encode/quality work off main, snapshotting view geometry on main; avoid the second validation of the same prepared file (`ScanFragment.kt`) | High perceived-responsiveness potential | Medium; preserve exact crop, EXIF and rejection policy |
| 3 | Sample/cache viewer image loading to display needs (`PdfViewerActivity.kt`, existing image loaders) | High main-thread/memory potential | Low/medium; keep original source and export quality, no OCR change |
| 4 | Decode single-scan input close to required resolution instead of full-size then resize (`ScanFragment.kt`, decoder helpers) | High allocation/GC potential | Medium; validate faint strokes, orientation, line coverage and CER/WER |
| 5 | Profile then reuse temporary pixel/tensor buffers and clarify bitmap ownership (`WhiteboardPreprocessor.kt`, CRNN input/runtime, CCL) | Medium/high memory potential | Medium; byte parity, no premature recycle of aliases/visual crops |
| 6 | Reuse a serialized, lifecycle-owned CRNN interpreter across single scans (`SingleScanPipelineFactory`, runtime/lifecycle owner) | Conditional on measured initialization cost | Medium; reset per-scan diagnostics, no concurrent access or resource leaks |
| 7 | Reduce live quality-analyzer work while scan is busy | Conditional CPU contention benefit | Low/medium; restore hints/preview correctly afterward |
| 8 | Consider bounded copy/decode prefetch for batches; retain one OCR client and ordered results | Conditional I/O overlap benefit | Medium; do not launch every full-resolution page concurrently |
| 9 | Profile CTC allocations before considering changes | Conditional CPU benefit | Decoder changes are excluded; do not silently shrink beam width or change outputs |

True multi-line batch inference is **not** a drop-in option: the unchanged CRNN
requires batch size 1 and fixed width/height. Do not resize it to a larger batch,
remove Flex, change normalization or replace weights as a performance shortcut.
Parallel interpreters can increase peak memory and contention. Neither is proposed
for immediate implementation.

## Minimal consistency changes to consider separately

1. Keep CRNN single and ML Kit batch engine selection intact. Centralize per-scan
   feature options/corrector creation so camera/gallery/batch entry points consume
   the same explicit policy rather than assuming progress labels imply execution.
2. Add an optional batch image-preparation hook using Stage 1's enhancement. With
   the flag off retain the current file-input path. With it on, canonicalize
   orientation and document coordinate/scale mapping before feeding ML Kit. Reuse
   existing OpenCV; avoid generating an unused CCL mask just for enhancement.
3. Apply the same text corrector to the batch extracted text before page assembly,
   retaining raw `visionText` for existing review checks. The corrector already
   preserves paragraph separators. Do not mutate ML Kit's immutable result objects
   or compare its scores with CRNN confidence.
4. Treat batch rule-engine adoption as a separate, explicitly approved behavior
   change. A full-image ML Kit-to-`RecognizedLine` adapter can preserve geometry,
   but replacing raw batch content with structured content changes existing notes.
5. Preserve current batch diagram threshold/export behavior until a dedicated
   diagram regression set proves any shared preprocessing replacement safe. Decide
   separately whether to expose single-scan visual crops in notes.

No such consistency change was implemented in this investigation. In particular,
forcing all batches through CRNN would change both recognition and performance and
is not a minimal fix.

## Validation and outstanding device work

Baseline: 114 tests = 99 passed, 14 failed, one optional host audit skipped.
Instrumented suite: 122 tests = 107 passed, the same 14 failed, the same one skipped.
All eight new tests pass; no new failures or hidden/disabled unrelated tests.
Debug, unsigned release and instrumentation APK tasks succeed using cached offline
dependencies. The combined `--continue` command exits unsuccessfully solely because
of the known unit failures. Both original feature flags are false in final builds.

New tests verify monotonic aggregation, per-line/per-page timings, disabled clock
and sink behavior, exceptions/cancellation, coroutine context propagation, logging
privacy and idempotent close. Pipeline parity compares tracing off/on and verifies
identical input tensor bytes, decoded text, corrected text, structured notes and
nonempty visual-region pixels. Existing Stage 1/2 tests still pass. The CTC decoder,
log-softmax, log-add and confidence function bodies were additionally checked
byte-for-byte against HEAD and remain unchanged. Model/charset and rule-engine
sources remain unchanged.

Before optimizing, collect cold/warm measurements for camera single, gallery
single, camera batch and gallery batch, with matched photos/resolutions, LEGACY
mode fixed, and all four OpenCV/correction flag combinations. Include clear/faint,
uneven lighting, diagrams, rotated EXIF images, many-line boards and maximum-size
batches. Report initialization, per-line/per-page p50/p95, source versus bounded
sizes, main-thread starts, heap/native/PSS/GC, queue/render time and failures.
Use timing-tag ON/OFF paired repeats to quantify the instrumentation overhead;
its device overhead has not been measured here. Confirm fresh-install offline
behavior and manually exercise camera/import/edit/export flows on real hardware.

Changed production files are the diagnostic utility plus timing call sites in
`ScanFragment`, `PdfViewerActivity`, `ImageQualityValidator`,
`WhiteboardRecognitionPipeline`, `WhiteboardPreprocessor`, `CrnnLineRecognizer`,
`CrnnInferenceRuntime`, `CrnnDiagnostics`, `MlKitLineRecognizer` and
`LineTextCorrection`. Two new test files and this report complete the change.
There are no Gradle, model, rule-engine or feature-default changes, and no
PaddleOCR/LiteRT work. Implementation of the ranked optimizations awaits approval.
