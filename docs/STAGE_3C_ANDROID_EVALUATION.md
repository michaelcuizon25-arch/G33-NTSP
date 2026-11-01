# Stage 3C: Android evaluation runbook

Prepared on `feature/ocr-accuracy-improvements`, based on Stage 3B commit
`64a91bc6b315e6779122035406c5967e0c144efa`. No production code, feature default,
model, Gradle dependency, preprocessing, engine selection, CTC or note behavior
changes are part of Stage 3C. No device was attached during preparation. This is
a procedure and tooling package, not a report of successful Android OCR.

## 1. Android Studio setup and comparison build

1. Fetch the feature branch, then select `feature/ocr-accuracy-improvements` in
   Android Studio's branch menu. Open the repository root containing
   `settings.gradle.kts`. Verify a clean/preserved working tree before local edits.
2. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, select
   **JDK 17** and a local **Gradle 8.5** distribution. Install Android SDK platform
   **34** via SDK Manager. Existing AGP is 8.1.1 / Kotlin 1.8.10 / minSdk 24.
   This checkout has `gradle-wrapper.properties` but no `gradlew`, `gradlew.bat` or
   wrapper JAR, so `./gradlew` is not a usable command. Do not upgrade the build
   system or generate/commit a wrapper as part of this evaluation.
3. Let the initial Gradle sync fetch development dependencies. Device inference
   needs no downloads. Use a physical Android API 24+ device, enable USB debugging,
   connect USB and accept the authorization prompt. Confirm `adb devices` shows
   `device`, not `unauthorized`. An emulator can run functional tests but is not a
   substitute for camera, latency, thermal or memory measurements on the target phone.
4. Select the **debug** build variant. In **Run > Edit Configurations > + > Gradle**,
   name a configuration **Install OCR comparison**. Select this project, set
   Tasks to `:app:installDebug` and Arguments to:

   ```text
   -PENABLE_PADDLE_OCR=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
   ```

   If your Studio version has a single **Tasks and arguments** field, combine the
   task and arguments there. Run this Gradle configuration and then **tap the
   installed app icon on the device**. An ordinary Android App Run configuration
   may rebuild without these properties and restore Paddle=false; do not use it
   for the comparison run unless it passes the same properties.
5. Verify generated `app/build/generated/source/buildConfig/debug/com/example/note2snap/BuildConfig.java`
   contains `ENABLE_PADDLE_OCR = true` and the other three flags are false.
   Do not edit this generated file. CRNN is still the source of all note text.
6. In Studio Logcat, select this device/process and filter `tag:NOTE2SNAP_PERF`.
   Enable explicit timing logs with `adb shell setprop log.tag.NOTE2SNAP_PERF DEBUG`.
   Paired text is stored in private files, not in the content-free performance tag.

Equivalent Terminal command, with Gradle 8.5 and SDK `platform-tools` on PATH:

```sh
gradle :app:installDebug -PENABLE_PADDLE_OCR=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
```

On Windows, invoke your installed `gradle.bat` (or its full path); Python commands
below use `python`, which can be replaced with `py -3`. Add `--offline` to Gradle
commands after development dependencies are cached. Building offline and scanning
offline are separate checks.

**Baseline/default restore** uses the same task with Paddle explicitly false:

```sh
gradle :app:installDebug -PENABLE_PADDLE_OCR=false -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
```

All defaults in repository configuration remain false. Keep per-run flags in the
Gradle run configuration/command, not in shared project `gradle.properties` or
user-global properties that might silently change later builds. Baseline and
comparison APKs have the same app ID and should be tested sequentially, not assumed
to be installed side by side. Installing a debug APK over a differently signed
release can fail; use a dedicated device/profile, preserving notes before any
uninstall. No uninstall or data wipe is performed by the tooling.

## 2. Native correctness gate before benchmarking

Create a second Gradle Run Configuration, **Paddle native parity**, with Task
`:app:connectedDebugAndroidTest` and Arguments:

```text
-PENABLE_PADDLE_OCR=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false -Pandroid.testInstrumentationRunnerArguments.class=com.example.note2snap.recognition.PaddleRecognitionDeviceTest
```

Equivalent terminal command:

```sh
gradle :app:connectedDebugAndroidTest -PENABLE_PADDLE_OCR=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false -Pandroid.testInstrumentationRunnerArguments.class=com.example.note2snap.recognition.PaddleRecognitionDeviceTest
```

This runs the Stage 3B Android OpenCV tensor and ORT-output parity fixtures, including
repeated inference and close. Inspect `app/build/reports/androidTests/connected/`
and Logcat. If it fails, **stop device benchmarking and retain logs**; do not relax
numerical tolerances or select Paddle predictions to make the test pass. Host
Paddle/ONNX parity from Stage 3B is not proof of Android JNI parity.

## 3. Controlled corpus and ground truth

