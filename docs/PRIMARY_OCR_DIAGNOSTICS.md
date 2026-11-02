# Primary OCR research diagnostics

Detailed exports are disabled by default. They require
`SINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK` and
`ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS=true`. CRNN fallback remains lazy; the exporter
never runs an additional recognizer or changes filtering, normalization, decoding,
correction, or structuring decisions. Storage/serialization failures cannot select
a different engine. Export timings include research overhead; compare accuracy with
exports enabled, but measure normal latency separately with exports disabled.

## Build and run

Use a Gradle run configuration in Android Studio, debug variant, with tasks
`:app:assembleDebug :app:assembleDebugAndroidTest` and arguments:

```text
-PSINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK
-PENABLE_OFFLINE_TEXT_CORRECTION=true
-PENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS=true
-PENABLE_PADDLE_OCR=false
-PENABLE_OPENCV_ENHANCEMENT=false
-PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
-PENABLE_OCR_EVALUATION_EXPORTS=false
```

Install the APK without removing data:

```powershell
adb -s 5f4c58c6 install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch the phone icon and import the original image using Gallery / single scan.
Do not rebuild using an ordinary Run configuration that omits these properties.
Compare correction on/off using separate builds and the same source PNG hash.
Ordinary logs contain configuration, counts, decisions and timings, not recognized
text. No device settings need to change for these exports.

The repository has no runnable Gradle wrapper. Use Studio's Gradle configuration,
or an existing compatible local Gradle/JDK installation; do not assume `gradlew`
or `gradle` is on PATH.

## Export and interpret

```powershell
adb -s 5f4c58c6 pull /sdcard/Android/data/com.example.note2snap/files/primary-ocr-diagnostics evaluation/primary-device-exports
```

Use a new destination to preserve earlier exports. Every pipeline scan creates a
UUID directory containing:

- `scan.json`: scan ID, configuration, actual asset hashes (null if unavailable),
  correction-requested/loaded/executed state, decoded source dimensions/hash,
  recognition dimensions, final CCL regions, raw/candidate CCL filtering decisions,
  primary guards, per-line decisions/failures, attempt timings, raw/corrected
  output, suggestions with applied flags/evidence, and final structured note.
- `source.png`: pipeline input before WhiteboardPreprocessor. This is a decoded
  bitmap, potentially already prepared by the gallery/camera path, not proof that
  the original gallery file bytes were identical.
- `recognition.png`: full image in CCL/physical-line coordinates.
- `region-<id>.png` and `line-<id>.png`: retained CCL crops and exact physical crops.

Boxes are [left, top, right, bottom], right/bottom exclusive, in recognition-image
coordinates. Raw CCL component decisions are unpadded boxes, not final region IDs.
Match them spatially. The raw/candidate observer records existing decisions only;
later CCL merging/suppression is reflected by the final `regions` list.

`lines[].lineId` is unique within its scan. `outputIndices` links a physical crop
to `outputs[].outputIndex`; rejected or failed crops can have no outputs.
`paddleRawText` is the Paddle decoder prediction before trimming.
`crnnRawText` is recorded only when fallback actually runs, including blank
decodes. The real CRNN decoder is observed before whitespace normalization.
Injected test recognizers expose only their returned text. No shadow CRNN is run
for successful Paddle lines. Failure fields contain exception types without
potentially sensitive exception messages.

`outputs[].rawText` is the selected engine's normalized output,
`correctedText` is its actual correction result. Suggestions are not necessarily
applied; offsets are UTF-16 and end exclusive. `correction_error` means that line
retained its original text after a correction failure. The final structured note
is recorded separately because structuring can join/reorder lines or make titles;
there is no fabricated one-to-one correspondence to structured blocks.

A report left at `started` or `pending` is incomplete. `failed` preserves the
failure type and any events recorded before it. A killed process or full disk can
leave an incomplete JSON file; preserve it as incomplete evidence rather than
scoring it as a successful empty scan. No automatic cleanup deletes research data.

## Installed APK verification

Before replacing an APK, preserve it using `adb shell pm path` and `adb pull`.
A read-only, dependency-free Java inspector is included:

```powershell
java tools/InspectOcrApk.java installed-before-primary-diagnostics.apk
```

Use JDK 17 or newer. It reads DEX-encoded BuildConfig constants, checks for the CCL
call to SideBorderGeometry, and prints actual uncompressed model/dictionary hashes.
It deliberately omits DEBUG, which uses a runtime initializer. It is not a
general DEX verifier and cannot establish what an older scan executed.
Expected dictionary SHA-256:
`e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6`.
Expected model SHA-256:
`70b2450eed39599af6b996c27a2f1a0ef30eeb49f9f66dd3e74f28f652befc89`.

## Correction limits and missing historical evidence

The 542-entry academic vocabulary and 25 reviewed aliases are not a general English
language model. Words shorter than five letters and all-caps/mixed-case identifiers are
preserved. Dictionary neighbours are suggestions only; reviewed lowercase aliases
can apply automatically, while title-case aliases require review. Valid but wrong
words, omitted words, and non-text crops cannot be repaired reliably this way.
Do not blacklist I/l/1 or replace uncertain handwriting with the expected pangram.

The four supplied historical Paddle predictions would all remain unchanged under
the current corrector. This is expected, but does not prove correction executed in
a historical phone scan. Quantify improvement/worsening only with the actual raw,
corrected and manually transcribed reference records.

At investigation time, `evaluation/run01/records` was missing locally and
`files/paddle-comparisons` was absent on device 5f4c58c6. Restore the entire original
run, including JSONL records, referenced crop/source images, annotations and
reference text. Do not recreate or infer missing predictions. The original
four-sentence image has not yet been identified, so border geometry, current
per-line accuracy and extra-I elimination remain unverified. No filtering
threshold, model, dictionary, CTC decoder or automatic correction rule was changed.


## Focused device tests

After building both debug APKs with the arguments above:

```powershell
adb -s 5f4c58c6 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 5f4c58c6 shell am instrument -w -r -e class com.example.note2snap.recognition.PaddleRecognitionDeviceTest,com.example.note2snap.recognition.PaddlePrimaryDeviceTest,com.example.note2snap.recognition.PrimaryOcrDiagnosticsDeviceTest com.example.note2snap.test/androidx.test.runner.AndroidJUnitRunner
```

The small real-Paddle diagnostic test retains a labelled research export from
`paddle_reference/fractional.png`. The full configured-pipeline test additionally
requires `-e runFullPrimaryFixture true` and retains `board_test_5.jpg` evidence.
Both are fixtures, not the four-sentence gallery image. Tests never save notes or
edit the user's gallery.

The first full-fixture run was stopped after the 180-second command timeout. It
reached CRNN fallback; its last log was CRNN invoke on a 53x28 crop, with no completion.
The partial scan is retained with status `started`, not scored as successful.
Run the full-fixture test only as a separately bounded investigation. The ordinary
class selection above skips it. Native CRNN stalling remains unresolved.
