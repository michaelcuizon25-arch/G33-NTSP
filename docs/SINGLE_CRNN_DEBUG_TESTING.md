# Controlled Single scanner preprocessing tests

Branch `debug/single-crnn-preprocessing`, based on `fix/crnn-dynamic-output` at
`d90f403`. The dynamic-output fix is unchanged. Model assets, custom CCL,
physical-line segmentation, both preprocessing transforms, decoder and rule
engine are unchanged. No experimental branch is pushed or merged.

## Phone instructions

1. Install `app/build/outputs/apk/debug/app-debug.apk` and open the scanner.
2. Tap **Single** to choose Single capture, then **long-press the Single tab**
   while the scanner is idle.
3. Choose **LEGACY (default)** or **SIMPLE_HTR_REFERENCE**, then tap **Use mode**.
   Cancel keeps the current choice. The confirmation toast names the chosen mode.
4. Capture one photo in Single Camera, or open Gallery and select exactly one
   image. Both paths use the selected setting. Selecting multiple Gallery images
   continues to use the existing ML Kit Batch Gallery path.
5. For a controlled comparison, import the same saved image in Single Gallery
   once per mode. Camera captures can vary in framing/lighting.
6. Long-press Single and choose LEGACY to restore the default.

The choice persists across scanner/app recreation until changed. If the preference
is absent or invalid, LEGACY is used. A mode snapshot is captured when each Single
scan begins, before file/quality work, so an ongoing scan cannot change modes.
The option is unavailable while scanning. It also applies to the existing Single
tutorial sample, which uses the same scan entry point.

The selector is installed only when the application's `FLAG_DEBUGGABLE` is set.
Release builds have no debug long-press selector and always resolve LEGACY,
even if a reference-mode preference was restored or left from a debug build.
The normal Single/Batch tab clicks are unchanged; no layout or unrelated UI was
modified.

## Confirming which mode was used

```sh
adb logcat -v threadtime NOTE2SNAP_PIPELINE:V CRNN_DIAGNOSTICS:V '*:S'
```

At scan start, look for:

```text
Single scan source=CAMERA preprocessing=SIMPLE_HTR_REFERENCE
Single scan source=GALLERY preprocessing=SIMPLE_HTR_REFERENCE
```

Existing per-line diagnostics record `requestedMode`, `actualMode`,
`referenceWidth`, and `fallbackReason`. A reference-width crop above 512 still
uses the existing LEGACY fallback and reports that fact. Its actual mode can
therefore differ from the selected mode for that line.

The dynamic-output runtime should continue to report native output bytes 40,960
and resolved [1,128,80], retaining the complete CTC sequence.

## Wiring and preservation

The latest branch already routes Single Camera and Gallery through the common
`processImageUri` path and `WhiteboardRecognitionPipeline`. This change adds an
explicit mode parameter to the pipeline constructor and passes the persisted
debug setting through `SingleScanPipelineFactory` into `CrnnLineRecognizer`.
Existing pipeline constructors still default to LEGACY. Application context and
the existing `finally`-based pipeline close are preserved.

The preferences live in a separate `single_crnn_debug` store and are read only
by Single scanner control/recognition code. Batch paths do not consume this mode.

Google ML Kit preservation was checked against `d90f403`:

- Both Batch Camera and Batch Gallery methods and their shared ML Kit async
  recognition helper are byte-for-byte unchanged.
- All ML Kit imports in `ScanFragment.kt` are unchanged.
- `app/build.gradle.kts`, the ML Kit implementations and Android manifest are
  unchanged, retaining dependencies, initialization metadata and functionality.
- No existing ML Kit code or tests were removed or disabled.

## Regression tests

Eight new native-graphics Robolectric tests cover:

- LEGACY default, persisted selection and resetting it.
- Release ignoring stored debug selections and rejecting writes.
- Invalid preference fallback and immutable scan snapshots.
- Both CAMERA and GALLERY, in both modes, forwarding the setting through the
  shared pipeline into the **actual CRNN preprocessing**. A test runtime captures
  every inference input and compares it with the requested transform.
- Pipeline constructor retaining its LEGACY default.
- Long-press dialog selection and preservation of the normal tab click.
- Cancel behavior and blocking the selector while a scan is busy.
- Absence of a release debug long-press.

The test runtime substitutes only model execution; it does not use ML Kit or
replace the production recognizer. Existing dynamic-output/native-logit and
Python preprocessing parity tests continue to run.

Validation: all **56 custom-pipeline tests passed**, including the eight new
tests, and the debug APK built successfully. The full suite ran **57 tests**:
56 passed and the unchanged `MlKitRecognitionPipelineTest` failed because
`MlKitContext` was not initialized. The separate custom-suite run excludes that
test only through an external scratch Gradle init script; no repository test was
disabled. The merged debug manifest retains `android:debuggable="true"` and the
ML Kit OCR initialization metadata.

Physical phone checks remain: opening the debug selector, testing a Single Camera
capture and identical Single Gallery imports in both modes, checking logged mode
and fallback, and smoke-testing Batch Camera/Gallery with ML Kit. No Android
device is attached to this workspace.

Modified files: `ScanFragment.kt`, `WhiteboardRecognitionPipeline.kt`; added
`SingleScanPreprocessing.kt`, `SingleScanDebugControls.kt`,
`SingleScanPreprocessingTest.kt`, and this document.