Use `docs/ocr-evaluation/sample-plan.tsv` to plan the samples. Its writing prompts
are **not ground truth**; transcribe what is actually visible in each captured
physical line before viewing engine predictions, ideally with a second reviewer.
Include clear, blank/erased, faint, cursive, numbers, formulas, computer-science
terms, diagrams and mixed notes; include at least five captures per category and
several writers. Save original photos, image IDs, resolutions and orientation.
Do not count repeated/synthetic versions of one sentence as independent writers.

Copy `run-manifest-template.tsv` for each experiment. Fill device model, Android
version, ABI, app commit, workflow, image ID, category, CRNN preprocessing mode,
all flags, offline settings, quality gate result, expected line count, observed
paired line count, record filenames and log/memory files. Keep default CRNN mode
**LEGACY** for the first experiment; do not pool SimpleHTR and legacy runs.

For exact paired inference, Stage 3B already passes the **same physical-line bitmap**
from CRNN's existing CCL/line segmentation to Paddle, with the same region ID and
bounding box. Do not run a second independent detector/segmenter for Paddle. The
engines then perform their own required tensor preparation. Camera and gallery
captures may have different pixels; paired equality is within each scan, not across
two different captures. Camera capture applies its existing crop/JPEG quality gate;
gallery import retains its own existing validation behavior.

## 4. Record a camera or gallery run

Use a new local run directory, e.g. `evaluation/camera-clear-01`; keep evaluation
artifacts outside Git or leave them untracked. The examples below run from the
repository root. Use `--adb /path/to/platform-tools/adb` and `--serial SERIAL` on
`collect` if needed; use `adb -s SERIAL` on shell/logcat commands for multiple devices.

1. If previous comparison files exist, snapshot them first:

   ```sh
   python tools/evaluate_ocr.py collect --output evaluation/before/records
   ```

   If this is the first scan and the device folder does not exist yet, no baseline
   snapshot is needed. The collector is read-only on the phone. It never deletes
   notes, images, logs or comparison files.
2. Enable timing logs, force-stop for a process-cold run, and start a clean log
   capture in a separate Terminal. These log commands are for a dedicated test run:

   ```sh
   adb shell setprop log.tag.NOTE2SNAP_PERF DEBUG
   adb shell am force-stop com.example.note2snap
   adb logcat -c
   adb logcat -v threadtime NOTE2SNAP_PERF:I "*:S" > evaluation/camera-clear-01/perf.log
   ```

   Create the local run directory before redirecting. Leave this Terminal running.
   Launch via device icon, not an unconfigured Studio App Run rebuild.
3. For **camera single**, photograph the planned board with the normal guide crop.
   For **gallery single**, import a known original test image and retain that file
   alongside its image ID. Allow processing to finish, inspect/edit/save the note
   normally, and verify the source image/diagram is still available. Never use the
   editable note's corrected text as transcription ground truth.
4. Record quality rejection, no-text/zero-line outcomes, errors and cancellation
   in the manifest even if no JSONL file is created. A blank whiteboard can be
   rejected by quality checks or produce no CCL regions. That is not a runtime
   hallucination result, and must not be silently dropped from dataset coverage.
5. Stop Logcat with Ctrl+C after the run. Collect files into a fresh local directory:

   ```sh
   python tools/evaluate_ocr.py collect --output evaluation/camera-clear-01/records --baseline evaluation/before/records
   ```

   Omit `--baseline` if there were no prior records. Otherwise it excludes unchanged
   old files and rejects a baseline file that was still being written. Collect only
   after processing completes. Do not overwrite a snapshot with a still-changing
   session. Each comparison scan normally creates one UUID-named JSONL file; rows
   are physical lines. A zero-record run stays in the manifest, not as invented
   empty model output. If collection contains multiple new sessions, map their
   filenames to individual scans before labeling; do not guess source/category.
6. Create the annotation sheet:

   ```sh
   python tools/evaluate_ocr.py template --records evaluation/camera-clear-01/records --output evaluation/camera-clear-01/samples.tsv
   ```

   Open the UTF-8 TSV in a spreadsheet or text editor. Import all columns as text
   to preserve leading zeros, formulas and Unicode. Preserve `sample_id`. Hide the
   prediction columns while transcribing. The sheet includes crop coordinates,
   dimensions and per-engine processing times in milliseconds.
   Fill `source` (`camera`, `gallery`, `tutorial` or `direct_probe`), `category`,
   `ground_truth` and set `transcribed=yes` only after manual review. For verified
   blanks/diagram-only crops, leave ground truth empty and use category `blank` or
   `diagram` with `transcribed=yes`. Unreviewed empty cells remain `transcribed=no`.
   `original_text` is optional human reference/provenance text; only `ground_truth`
   is scored. Prediction columns are for convenient inspection, but the scorer
   always reads original predictions from JSONL, ignoring spreadsheet edits to them.
