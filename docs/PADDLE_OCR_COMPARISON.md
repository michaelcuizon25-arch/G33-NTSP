# Stage 3B: offline PaddleOCR comparison

Work is on `feature/ocr-accuracy-improvements`, based on Stage 2.5 commit
`17ba8f7f12b70bd68f84a3f664cd497b9e2cbca7`. No commit, push or merge is authorized.

## Model and provenance

The user supplied these resources (the `.txt` configuration is YAML content):

| Artifact | SHA-256 |
| --- | --- |
| `en_PP-OCRv5_mobile_rec_infer.tar` | `e595b4cf2ffad19fbb5a61ba345d63939577a3ab8717b6e5995642590c9101b4` |
| `ppocrv5_en_dict.txt` | `e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6` |
| `en_PP-OCRv5_mobile_rec.txt` | `19ccdc0fdb0632ef20438584a609c63f40ba347233b7164fd04f3453a47ab50d` |
| Exported ONNX | `70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89` |

The archive was safely extracted using Python's data filter. It contains
`inference.json` (Paddle PIR), `inference.pdiparams` and `inference.yml`; no
unexpected executable payloads or links were present. All graph/weight files were
read successfully by the official Paddle CPU predictor and converter.

The supplied dictionary and training YAML are byte-identical to official source
at PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`:

- https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/ppocr/utils/dict/ppocrv5_en_dict.txt
- https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/configs/rec/PP-OCRv5/multi_language/en_PP-OCRv5_mobile_rec.yaml

The inference YAML embeds the same ordered 436 dictionary entries. The graph has
438 output classes, consistent with blank + dictionary + space. These checks
corroborate the supplied provenance and detect internal mismatch; they are not a
publisher signature. Direct access to the original binary download host remains
restricted, so no independent publisher checksum for the binary weights was
retrieved. The user identified the archive as official. Retain that distinction
when distributing artifacts. `ocr/paddle/provenance.json` records original and
exported hashes, source, versions, and this limitation.

The pinned PaddleOCR source is Apache-2.0. Its license is bundled as
`PaddleOCR-LICENSE.txt`; the inference archive itself contains no separate license
notice. The ONNX Runtime dependency retains its upstream packaged license notices.

## Export and numerical validation

Isolated development environment:

- PaddlePaddle CPU 3.2.2
- Paddle2ONNX 2.1.0
- ONNX 1.17.0
- ONNX Runtime CPU 1.22.0
- OpenCV 4.12.0, NumPy 2.2.6

Paddle2ONNX exports the PIR graph with opset 17, automatic opset upgrades disabled,
ONNX checker enabled, and optimizer `None`. No quantization or trained weight
updates were performed. The resulting self-contained ONNX file is 7,843,511 bytes;
there are no external weight files.

`tools/validate_paddle_export.py` executes the actual pinned official
`TextRecognizer.resize_norm_img` method, the official `CTCLabelDecode`, and the
native Paddle inference predictor. It compares the full output tensor with ONNX
Runtime CPU (rtol 1e-3, atol 1e-4) and requires identical decoded text. It does not
claim to exercise the entire high-level PaddleOCR/PaddleX service.

All 11 samples passed: white blank, eight versions of the same labeled title,
narrow noise and wide blank. Maximum absolute output difference was about 2.17e-5.
See `docs/paddle/parity-results.json` for all tensor shapes and predictions.

Example reproduction after obtaining the supplied archive and pinned checkout:

```sh
python tools/compare_opencv_enhancement.py prepare --output /tmp/ocr-images
python tools/validate_paddle_export.py --work-dir /tmp/paddle-validation \
  --source-dir /path/to/pinned/PaddleOCR --image-dir /tmp/ocr-images --export
