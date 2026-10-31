# Stage 1: optional offline OpenCV whiteboard enhancement

Implemented on `feature/ocr-accuracy-improvements`, based on `49713e8`.
No model, charset, CRNN input transform, tensor contract, CTC decoder, CCL,
physical-line segmentation, ML Kit or rule-engine changes.

## Integration and configuration

`WhiteboardPreprocessor` downsizes exactly as before (maximum side 1800), then
optionally invokes `OpenCvWhiteboardEnhancer`. Both the existing adaptive binary
mask and recognition crops are derived from the same resulting image. Sizes and
coordinate spaces are unchanged. `WhiteboardRecognitionPipeline` and the existing
single-scan factory need no changes. Batch camera/gallery ML Kit paths are unchanged.

The official Maven Central Android AAR is `org.opencv:opencv:4.12.0` (Apache-2.0,
minimum API 21; this app requires API 24). Its native libraries are bundled in the
APK; `OpenCVLoader.initLocal()` loads them lazily. No OpenCV Manager, downloads,
cloud processing, network permission or scanning-time Internet access is added.
Android test dependencies are `androidx.test.ext:junit:1.1.5` and
`androidx.test:runner:1.5.2`.

The generated `BuildConfig.ENABLE_OPENCV_ENHANCEMENT` is **false by default**.
Opt into an evaluation build with:

```sh
gradle assembleDebug -PENABLE_OPENCV_ENHANCEMENT=true
```

Omit the property for the original behavior. Invalid boolean strings fail the
build instead of silently selecting an unexpected configuration. The existing
LEGACY/SIMPLE_HTR_REFERENCE selector remains independent. The device test forces
both enhancer settings directly, so it does not need an enabled app build.

## Enhancement algorithm

1. Convert the bitmap's RGBA pixels to RGB and then Lab.
2. Use Lab L as the grayscale/luminance plane, preserving the chroma planes.
3. Apply a mild 3-pixel bilateral filter (sigmaColor 10, sigmaSpace 1.5).
4. Apply CLAHE (clip limit 1.5, 8x8 tiles) to that luminance.
5. Blend 50% original luminance with 50% enhanced luminance.
6. Recombine chroma and return a new ARGB bitmap of exactly the same dimensions.

No new threshold, morphology, inversion, crop or geometric warp is introduced.
The existing adaptive threshold remains downstream. Images smaller than 16 pixels
in either dimension bypass enhancement. Perspective correction is deliberately
inactive: the current scanner has no validated board-corner input. Guessing board
contours could remove content or invalidate diagram coordinates. Adding reliable
corner detection and coordinate transforms would need separate validation.

The source is not mutated or recycled. `PreprocessingResult` retains the bounded
original in `originalRecognitionBitmap`, alongside applied/requested state, a
fallback reason and elapsed time. Existing diagnostic sessions retain `source.png`
and `recognition.png`; enabled comparisons also save `recognition-original.png`.
`source.json` records enhancement metadata. Native Mats are released after each
operation. Processing/native-loading errors fall back to the original image and
existing thresholding; coroutine cancellation is propagated.

## Validation and reproduction

Run the defaults-off regression suite and build both variants and device tests:

```sh
gradle testDebugUnitTest
gradle assembleDebug assembleRelease assembleDebugAndroidTest
```

`WhiteboardEnhancementTest` verifies disabled behavior, no native invocation when
disabled, source preservation, bounded processing, coordinate alignment, use of
enhanced pixels for both OCR and CCL, processing/native-error fallback, rejection
of changed dimensions, and cancellation propagation. It uses an injected enhancer;
it does not establish Android OpenCV JNI behavior.

On an attached Android device, run:

```sh
gradle connectedDebugAndroidTest
```

`OpenCvEnhancementDeviceTest` requires actual native enhancement (a fallback fails
the test). It compares all ten existing board fixtures and controlled faint,
uneven-lighting and resolution variants using unchanged CRNN inference and both
input preprocessing modes. It also checks a synthetic faint thin stroke and
colored diagram, and computes case-sensitive CER/WER for the existing manually
identified `Mobile Application Development` title. Results and paired PNGs are
written under the app's external-files `opencv-comparison` directory. It does not
assert that enhancement improves accuracy before those results are evaluated.

### Optional host CPU/Flex comparison

The host audit is deliberately separate from Android JNI verification. Host
OpenCV 4.12.0 prepares paired PNGs; the original Kotlin CCL, physical-line splitting
and input transforms export tensors; native TensorFlow CPU/Flex runs the unchanged
weights; a second Kotlin pass decodes the saved logits using the existing CTC
implementation. Host OpenCV initially bounds large photos with its own resize,
so this is not proof of full Android image-decoder/resize parity.

Use separate host environments: an image environment with
`opencv-python-headless==4.12.0.88` and NumPy 2.2.6, and an inference environment
with `tensorflow-cpu==2.16.1` and NumPy 1.26.4. They are development tools, not app
dependencies. Image preparation and inference can run in their respective
environments because the script imports OpenCV and TensorFlow only as needed.