7. Calculate accuracy and timing summaries:

   ```sh
   python tools/evaluate_ocr.py score --records evaluation/camera-clear-01/records --labels evaluation/camera-clear-01/samples.tsv --output evaluation/camera-clear-01/scores.json
   python tools/evaluate_ocr.py perf --log evaluation/camera-clear-01/perf.log --output evaluation/camera-clear-01/performance.json
   ```

   Output/template files are created exclusively to protect manual work. Use a new
   filename for a revised score. Retain raw JSONL, labels, original images and logs.
   For visual annotation, Studio **Device Explorer** can open/save
   `/data/data/com.example.note2snap/files/paddle-comparisons`. Row `box` coordinates
   refer to the bounded recognition image, not necessarily the full original photo;
   inspect region ID and image dimensions. If crop provenance is ambiguous, run a
   **separate** audit with `-PENABLE_CRNN_DIAGNOSTIC_EXPORTS=true` and inspect the
   existing CRNN crop/review exports. Keep those export-heavy runs out of timing
   baselines. Comparison logs themselves do not save line images or source paths.

### Metric definitions and coverage

CER = character edit distance / reference characters; WER = token edit distance /
reference words. The scorer uses exact Unicode codepoints/case/whitespace for CER
and whitespace tokenization for WER, without stripping punctuation or rewriting
formulas. Corpus scores use summed errors and denominators, not a mean of per-line
percentages. It reports per-source/category results and individual edit counts.
Blank-only CER/WER are undefined (`null`), with empty-reference false-positive rate
and insertion counts reported instead. Mixed-corpus scores including blank
insertions and separate nonblank scores are both provided. Formula tokenization
is whitespace-based and must be interpreted alongside character errors.

Paddle exceptions are counted as failures and excluded from **paired** accuracy;
CRNN accuracy over all labeled rows is also shown so failure exclusion is visible.
Do not reinterpret an exception or a missing CCL crop as an empty prediction. Track
segmentation/quality-gate misses separately in the manifest. Manually record full
image reference/expected line counts to expose text missed by both engines; this
line scorer alone cannot calculate end-to-end document recall. Mixed Paddle model
hashes are rejected. Do not pool different flags, CRNN modes or device protocols.

## 5. Investigate blank hallucinations separately