```

The work directory must contain the extracted `en_PP-OCRv5_mobile_rec_infer`
folder. Install the exact development package versions above. Exporting is a
development operation; neither Python nor Paddle is shipped in the application.

## Preprocessing and decoding contract

Input `x`: float32 `[N, 3, 48, W]`, dynamic batch/width; application batch is always 1.
Output: float32 `[N, T, 438]` probabilities; observed T is ceil(W/8).

- Decode upright image into BGR (Android RGBA -> BGR via existing OpenCV).
- For each individual line, use official padded width
  `int(48 * max(320/48, originalWidth/originalHeight))`.
- Resize to height 48 and `min(paddedWidth, ceil(48 * originalWidth/originalHeight))`
  using OpenCV `INTER_LINEAR`, with its uint8 rounding behavior.
- Convert to float32 CHW, then divide by 255, subtract 0.5, divide by 0.5.
- Right padding is zero in normalized space. No inversion, thresholding, RGB swap,
  fixed-width squeeze or CRNN preprocessing is used.
- This experimental adapter accepts padded widths 320 through 3200. Excessive
  aspect ratios fail explicitly and are recorded as comparison errors; text is not
  silently clipped. The 3200 bound comes from supplied deployment shape metadata
  and limits memory, not from the training `max_text_length: 25` field.
- Greedy CTC collapses adjacent repeats before removing blank 0; space is 437.
  Dictionary entries remain strings so supplementary Unicode symbols are intact.
- The average selected probability is labeled uncalibrated. It is never compared
  with CRNN/ML Kit confidence to select output.

`PaddleRecognitionDeviceTest` compares actual Android OpenCV input tensors and
ORT output against four checked-in reference fixtures, repeats inference three
times, and closes the runtime twice. Native Android equivalence remains pending
until that test runs on a device; host parity alone cannot prove JNI equivalence.
Transparent/premultiplied gallery images need additional testing; the validated
fixtures and whiteboard photographs are opaque.

## Architecture and flags

`ENABLE_PADDLE_OCR=false` by default, independent of the existing disabled OpenCV,
correction and detailed diagnostic export options. To opt into comparison:

```sh
gradle assembleDebug -PENABLE_PADDLE_OCR=true
```

The public `CrnnLineRecognizer` constructor creates a cheap comparison recorder
only when enabled. Each existing CCL region is segmented by the same physical-line
segmenter as before. After normal CRNN inference/CTC, the exact same physical-line
bitmap, region ID and bounding box are passed to the secondary recognizer. No
second segmentation is used in comparison mode. CRNN raw text, tensor preparation,
CTC, confidence, correction inputs and note generation remain authoritative.

- `PaddleModelAssets`: local names, ordered charset, SHA-256 integrity checks.
- `PaddleInputPreprocessor`: model-specific BGR/resize/NCHW normalization.
- `PaddleCtcDecoder`: independent CTC decoder; existing CRNN decoder untouched.
- `PaddleRecognitionRuntime` / `OnnxPaddleRecognitionRuntime`: one lazy CPU ORT
  session per comparison recognizer, single-thread CPU settings, checked model
  metadata/output shape, native tensor/result scopes, serialized inference/close.
- `PaddleOcrLineRecognizer`: implements `LineRecognizer`, shares existing line
  segmentation for standalone research, exposes single-line inference for exact
  CRNN crop comparison. Native work uses `Dispatchers.Default`.
- `PaddleOcrComparison`: separate app-private JSONL records under
  `files/paddle-comparisons`, with CRNN/Paddle raw outputs and individual timings.
  File writes use IO. Comparison exceptions/native linkage errors are isolated;
  cancellation propagates. No UI engine switch or automatic prediction selection.

Session creation and dictionary verification happen on the first compared line;
empty scans do not initialize ORT. `close()` releases the per-scan session, while
ORT's process-wide environment is retained for other potential users. Input
buffers, native Mats, input tensors and result tensors have bounded lifetimes.
Comparison mode adds latency by deliberately running both engines sequentially;
it is not a production performance optimization. A failed native session initialization
is cached for the rest of that recognizer lifetime rather than retried for every line.

Existing camera, gallery and tutorial single scans share the public CRNN
constructor, so all support the comparison flag. Camera/gallery batch scans remain
ML Kit, with their Stage 2.5 optional OpenCV/correction hooks, original image and
diagram handling. Paddle is not inserted into batch scans or any fallback. No
camera/gallery UI, coordinates, orientation, note structurer, editor, persistence,
ML Kit client, CRNN weights or CRNN CTC changes are needed.

Comparison JSONL files contain note text. They are intentionally created only by
this research opt-in and are not uploaded. Delete them after analysis using app
storage controls or an authorized `run-as` debugging session. There is currently
no automatic retention policy; long research runs can consume disk space.

## Offline and Android compatibility

Official dependency: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`.
Its AAR declares minSdk 24, matching the application; compile/target SDK 34, JDK 17,
Gradle 8.5 and the current AGP remain unchanged. Native libraries cover armeabi-v7a,
arm64-v8a, x86 and x86_64. No ABI filters or packaging changes are introduced.
The model, dictionary, inference config and provenance/license are in APK assets.
There are no new HTTP clients, cloud OCR calls, runtime downloads or permissions.
Both default and comparison APK builds can resolve dependencies offline after the
development build cache has been populated. A fresh-install airplane-mode device
scan still needs verification; desktop inference is not a substitute.

## Accuracy and performance limits

`docs/paddle/accuracy-results.json` uses one manually labeled title with four
synthetic/capture variants and enhancement ON/OFF (8 cases, 240 reference characters,
24 reference words). Case-sensitive Levenshtein CER and whitespace-token WER:

