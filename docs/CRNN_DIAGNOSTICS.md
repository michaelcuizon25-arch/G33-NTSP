# Epoch 3 recognition investigation

This branch adds observation only. The CCL algorithm, physical-line segmentation,
whiteboard preprocessing, CRNN transform math, model assets, greedy decoder,
confidence calculation, and rule engine are unchanged from `ff5616f`.
The existing CRNN input transform was extracted unchanged for byte-parity testing.
No training notebook cells were run; no model was retrained or replaced.

## Findings from the supplied files

The strongest evidence for the spelling errors is the model's existing error
rate, with an additional confirmed preprocessing mismatch. This does not yet
quantify how much CCL contributes on the user's actual phone.

Notebook cell numbers below are zero-based JSON cell indices in
`Note2Snap_CRNN_Training_v22.ipynb`. Results are saved notebook output, not a new
independent training/inference run in this workspace.

- Cells 51 and 56 report and reproduce Epoch 3 validation: V1 CER **20.65%**, WER
  **57.79%**; V2 CER **35.18%**, WER **77.92%**. These tests use existing line
  crops and the training/reference preprocessing, bypassing Android CCL. Thus
  segmentation alone cannot account for all the misspelled words.
- Cell 3's exported-graph reference reads `INHERITANCE` as `IHPRIRHCG`, using the
  declared reference transform. Cell 74 gives more inaccurate predictions from
  complete validation crops. Being the best of five epochs does not make Epoch 3
  accurate enough for reliable transcription.
- Cells 75–76 evaluate right-padding on 54 V2 lines: 19 predictions change; CER
  goes from **35.18% to 35.37%**, only **+0.19 percentage points**. This isolates
  right-padding of already-preprocessed crops, NOT the current Android transform.
- Cells 21 and 3 instantiate `Preprocessor((256,32), dynamic_width=True,
  padding=16, line_mode=False, data_augmentation=False)`. The supplied Python
  `process_img` scales to height 32, computes `int(scale*width+16)`, rounds up to
  a multiple of four, and horizontally centers the image in that width. It uses
  floating-point OpenCV affine interpolation with a white background, transposes
  to width-major order, then applies `gray/255 - 0.5`. The reference then pads
  right with +0.5 to width 512. Wider reference crops are skipped, not compressed.
- Cells 32–33 also stretch 25/725 training examples horizontally to obtain enough
  CTC time steps, based on their known labels. This training-only operation is
  distinct from ordinary validation preprocessing and cannot be applied blindly
  in deployment where the correct label is unknown.

### Actual Android/reference comparison

| Operation | Existing Android | Supplied reference |
| --- | --- | --- |
| Input crop | Physical-line crop from downscaled original colour photo | Existing line crop, OpenCV grayscale |
| Grayscale | Canvas ColorMatrix saturation=0, then weighted channel reading | OpenCV IMREAD_GRAYSCALE |
| Scale | min(512/width, 32/height) | 32/height |
| Dimensions | Scaled dimensions rounded to nearest integer | Floating affine mapping, width rounded to multiple of 4 |
| Placement | Top-left; pad right/bottom | Centered horizontal margin totaling at least 16 pixels; then pad right to 512 |
| Interpolation | Bitmap.createScaledBitmap with filtering | cv2.warpAffine, default linear interpolation |
| Normalization/polarity | gray/255−0.5; white +0.5, dark −0.5 | Same |
| Memory layout | x-major then y | Transposed width-major |
| Width above 512 | Shrink both dimensions; writing becomes shorter than 32 pixels | Reference cannot fit and notebook skips it |

The adaptive threshold/binary image is only for CCL. CRNN is **not** receiving
that cleaned binary image: its source is the original-colour recognition bitmap.
The original maximum-dimension downscale, threshold offset/window, noise cleanup,
and border clearing remain untouched.

Native-graphics Robolectric auditing on `board_test_5.jpg` produced 11 physical
crop inputs, including artifacts. All 10 reference transforms that fit width 512
are numerically different from the unchanged Android transform. One long row
requires reference width **796**, exceeding the fixed model width. For the
complete `Mobile Application Development` title, the Android crop is 753×74;
reference width is 344 and normalized tensor RMSE is about **0.1099**. This proves
transform differences; it does not measure their effect on recognition accuracy.
The audit uses the repository lecture-board fixture, not the user's Elements of
Design photo, and performs **no model inference**. Legacy JVM graphics produced a
blank Canvas artifact, so those earlier numerical results were discarded. The
final audit and byte-parity tests use Robolectric native graphics.