```sh
python tools/compare_opencv_enhancement.py prepare --output /absolute/path/audit
```

Create a scratch Gradle init script (replace the path with the same audit directory):

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        outputs.upToDateWhen { false }
        systemProperty 'note2snap.opencv.audit', '/absolute/path/audit'
    }
}
```

Then execute:

```sh
gradle testDebugUnitTest --tests '*OpenCvHostComparisonTest' --init-script /path/to/audit.init.gradle
python tools/compare_opencv_enhancement.py infer --output /absolute/path/audit
gradle testDebugUnitTest --tests '*OpenCvHostComparisonTest' --init-script /path/to/audit.init.gradle
```

The first pass writes actual Kotlin `input-*.f32` tensors. The script writes
`output-*.f32` native logits. The second pass validates identical input bytes and
writes predictions and available CER/WER to `kotlin-comparison.json`.
`native-inference.json` records the model hash, runtime versions and output shapes.
Without the opt-in system property this test is skipped in the normal suite.
Do not reuse an audit directory for a different dataset: ordering/input changes
are intentionally rejected. Delete only your generated audit outputs or use a
fresh directory when changing cases.

CER is character edit distance divided by reference characters; WER is token edit
distance divided by reference words. The audit preserves case and punctuation.
Only four variants of one known title have labels, so these metrics cannot
establish general handwriting quality. The remaining board comparisons have no
complete verified transcriptions.

## Limits and rollout safeguards

Keep the flag off pending representative labeled handwriting and Android device
validation. CLAHE can amplify board texture and change CCL grouping, even though
its API and geometry are unchanged. Faint-writing, diagram and colored-marker
quality must be checked visually and against labeled text. Repeated scans need
latency/peak-memory measurements on low-memory devices. Native processing uses
several temporary image planes and retains an additional bounded original bitmap.

The universal debug APK is about 240 MiB; unsigned release about 202 MiB. OpenCV
ships four ABIs and contributes about 135 MiB of uncompressed native libraries in
total. No ABI is removed in this stage. The inspected arm64 OpenCV ELF uses 16 KiB
LOAD alignment; full app/device compatibility also depends on the existing OCR
libraries, packaging and toolchain. Host tests cannot establish that compatibility.

Historical normalization/debug-metadata inconsistencies found during planning
remain outside this stage. No preprocessing-mode default or model input semantics
have been changed to resolve them.

## Results from this workspace

Baseline at `49713e8`: 80 unit tests, 66 passed and 14 failed; debug APK built.
After integration, seven new unit tests pass and the same 14 pre-existing tests
fail. The opt-in host audit passes with 437 real native CRNN outputs; the normal
suite skips this audit when no data directory is supplied. Debug, unsigned release
and instrumentation APK builds succeed. No device/emulator is attached, so the
three instrumentation tests are compiled but have not been executed.

The baseline failures include ML Kit context initialization; two diagnostics
preprocessing assertions; a title/editor-content assertion; and ten structuring
assertions. Their identities are compared against the baseline, not suppressed.
The full suite remains failing and must not be described as entirely passing.

Host comparison: OpenCV 4.12.0/NumPy 2.2.6 image preparation;
TensorFlow CPU/Flex 2.16.1/NumPy 1.26.4 inference; existing Kotlin input transforms,
physical segmentation and CTC prefix beam decoder. All 437 outputs were finite
FLOAT32 `[1,128,80]` (40,960 bytes). All 437 were decoded on the second pass.

The only labeled sample is the 30-character, three-word title, repeated as clear,
synthetically faint, unevenly lit and half-resolution variants. Aggregate metrics
below have 120 reference characters and 12 reference words per mode/setting.
They are diagnostic results on correlated samples, not general accuracy estimates.

| Existing CRNN mode | Original CER | Enhanced CER | Original WER | Enhanced WER |
| --- | ---: | ---: | ---: | ---: |
| LEGACY (unchanged default) | 37.50% | 36.67% | 83.33% | 75.00% |
| SIMPLE_HTR_REFERENCE (existing option) | 8.33% | 0.83% | 33.33% | 8.33% |

For the clear title, LEGACY CER changes from 13.33% to 10.00%; reference remains
0%. The faint title remains blank in LEGACY with or without enhancement. In
reference mode its CER changes from 30.00% to 3.33%. Uneven-lighting reference CER
changes from 3.33% to 0%; half-resolution reference remains 0%. No model-mode
default is changed in response to these results. The other ten board fixtures
have paired predictions but insufficient verified labels for CER/WER.

The initial version of the new host audit required a test-only asset-path fix;
its rerun passes. The audit explicitly disables Gradle test caching, since external
logit files are generated between the capture and decode passes. These harness
issues are resolved and do not alter application behavior.
