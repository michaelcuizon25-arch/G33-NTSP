# Experimental per-line Paddle crop refinement

Disabled by default. Enable only with:

```text
-PSINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK
-PENABLE_PADDLE_CROP_REFINEMENT=true
```

The refinement is applied inside PaddleOcrLineRecognizer, after physical-line
segmentation and before PaddleInputPreprocessor. Only the production primary
adapter opts into the BuildConfig property. CRNN preprocessing, decoders, CCL,
frame filtering, region geometry and rule-based structuring are unchanged.
The comparison/shadow Paddle adapter remains off unless explicitly requested
by its caller. There is no hybrid engine selector or second OCR pass per line.

## Conservative verification

Revision: flat-background-v1.

- Accept only opaque line images within a four-million-pixel analysis budget and
  width at most 8192. Unsupported inputs remain unchanged.
- Find and verify an exact RGB background color occupying at least 55% of pixels.
  A reusable pixel row and two image passes avoid a full-image mask or histogram.
- Every pixel different from that background is protected, including isolated
  punctuation and one-level faint marks. No small-component removal or
  foreground-count-per-row threshold is used.
- Require at least eight protected pixels, three rows of extent and contrast of
  at least 16 RGB levels somewhere in the body. An entirely faint or ambiguous
  input stays unchanged.
- Trim only completely verified background rows beyond the protected envelope.
  Retain at least three pixels or 8% of protected height as padding, whichever
  is larger. Preserve the full horizontal extent.
- Remove at most 20% of original height (maximum 1.25x scale gain). Retain excess
  whitespace as additional padding.
- Reject a refinement that would exceed Paddle's existing width envelope.
- Any analysis exception uses the original input. No uncertainty or refinement
  exception is itself a reason to initialize CRNN.

This deliberately strict first version will abstain on many photographic crops.
JPEG noise, shadows, paper texture and a vertical frame reaching into the margin
are not certified empty. The manually reviewed row-3 trim from the prior study is
not hardcoded, learned as a threshold or copied into production logic.

## Geometry and ownership contract

A refinement returns only crop-local top/bottom offsets. It never changes or
replaces Region.id, Region.boundingBox, PhysicalLine.boundingBox or
RecognizedLine.boundingBox. Both nearby and spatially separated source sections
retain their original source coordinates for future layout analysis.

Only a temporary model-input bitmap is vertically cropped. The caller's source
bitmap is unmodified and remains available for research export and CRNN fallback.
The temporary bitmap is released after inference, including failures/cancellation.
The original physical-line crop and bounding box are passed to CRNN if Paddle
is unusable. No entire-whiteboard crop or column-layout stage is introduced.

## Research and timings

ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS remains a separate, default-off opt-in.
When both flags are enabled, scan.json preserves original source boxes and crops,
adds cropRefinement records with reason, originalSourceBox, localInputBox,
supplemental modelInputSourceBox, elapsedNs and scratch bytes. Applied inputs are
also exported as line-N-paddle-refined.png. Supplemental geometry never replaces
the original box used by note structuring. Exports contain handwriting and images;
ordinary Logcat contains only fixed labels/counts/configuration/timings.

NOTE2SNAP_PERF records PADDLE_CROP_REFINEMENT separately. Disabled mode performs
no refinement analysis or row-buffer allocation. Enabled analysis uses one IntArray
of width*4 payload bytes plus small metadata objects. An applied crop adds an
ARGB bitmap pixel buffer; inference tensor width can grow by about 25% (with shape rounding), within the
existing maximum. These payload sizes are not a measurement of process peak heap.
Detailed measurements and accuracy are in evaluation/crop-refinement-validation.

Build using Android Studio's Gradle runner; this checkout has no wrapper:

```text
:app:assembleDebug :app:assembleDebugAndroidTest
-PSINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK
-PENABLE_PADDLE_CROP_REFINEMENT=true
-PENABLE_OFFLINE_TEXT_CORRECTION=true
-PENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS=true
-PENABLE_PADDLE_OCR=false
-PENABLE_OPENCV_ENHANCEMENT=false
-PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
-PENABLE_OCR_EVALUATION_EXPORTS=false
```

Install with adb -s DEVICE_SERIAL install -r (add -t for the instrumentation APK).
Replace DEVICE_SERIAL with the connected test device's serial.
Never uninstall or clear user data for these tests. Rebuild with
-PENABLE_PADDLE_CROP_REFINEMENT=false for the established recognition behavior.

Research A/B harness: PaddleCropRefinementDeviceTest, with
runCropRefinementResearch=true and corpus=real or corpus=rendered.
The real run also needs capturedDirectory pointing to an existing exported scan.
It compares all declared samples, reverses A/B order in the second iteration,
shares a native runtime, preserves exact raw strings and never runs CRNN.
Rendered controls are reported separately from photographed handwriting.
Missing handwritten categories remain unvalidated.

Keep research exports in a new local destination. Resolve the app's external-files
location on the test device when collecting them; device-specific paths and raw
records are intentionally excluded from this change.

Do not enable by default without broader labeled handwritten crops and a
validated image-aware verification policy. More aggressive photographic background
modelling would weaken this exact pixel-preservation contract and needs a separate
measured change.

## Scoring and validation scope

The validation scorer imports pure edit-count functions from tools/ocr_scoring.mjs.
That utility contains only whitespace normalization, Levenshtein distance and
character/word edit counts. It has no file IO, OCR model or engine-selection policy.
The earlier paired-engine research scorer is independent and is not required.

On a machine with Node, run:

```text
node --test tools/ocr_scoring.test.mjs tools/score_crop_refinement.test.mjs
node tools/score_crop_refinement.mjs EXPORTED_REFINEMENT_DIRECTORY
```

Replace EXPORTED_REFINEMENT_DIRECTORY with the local, private export directory.
Do not commit the exported images, transcriptions or records.

The recorded experiment included all five available photographed handwriting
crops: all were left unchanged, with CER 25.73% and WER 41.03% before and after.
Ten separate rendered controls had four accuracy improvements, five regressions,
and one unchanged error count; their WER increased from 89.61% to 92.21%.
Rendered controls are not evidence of handwriting accuracy in missing categories.
These results support keeping refinement disabled by default.