### Model contract and confidence

FlatBuffer inspection of the bundled Epoch 3 asset confirms FLOAT32 input
`note2snap_image` of shape **[1,512,32]**. Its dynamic output is named
`note2snap_logits`; initial serialized shape is [1,1,80], resolving during
inference to the expected [1,128,80] contract. The old Android comment declaring
[1,512,32,1] was inaccurate; the existing ByteBuffer still contains the correct
number and ordering of values. This comment discrepancy does not establish an
inference bug.

The 79-character asset exactly matches the notebook character list; blank index
79 agrees. The bundled model has Select TensorFlow/Flex operators and no builtin
Softmax. The existing `confidence` method averages selected raw output values;
the notebook exports logits. Treat this number as an existing raw score, not a
calibrated probability, and do not introduce a confidence cutoff based on it.

Bundled model SHA-256:
`f19c3b00c2507869a545a5475b1499f8b31c4717969225e5bc3f092369f02ab1`.
A notebook-export hash or Android reference input/logits would be needed to verify
artifact identity and cross-runtime numerical parity conclusively. The notebook
verifies its frozen inference graph, but its saved TFLite invocation fails from
missing Flex support. No Android/CPU TFLite parity result is claimed here.

### Elements of Design photo

The attached photo has low-contrast grey surroundings, blue headings, isolated
marks above the writing, and separate definition entries written with **hyphens**,
not colons. Forms and Texture each wrap onto a second physical row. The target
layout has approximately 12 handwritten rows, including both headings and wraps.
A complete physical row is not necessarily a complete sentence.

The rule engine's definition detector handles `key: value`, not `Shape - ...`,
`Forms - ...`, or `Texture - ...`. A model that drops punctuation or changes case
can cause its continuation heuristic to join such entries: for example, `Shape
- ... space next to` ends with connector word `to`, and a nearby `Forms - ...`
line is not recognized as a definition boundary. This is a distinct possible
structuring failure, rather than proof that CCL merged those crops. The rule
engine was not changed in this investigation.

The inline photo can be inspected visually, but it is not available as a local
image file for exact replay here. There is no phone inference dump yet. Therefore
no predictions or crop-completeness labels for this photo are invented.

## What the diagnostic build records

Each real scan saves a uniquely named session in the app's external files folder:

`Android/data/com.example.note2snap/files/crnn-diagnostics/<timestamp>-<uuid>/`

If external app storage is unavailable, it falls back to the app cache directory.
Logcat tag `CRNN_DIAGNOSTICS` prints the exact session path and each prediction.
Nothing is uploaded automatically. Separate scans preserve earlier sessions.

Files:

- `source.png`: original decoded photo.
- `recognition.png`: downscaled coordinate reference.
- `ccl-binary.png`: actual CCL foreground image.
- `source.json`: original/downscaled dimensions, coordinate scale, all region
  boxes/types and pixel areas. Boxes use recognition-image pixels, with exclusive
  right/bottom bounds.
- `region-<id>.png`: each CCL crop, before physical-line splitting.
- `line-NNNN-crop.png`: exact physical-line bitmap that is preprocessed for CRNN.
- `line-NNNN-input.f32`: exact FLOAT32 ByteBuffer captured immediately before
  Interpreter.run; x-major layout, byte order recorded in JSON.
- `line-NNNN-input.png`: visual reconstruction of that actual tensor, not a second
  preprocessing pass.
- `line-NNNN-output.f32`: raw model output, for same-input runtime comparisons.
- `line-NNNN.json` and `lines.jsonl`: region ID, CCL box, physical-line box, crop
  dimensions, raw/cleaned predicted text, score, output-buffer shape, and status.
  Empty decoded text and inference failures are recorded rather than omitted.
- `model.json`: actual tensor types/shapes, vocabulary, asset hashes, preprocessing
  convention and confidence caveat.
- `structured.json`: final title and typed blocks with boxes, allowing comparison
  of raw CRNN lines with later paragraph merging.
- `review.html`: local gallery of crop/tensor images, predictions and boxes.

Whether a crop contains a complete handwritten row is a **human annotation**;
geometry alone cannot establish it. Initial JSON uses `cropCompleteness:
"unreviewed"` and `containsCompleteHandwrittenLine: null`. Open `review.html`,
compare each crop with the full photo, choose complete/fragment/multiple_lines/
noise/uncertain, and transcribe the visible ink. Export `reviewed-lines.json` to
save those decisions. This avoids claiming that a wide crop is complete merely
because its bounding box looks line-like.

