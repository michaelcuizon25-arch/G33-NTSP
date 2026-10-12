# Epoch 3 dynamic output mismatch: verified cause and complete-output fix

Branch `fix/crnn-dynamic-output` extends local fix `f9dbc5a`, based on experiment
commit `07a613f`. No push or merge, no main modification, no model changes,
retraining or recognition replacement. Both preprocessing modes, custom CCL,
physical-line segmentation and rule-based structuring are preserved.

## Actual model contract

FlatBuffer inspection and **native CPU/Flex inference** on the unchanged asset:

| Item | Input | Output |
| --- | --- | --- |
| Name | note2snap_image | note2snap_logits |
| Type | FLOAT32 | FLOAT32 |
| Serialized shape | [1,512,32] | [1,1,80] |
| Serialized shape signature | absent | [-1,-1,80] |
| Effective runtime signature | [1,512,32] | [-1,-1,80] |
| Shape after allocate_tensors | [1,512,32] | [1,1,80] |
| Shape after invoke | [1,512,32] | [1,128,80] |
| Native bytes after invoke | 65,536 | 40,960 |

The output batch and time dimensions are dynamic; class count 80 is fixed
(79 characters and the CTC blank). Absence of a serialized input signature means
the runtime uses the fixed input shape as its effective signature. The placeholder
one-step output is not a complete CTC sequence. The model has width/4 output
steps, so width 512 produces 128.

Asset SHA-256 is unchanged:
`f19c3b00c2507869a545a5475b1499f8b31c4717969225e5bc3f092369f02ab1`.

## Why the Android copy failed

The user's Xiaomi Android 16 trace reports `TensorImpl.copyTo` trying to copy
cached `[1,1,80]` output into the Java `[1,128,80]` array. This confirms the failure
mechanism investigated in `CRNN_INFERENCE_FAILURE.md`.

The diagnostic initializer introduced in `26f09e9` queried the output tensor
before invocation. TFLite 2.16.1 `TensorImpl` caches the current shape in
`shapeCopy`. ByteBuffer input does not request a resize, and the Java wrapper
refreshes cached output shapes after invocation only when it allocated tensors
for that run. With memory already allocated, the cached placeholder can remain
stale even though the native output has grown to 128 steps. The Java array copy
checks that stale shape and throws. The old per-line catch swallowed each
exception, eventually returning empty OCR and triggering a photo-quality message.

Deferring output queries fixes the initial cache hazard, but this revision also
removes the shape-dependent Java array copy entirely, providing stronger safety
against stale metadata.

## Correct output collection

`TensorFlowLiteCrnnRuntime` now calls:

```kotlin
interpreter.runForMultipleInputsOutputs(arrayOf(input), emptyMap<Int, Any>())
```

The empty output map is supported by the actual TFLite 2.16.1 wrapper: it invokes
the graph and skips Java `TensorImpl.copyTo` calls. After invocation, the adapter
reads `getOutputTensor(0).asReadOnlyBuffer()` and `numBytes()`, which expose the
native data and native byte count independently of cached Java shape metadata.

The collector validates:

- Fixed FLOAT32 input [1,512,32] and the exact input byte count.
- Rank-three output signature with 80 classes and the expected batch contract.
- FLOAT32 output, complete buffer size, and integral frame count.
- Time length derived from **all native bytes**, then checked against the
  unchanged model's input-width/4 contract.

Only after validation does it materialize `[batch][actualSteps][classes]` for the
existing decoder. Every native logit is read. There is no hardcoded one-step
output, prefix copy, truncation, padding of logits, model resizing or reflection
into private TFLite classes. A genuinely unresolved one-step native buffer or
malformed output raises a descriptive error instead of inventing a sequence.

Cached tensor shape, shape signature, native bytes and verified resolved layout
are logged separately. `model.json` also distinguishes cached output shape from
the native-buffer-derived output shape and records input/output signatures.

## Inference verification

Actual interpreter: **tensorflow-cpu 2.16.1**, numpy **1.26.4**, Select TF Ops/Flex
delegate. These packages are in scratch storage, not app dependencies.

One normalized-white input plus 11 identical physical-line crops through legacy
and selected/reference transforms produced **23 successful native invocations**.
Every output had [1,128,80], 40,960 bytes and finite values. Of the crop invocations,
12 used legacy (including one reference-width fallback) and 10 used reference.
Thirteen of 22 crop predictions were nonempty. Those counts are not accuracy
scores; some fixture crops contain artifacts and no new manual labels were added.

Both title-crop inputs decoded as **Mobile Application Development**. Full native
logits from those two inputs are committed as regression fixtures. The Kotlin
native-buffer collector and unchanged decoder produce exactly the independent
Python predictions for both.

The native audit is reproducible with `tools/audit_crnn_dynamic_output.py`:

```sh
python tools/audit_crnn_dynamic_output.py \
  --model app/src/main/assets/ocr/note2snap_crnn_v2_epoch03_flex.tflite \
  --charset app/src/main/assets/ocr/note2snap_crnn_v2_epoch03_charlist.txt \
  --session app/build/crnn-reference-comparison \
  --output /path/to/native-inference.json
```

The input session is produced by the existing native-graphics option-comparison
test. `--golden-output-dir app/src/test/resources/crnn_dynamic_native` regenerates
the title-output test resources; the manifest includes model/input hashes and
runtime version. The saved native audit is `crnn-native-inference.json`.

## Tests and physical phone checks

Regression coverage includes the exact stale-placeholder array-copy exception;
native-buffer inference despite that cache; full final-frame preservation;
different sequence lengths in the standalone collector; malformed byte sizes,
types/signatures and actual one-step-output rejection; decoder retention of the
last CTC frame; both preprocessing modes; and decoding real CPU/Flex logits.
Existing failure propagation, title-only, preprocessing parity and pipeline tests
continue to run.

Validation: **48 custom-pipeline tests passed** and the Android debug APK built
successfully. Full suite: **49 tests**, 48 passed; the unchanged
`MlKitRecognitionPipelineTest` failed because `MlKitContext` was not initialized.
No repository test was disabled; the custom run excludes that test only via an
external scratch Gradle init script.

CPU/Flex execution establishes native model behavior and full sequence length.
It does not execute Android JNI/Java on Xiaomi hardware or establish cross-runtime
logit parity. No Android device is attached here.

On the phone, import the original failing photos with LEGACY and repeat with
SIMPLE_HTR_REFERENCE. Capture:

```sh
adb logcat -v threadtime NOTE2SNAP_PIPELINE:V CRNN_DIAGNOSTICS:V '*:S'
```

Expect `nativeBytes=40960`, signature [-1,-1,80], resolved output [1,128,80], and
predictions for supported lines. A cached shape of [1,1,80] may still be visible;
it no longer controls copying or sequence length. Repeat imports to exercise
lifecycle behavior. Retain the first exception and session dump if any native
size/signature check fails. This fix does not promise better spelling or CCL
segmentation accuracy.

Production changes: `CrnnInferenceRuntime.kt`, `CrnnLineRecognizer.kt`,
`CrnnDiagnostics.kt`. Test changes: `CrnnFailureRegressionTest.kt`, new
`CrnnOutputBufferTest.kt` and native-logit fixtures. Added the native audit tool,
this report and native inference evidence. The previous failure-propagation/UI
fixes remain on this branch.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