| Engine | CER | WER |
| --- | ---: | ---: |
| Historical CRNN legacy captures | 37.08% | 79.17% |
| Historical CRNN SimpleHTR reference captures | 4.58% | 20.83% |
| Paddle ONNX current host inference | 0.42% | 8.33% |

Historical CRNN predictions come from the unchanged checked-in
`correction/crnn_title_outputs.tsv` on the same Stage 1 title image variants.
CRNN historically segments physical lines from these images; the host Paddle
evaluation above uses the full title crops. These scores therefore are not a
strict crop-identical comparison. This is not fresh paired Android inference, an independent handwriting corpus,
or evidence for enabling Paddle in production. The half-resolution enhanced
Paddle result loses a space. Both native Paddle and ONNX hallucinate `EMPY` on a
white 320px blank and `EY` on a wide white blank; colored blank also emits `.`.
Do not treat successful export/parity as proof of useful recognition on blank,
faint, cursive, technical or mathematical handwriting. There is insufficient
labeled data to quantify those groups.

Host benchmark details and memory scope are in `docs/paddle/benchmark-results.json`.
They use x86_64 CPU, one ORT thread, 3 warmups and 20 timed runs of the clear title.
Memory values are Python process RSS snapshots, not Android heap/PSS or per-engine
peak allocations. Concurrent Gradle activity can affect these observations. The
existing CRNN host median (~65ms, different run and workload mix) is not a controlled
speed comparison; use the per-line CRNN/Paddle timing records on Android instead.

## Validation and next steps

Build/test counts and APK deltas are recorded below.
No hardware is attached (`adb devices` reports an empty list). Android native
fixture execution, camera/gallery/tutorial scanning, feature combinations,
airplane-mode first install, repeated scan cancellation, native memory pressure,
ABI-specific inference and real CER/WER remain device gates. Keep the feature off.
Expand the labeled corpus with empty/diagram-only inputs, faint/cursive writing,
mathematics, technical vocabulary, very long lines and multiple resolutions before
considering engine selection. Do not calibrate cross-engine confidence by comparing
raw probabilities. LiteRT migration is not part of Stage 3B.

## Recorded build and test results

Default debug, release (unsigned) and Android-test APK assembly succeeded. The
explicit `-PENABLE_PADDLE_OCR=true` debug build and all seven focused Paddle unit
tests also passed. Final default builds restore all four flags to false.

The complete host unit suite contains **137 tests: 122 passed, 14 failed, 1
skipped**. The Stage 2.5 baseline was 115 passed / 14 failed / 1 skipped, so all seven
new unit tests pass and there are no new or resolved failures. The combined Gradle
command returns failure because of those same 14 baseline failures; this is not a
clean test suite. Existing failing assertions were not altered. Failure identifiers
are in `docs/paddle/test-results.json`. `git diff --check` passes.

Regression coverage verifies dictionary/asset hashes, BGR/CHW normalization and
zero padding, dynamic dimensions and oversize rejection, Unicode/space/blank CTC
handling, incompatible/non-finite outputs, exact line pixels/region identifiers,
unchanged positive CRNN results/input tensors after a secondary-engine failure,
cancellation propagation, and close behavior. Existing tensor/CTC, correction,
structuring, diagram and camera-preparation tests run as part of the full suite.
The native Android test APK compiles; it was not executed without hardware.

Observed APK file sizes (decimal MB; same existing universal-ABI packaging):

| APK | Stage 2.5 baseline | Stage 3B | Increase |
| --- | ---: | ---: | ---: |
| Debug | 251.42 MB | 277.11 MB | 25.68 MB |
| Release unsigned | 211.00 MB | 246.59 MB | 35.59 MB |

These are total APK deltas, not estimates of installed size. Default-off currently
still packages the dormant model/runtime, so the size cost also applies to the
default APK. A later comparison-only build variant or ABI splits could reduce
production distribution cost; neither packaging change was introduced here.
Exact byte counts and Android-test APK delta are in `docs/paddle/apk-size-results.json`.

A separate host run after Gradle completed measured 29.06ms median / 32.27ms p95
ONNX inference, 67.56ms cold session creation, and 0.22ms median host preprocessing
for a `[1,3,48,517]` title. Linux Python-process RSS was 70.82 MB before session
creation, 106.53 MB after creation and 120.07 MB after repeated inference. These
include Python/runtime/allocator overhead and are not Android memory estimates.
No device accuracy, latency, memory, camera/gallery lifecycle, first-install offline
or repeated Android scanning success is claimed. Keep comparison disabled pending
those device gates. No commit, push, merge, Paddle production selection, batch
engine change or LiteRT migration was performed.
