# Recognition failure investigation and fix

Follow-up: the user's actual Android stack trace and native CPU/Flex verification
are documented in [CRNN_DYNAMIC_OUTPUT.md](CRNN_DYNAMIC_OUTPUT.md). That revision
also replaces Java array output copying with validated full native-buffer reading.

Branch: `fix/crnn-inference-failure`, based on `07a613f` from
`experiment/epoch3-reference-preprocessing`. No weights, preprocessing modes,
custom CCL, physical-line algorithm, rule engine, vocabulary, decoder, confidence
calculation, or dependencies were changed. No ML Kit/YOLO recognition was added.
Nothing was retrained, pushed, or merged.

## Regression introduced by diagnostic metadata

Commit `26f09e9` added calls to `interpreter.getOutputTensor(0).shape()` and
`.dataType()` in the recognizer initializer. They remained in `07a613f`.
The Epoch 3 output is dynamic: its serialized placeholder is `[1,1,80]`, while
the established invocation output buffer is `[1,128,80]`.

Inspection of the **actual cached TFLite 2.16.1 Java classes** with `javap`
establishes this sequence:

1. `getOutputTensor` creates a `TensorImpl` whose constructor caches the current
   shape in `shapeCopy`. Asking for dtype also creates that output tensor.
2. `TensorImpl.getInputShapeIfDifferent` returns null for Buffer inputs. Both
   preprocessing modes use a ByteBuffer, so neither requests input resizing.
3. `NativeInterpreterWrapper` allocates tensors during initialization. In `run`,
   `allocateTensorsIfNeeded` returns false when allocation is already complete.
4. After native invocation, the wrapper refreshes cached output shapes **only
   when that allocation flag is true**.
5. `TensorImpl.copyTo` checks a Java array's shape against cached `shapeCopy`.
   A premature dynamic placeholder can therefore cause a persistent copy error:
   `[1,1,80]` versus `[1,128,80]`, despite the native tensor resolving during run.

Previously the output TensorImpl was first obtained by the wrapper *after*
invocation, when its shape was resolved. The added diagnostics changed that
ordering. Recreating a recognizer for each imported photo repeats the same
problem, and switching preprocessing modes cannot repair it.

The fix keeps output shape/dtype **unqueried before the first successful run**.
Runtime initialization and pre-invoke logging use an empty shape and
`UNRESOLVED_BEFORE_INFERENCE`. After a successful invocation, shape/dtype can be
read and model metadata is updated with the actual result. There is no dummy
inference, output-size guess, model resize, or decoder change.

Evidence boundary: library cache behavior and the application's premature read
are confirmed. A regression test models that exact cache behavior, reproduces
the old array-copy failure, and verifies that the production runtime wrapper and
recognizer initialization avoid it. There is **no attached Android device** and
no supplied failed-import stack trace, so the actual phone's native placeholder,
exception and recognition recovery still need confirmation. The test is not a
claim that Android Flex kernels were run in this workspace.

## Additional confirmed error handling bugs

The per-line catch block previously called `printStackTrace()` and continued.
If every invocation failed, `recognize` returned an empty list. The structurer
produced an empty note and the UI displayed **No readable writing detected**,
incorrectly treating a runtime error as an image-quality problem.

Now every failure records its preprocessing/inference/decoding stage, crop box,
region ID and full exception in Logcat. When failures leave no recognized text,
`CrnnRecognitionFailure` reaches the scan UI with the original exception as its
cause, and a recognition-error message replaces the false photo-quality warning.
Successful blank predictions remain empty results, and partial failures preserve
successful readable lines. Coroutine cancellation is rethrown.

The UI also previously judged readability solely from structured body blocks.
The rule engine extracts a document title out of those blocks, so a valid
title-only prediction was rejected. Readability now uses the raw recognized
lines with the **unchanged three-alphanumeric-character rule**. A title-only
note renders an escaped heading in editor content rather than an empty body.
This fixes acceptance/rendering; title classification itself is unchanged.

The model loader's AssetFileDescriptor is now closed along with its input stream,
preventing the existing per-recognizer descriptor leak. This was not established
as the phone's immediate failure cause.

## Pipeline trace and diagnostics

The single-photo fragment now invokes `WhiteboardRecognitionPipeline`, using
the same implementation covered by pipeline tests, and closes it in `finally`.
The stage order remains:

1. Decode original image, downscale recognition bitmap, adaptive binary CCL input.
2. Custom CCL on binary input; text crops use the original-color recognition image.
3. Physical-line segmentation for each TEXT region.
4. Selected legacy/reference transform, including reference-width fallback.
5. Unchanged Epoch 3 TFLite invocation and greedy decoder.
6. Rule-based structuring and readability validation against raw OCR.

Use:

```sh
adb logcat -v threadtime NOTE2SNAP_PIPELINE:V CRNN_DIAGNOSTICS:V '*:S'
```

Logcat records source/recognition dimensions; total, text and non-text CCL counts;
physical-line counts and boxes; mode/fallback; input byte count/position/order;
post-invoke output shape; each prediction; full failures; recognized/blank/failed
counts; final structured-block counts and readability.

Each session also saves `recognition-summary.json`, with one of:
`no_text_regions`, `no_physical_lines`, `all_predictions_blank`,
`recognition_failed`, `partial_success`, or `recognized`. Existing exact crops,
tensors, predictions, model metadata and review gallery remain available.
Failure records include `failureStage`; before successful invocation,
`model.json` explicitly records that dynamic output metadata was unqueried.

## Regression coverage and phone verification

Nine new native-graphics Robolectric tests cover premature dynamic-output access,
actual recognizer initialization without that access, repeated safe invocations,
inference failure propagation with the original cause, blank outputs, partial
success, cancellation, identical prepared inputs in both modes, unchanged
decoding, title-only acceptance/HTML escaping, distinct empty-stage statuses and
the real photo preprocessing/CCL/segmentation pipeline followed by a failed
runtime. Model invocations in these tests are injected; no accuracy claim is made.

All **43 custom-pipeline tests passed**, and `assembleDebug` succeeded. This
includes the existing legacy byte-parity and Python reference golden tests.
The full suite ran **44 tests**: 43 passed and the unchanged
`MlKitRecognitionPipelineTest` failed because `MlKitContext` was not initialized.
The separate custom-suite run excludes that test only through an external scratch
Gradle init script; no repository test was disabled.

On the phone, import a previously successful photo and check:

- The first invoke completes, output becomes `[1,128,80]`, and predictions/notes
  return under LEGACY. Repeat another scan to check resource/lifecycle behavior.
- Repeat with SIMPLE_HTR_REFERENCE using the unchanged explicit recognizer option;
  long lines should still report the legacy fallback.
- If the result remains empty, retain the first exception and the session summary.
  `no_text_regions` implicates preprocessing/CCL, `no_physical_lines` implicates
  segmentation, successful blank outputs require model/input investigation, and
  `recognition_failed` now gives the exact runtime failure instead of hiding it.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Modified production files: `ScanFragment.kt`, `WhiteboardRecognitionPipeline.kt`,
`CrnnLineRecognizer.kt`, `CrnnDiagnostics.kt`; added `CrnnInferenceRuntime.kt` and
`RecognitionReadability.kt`. Added `CrnnFailureRegressionTest.kt` and this report.