Run the Stage 3C direct-input probe **after** the native parity gate passes. Create
an **OCR blank probes** Gradle run configuration with Task
`:app:connectedDebugAndroidTest`, the same four feature properties as above, and:

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.example.note2snap.recognition.OcrBlankProbeDeviceTest
```

The test creates white 320px/1280px crops, uniform off-white, near-white noise,
a small dot and an unlabeled rectangle. It sends each **identical bitmap** through
the real CRNN tensor/runtime/unchanged beam decoder and Paddle tensor/runtime/CTC.
It bypasses quality checks/CCL only inside this test to separate model behavior
from segmentation. Test-only reflection calls the existing private CRNN decoder;
there is no production API/decoder modification. No confidence threshold or
automatic output suppression is introduced. A completed probe means execution
worked, not that recognition was correct.

Records are in `files/paddle-comparisons/direct-probes-UUID.jsonl`; reference PNGs
are separately in `files/paddle-probe-images/direct-probes-UUID/` and can be saved
via Studio Device Explorer. Collect using the same script, inspect PNGs, manually
mark empty references, and score separately with `source=direct_probe` and notes
identifying each `caseName`. Keep direct-model timings separate from UI pipeline
runs. Record any new strings, blank outputs or errors; do not assume the host's
previous `EMPY`/`EY` outputs will occur on the phone. Tiny marks/diagrams test
non-text false positives, not handwriting accuracy. This does not justify enabling
Paddle or comparing uncalibrated confidence numbers across models.

## 6. Offline, workflow and feature matrix

For the first offline check, install the already-built debug APK, then manually
turn on airplane mode, turn Wi-Fi off (some phones retain it in airplane mode),
and verify mobile data is off. USB debugging may stay connected. Force-stop and
launch again, then perform camera single and gallery single scans, edit/save/reopen
notes, inspect diagrams, and run the native test/probes. Never count cached text
as a fresh offline inference result. Preserve logs/screenshots and failures.
If a pristine-install check is required, use a dedicated empty test device/profile;
back up notes before any manual uninstall. No runtime model download should occur.

| Workflow | Expected engine/output | Evaluation action |
| --- | --- | --- |
| Camera single | CRNN note text + optional Paddle shadow | Pair identical line crops; record camera quality/crop/orientation |
| Gallery single | CRNN note text + optional Paddle shadow | Use saved originals; include rotated and high-resolution images |
| Tutorial | CRNN note text + optional Paddle shadow | Smoke-test bundled tutorial; do not include tutorial as independent writer data |
| Camera batch | ML Kit | Smoke-test order/diagrams/offline behavior; no Paddle JSONL expected |
| Gallery batch | ML Kit | Same; compare Stage 2.5 hooks independently, not as CRNN/Paddle accuracy |

Start with enhancement/correction/exports all false. Then, in **separate labeled
runs**, test the OpenCV/correction combinations false/false, true/false, false/true,
true/true. Keep Paddle comparison true. Both engines receive the same Stage 1
line bitmap; raw JSONL predictions are **before** offline correction. Verify edited
notes/diagrams and correction behavior separately; do not score corrected text as
raw OCR. Reinstall with explicit flags and verify BuildConfig each time. Test
cancel/back navigation, rotate/recreate the screen, switch modes, return from
background and repeat gallery/camera recognition. A normal batch scan must remain
ML Kit and must not unexpectedly produce a Paddle comparison record.

## 7. Performance, memory and repeated scans

Record device/OS/ABI, charging state, temperature, resolutions, line counts, flags
and process state. Keep the same device/conditions and source files. Take at least
5 process-cold runs and 20 repeated runs per workflow; do not mix first launch,
first line, subsequent lines and repeated scans into one latency number. The
existing pipeline creates new model sessions per scan, so a repeated app scan can
still pay model initialization. Report both per-scan initialization and warm
per-line invocation.

- `MODEL_INITIALIZATION`: existing CRNN creation/allocation stage.
- `PADDLE_INITIALIZATION`: first-use ORT session/asset validation.
- `CRNN_INFERENCE`: per-line invocation events and aggregate stage.
- Comparison JSONL: CRNN preprocessing/inference/decode and Paddle preprocessing/
  inference-including-cold-init/decode for each physical line.
- `PADDLE_INFERENCE`: invocation-only stage after ORT session creation.
- `PREPROCESSING`, `CCL`, `SEGMENTATION`, `OPENCV`, `OFFLINE_CORRECTION` and flow
  `TOTAL`: existing image/pipeline/per-scan context. ML Kit batch retains its own
  stages and BATCH_PAGE events.

The scorer separates the first successful Paddle row per JSONL (which **may include
cold initialization**) from subsequent successes. Use NOTE2SNAP_PERF initialization
stages to characterize cold cost; do not compare that first-row total directly
with invocation-only CRNN or blindly subtract unrelated event aggregates. Record
IDs do not contain PERF session IDs, so capture one scan/log at a time for alignment.
The PERF parser groups by flow/kind/stage; nested stages are inclusive and cannot
be summed. STAGE event percentiles describe aggregate events, not individual call
percentiles; `mean_per_call_ms` uses their summed call counts. Per-line CRNN events
and JSONL are the source for inference percentiles. Reports are not frame-time proof.

Capture memory after launch, after first scan and after scans 5/10/20:

```sh
adb shell dumpsys meminfo com.example.note2snap > evaluation/run01/memory-launch.txt
adb shell dumpsys gfxinfo com.example.note2snap reset
# Perform the planned scans, then:
adb shell dumpsys meminfo com.example.note2snap > evaluation/run01/memory-after20.txt
adb shell dumpsys gfxinfo com.example.note2snap > evaluation/run01/frames-after20.txt
```

Use distinct files for each checkpoint. In Studio **Profiler**, attach the debuggable
app, record CPU/System Trace around capture/import/recognition, and inspect main
thread stalls. Inspect Java/native memory and PSS trends; note GC and image sizes.
Debug/profiler overhead affects latency, so collect a separate unprofiled timing
run. Do not treat a one-time increase from model/library loading as a leak. Repeated
post-scan growth, allocation failures, hangs, corrupted crops, missing records,
ANRs or crashes are failures to investigate. Stop at the first native parity error
or corrupted prediction record, preserving the reproducible input. Measure p50/p95,
errors, coverage and memory trend rather than declaring a pass from one scan.

Finally restore all flags false with the baseline install command, and restore the
timing tag to `WARN` if desired. Do not commit/push/merge evaluation changes without
approval.

## 8. Preparation validation (host only)

No Android device was attached (`adb devices` returned an empty list). Native
parity/probe execution, camera/gallery acceptance, memory, repeated-device stability
and airplane-mode scanning are pending; no results for them are fabricated.
Host script tests use explicitly synthetic records and do not demonstrate OCR accuracy.

Run tooling tests:

```sh
python -B -m unittest discover -s tools -p test_evaluate_ocr.py -v
```

The available Gradle host suite and debug/release/Android-test assembly were run
with all feature defaults disabled. Results and differences from Stage 3B are in
`docs/ocr-evaluation/preparation-validation.json`. The baseline already has 14 unit
failures; leave their assertions unchanged. These failures still make the combined
Gradle invocation return nonzero even when APK assembly succeeds.