Diagnostic I/O failures are caught and logged without filtering, changing, or
blocking recognition outputs. The original transform is verified byte-for-byte
against a test-only copy of the pre-diagnostic implementation.

### Collect a phone session

Install the debug APK built from this branch, scan the Elements of Design board,
and inspect:

```sh
adb logcat -s CRNN_DIAGNOSTICS
adb pull /sdcard/Android/data/com.example.note2snap/files/crnn-diagnostics ./crnn-diagnostics
```

If a device uses a different external path, use the path printed in logcat. For
the cache fallback on a debuggable build, use `adb shell run-as
com.example.note2snap` to locate the folder and `adb exec-out run-as
com.example.note2snap tar -C cache -cf - crnn-diagnostics > crnn-diagnostics.tar`.
Open the downloaded session's `review.html` and export reviewed labels. Preserve
the original crop/tensor/output files alongside those labels.

### Compare captured preprocessing

`tools/compare_crnn_preprocessing.py` uses the supplied, reviewed Python
preprocessor's `process_img` only, without executing the notebook or training
loader. Install numpy and opencv-python-headless in a suitable Python environment.

```sh
python tools/compare_crnn_preprocessing.py path/to/session \
  --preprocessor path/to/preprocessor.py --output path/to/comparison \
  --reviewed-labels path/to/reviewed-lines.json
```

This saves reference tensors/images and numerical differences. It reports a
reference crop wider than 512 instead of silently shrinking it.
With a TensorFlow runtime that supports Flex, add
`--model app/src/main/assets/ocr/note2snap_crnn_v2_epoch03_flex.tflite` to replay
both transforms through the **same unchanged model**. It verifies the model hash,
compares captured and replayed outputs, and computes character error for complete
manually labeled crops. This optional model replay has not been run here.

## Evidence required before the next recognition fix

1. **CCL/line splitting:** crops visibly cut off words, include two unrelated
   rows, or contain board marks. Compare each CCL region to its physical-line
   children: this distinguishes CCL grouping from later projection splitting.
   A manually corrected crop improving the same model's prediction supports a
   segmentation fix, rather than more threshold guessing.
2. **CRNN preprocessing:** the same complete, manually labeled crop is decoded
   more accurately with the supplied reference transform than with Android's
   captured tensor, through the same model/runtime. Tensor mismatch alone is
   insufficient evidence of accuracy improvement. Include long crops rather than
   silently dropping the difficult cases.
3. **Model/runtime:** complete crops with reference preprocessing still have poor
   CER/WER. If the phone and CPU disagree on identical tensors, investigate model
   identity, Flex/runtime or output handling before blaming model capacity. If
   identical-input logits/predictions agree and both are poor, the unchanged
   model's quality is limiting recognition; further CCL tuning cannot recover
   those words. This investigation does not authorize retraining/replacement.
4. **Rule-based merging:** raw crop predictions are separate definitions, but
   `structured.json` joins them. That supports a targeted definition-boundary
   fix, independent of CCL or the model.

## Validation and changed files

29 custom-pipeline unit tests passed and the Android debug APK built successfully.
The full suite ran 30 tests: 29 passed and the pre-existing
`MlKitRecognitionPipelineTest` failed because `MlKitContext` was not initialized.
The separate custom-pipeline run excludes that test only through an external
scratch validation script; no repository test was disabled. The native
input-parity tests cover narrow, wide, exact-size and ordinary crops plus the
photo fixture. Recorder tests verify exact PNG/tensor capture, unchanged buffer
position/bytes, empty predictions, error capture, unique sessions, and nonfatal
storage failures.

Changed production files: `ScanFragment.kt`, `WhiteboardRecognitionPipeline.kt`,
`CrnnLineRecognizer.kt`; new helpers `CrnnDiagnostics.kt`,
`CrnnInputPreprocessor.kt`. Added tests: `CrnnDiagnosticsTest.kt`,
`LegacyEpoch3Input.kt` (test-only copy). Added this document and the offline
comparison tool. CCL, WhiteboardPreprocessor, PhysicalLineSegmenter,
WhiteboardRuleEngine, model assets and dependencies are unchanged.

No new branch was pushed, no PR was merged, and main was not modified.
