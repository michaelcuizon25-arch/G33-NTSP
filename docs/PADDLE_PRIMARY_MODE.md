# Explicit Paddle-primary single-scan mode

Prepared on `feature/ocr-accuracy-improvements`, after dictionary LF fix
`ea2a5827c7f237f837b8c037e5c004d0502dfaf8`. The implementation and results below
were prepared before committing; phone validation remains pending.

## Selection and rollback

New build property (case-sensitive, invalid values fail Gradle configuration):

- `SINGLE_SCAN_RECOGNITION_MODE=CRNN_ONLY` — ordinary default, unchanged CRNN note
  generation. Existing optional `ENABLE_PADDLE_OCR=true` still enables Stage 3B
  shadow comparisons in this mode.
- `SINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK` — explicit
  experimental primary mode. Paddle supplies usable text; CRNN is lazy fallback.
  `ENABLE_PADDLE_OCR` is the older comparison flag, not the primary selector.
  Primary mode takes precedence and does not run shadow comparison/dual OCR.

In Android Studio, use JDK 17 / local Gradle 8.5 / SDK 34 and debug build variant.
The repository still has no runnable Gradle wrapper. Under **Run > Edit
Configurations > + > Gradle**, set Task `:app:installDebug`, and Arguments:

```text
-PSINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK -PENABLE_PADDLE_OCR=false -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
```

Run that Gradle configuration, then tap the app icon on the phone. Do not rebuild
through an ordinary App Run configuration without the same properties. Verify
`app/build/generated/source/buildConfig/debug/com/example/note2snap/BuildConfig.java`
contains the selected `SINGLE_SCAN_RECOGNITION_MODE` string.

Equivalent Windows PowerShell (Gradle 8.5 on PATH):

```powershell
gradle.bat :app:installDebug -PSINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK -PENABLE_PADDLE_OCR=false -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
```

Rollback uses the same install command with `-PSINGLE_SCAN_RECOGNITION_MODE=CRNN_ONLY`.
Keep all other flags false for the initial baseline. Rollback replaces the APK
without clearing notes/data, assuming the same signing key. Do not uninstall to
resolve a signing mismatch without preserving notes. Source defaults remain CRNN
and all existing enhancement/correction/comparison/export defaults remain false.
Enable OpenCV or correction independently only for separate validated runs.

## Dictionary first

The official 436-entry dictionary remains UTF-8 without BOM, LF terminated,
1,416 bytes, SHA-256:

`e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6`

Root `.gitattributes` pins its line endings. A Windows checkout left at 1,852 CRLF
bytes has hash `8459e5659185f87d62195cb495f2677493bade134182bfb8c54bf21757b28cdf`
and must be repaired before building, not accepted as an alternate hash. Follow
`docs/PADDLE_DICTIONARY_INTEGRITY_FIX.md` to refresh only this file safely after
checking for local edits. Verify your APK itself before installation:

```powershell
py -3 -c "import zipfile,hashlib; z=zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk'); b=z.read('assets/ocr/paddle/ppocrv5_en_dict.txt'); h=hashlib.sha256(b).hexdigest(); print(len(b),h); assert h=='e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6'"
```

A lightweight `verifyPaddleDictionary` pre-build task now fails before packaging
if the actual bytes do not match the original official digest. It does not rewrite
files or accept the Windows hash. No runtime integrity checks, pinned hashes,
vocabulary or ONNX weights were changed.
A bad dictionary in primary mode causes CRNN fallback, with the original integrity
exception in Logcat. Fallback availability must not be mistaken for a successful
Paddle installation.

## Recognition path

Camera, gallery and tutorial single scans use the shared selector in both
`SingleScanPipelineFactory` and the context-based `WhiteboardRecognitionPipeline`.
Camera/gallery batch paths remain unchanged ML Kit; no batch engine replacement.

1. Existing optional image enhancement, CCL classification and coordinates remain.
2. Primary mode honors CCL `TEXT`, positive pixel area and valid region bounds;
   `NON_TEXT` regions never go to either text engine and remain in the pipeline's
   diagram result/original source handling.
3. A primary-only negative image guard rejects uniform crops, isolated tiny marks,
   very thin separators, low-amplitude noise distributed throughout the image,
   and clear wide empty frames. It does not use model probability. Checks run on
   the region before segmentation and on each actual line afterward. Localization
   distinguishes faint strokes from uniformly distributed low-contrast noise.
4. Existing physical-line segmentation runs once. Each accepted crop retains the
   original region ID and global bounding box. The validated Paddle BGR/OpenCV
   resize, NCHW normalization, dictionary and CTC decoder are reused unchanged.
5. Nonblank Paddle text containing letters/numbers or mathematical operators is
   retained, unless it contains invalid control/replacement characters. Punctuation-
   only noise is unusable and falls back. Whitespace is normalized as in single-line
   CRNN output. Low Paddle probability alone does not reject valid handwriting.
6. On exception, native linkage failure, unsupported dimensions, blank prediction
   or unusable text, the exact physical crop is passed to CRNN. A private provider
   on the existing CRNN recognizer prevents re-segmentation or coordinate shifts.
   CRNN tensor preparation and beam CTC decoding are untouched. No raw Paddle/CRNN
   confidence comparison or automatic maximum-confidence choice exists.
7. CRNN output then follows the existing optional text correction, structurer,
   review/edit/save and image paths. If both engines fail on every available line,
   an error propagates instead of claiming successful empty recognition. Partial
   successful lines remain available as before. Blank fallback predictions remain
   blank; existing UI readability validation still applies.

One Paddle session is reused across lines for each scan. CRNN is never constructed
on a successful Paddle-only scan. Factory failures are cached; the existing ORT
runtime already caches native session initialization failure. Segmentation metrics
are not counted twice for fallback. All recognition work is on a worker dispatcher;
cancellation propagates without triggering fallback. Owned line crops are recycled
only after both possible engines finish. Closing releases only initialized engines
and is idempotent. No cloud service, runtime download, dependency or model change.

## Observability

Use Studio Logcat `tag:PADDLE_PRIMARY` for content-free decisions:

- `engine=paddle reason=usable_text`
- `engine=crnn_fallback reason=...`
- `engine=skip reason=uniform_blank` (or the other guard reasons)

Exceptions retain stack traces, including dictionary integrity failures. The
existing `NOTE2SNAP_PIPELINE` tag logs `Single scan recognitionMode=...`; enable
`NOTE2SNAP_PERF` as in Stage 3C for Paddle initialization/preprocessing/inference/CTC
and on-demand CRNN initialization/inference. A successful Paddle-only scan must
not have CRNN initialization/inference. Blank-guarded scans initialize neither.

Stage 3C paired JSONL comparisons remain available in `CRNN_ONLY` with
`ENABLE_PADDLE_OCR=true`. They are not generated by primary mode, which intentionally
avoids running both engines on successful lines. Existing CRNN-only diagnostic
exports remain unchanged; primary fallback exports have CRNN per-line/model data
but do not have the pipeline's CRNN-only full-source export hook. Do not mix these
modes' timings or infer successful Paddle use from the mere existence of a note.

## Phone gates and remaining limitations

No Android phone is attached to the implementation environment. Native execution,
camera/gallery acceptance, latency, native memory, offline first launch and saving
on an actual phone are not claimed. Before regular primary use:

1. Run `PaddleRecognitionDeviceTest` (dictionary and native parity) with the primary
   mode Gradle property above. Stop on integrity/native parity failure.
2. Run the new `PaddlePrimaryDeviceTest` using `:app:connectedDebugAndroidTest` and
   `-Pandroid.testInstrumentationRunnerArguments.class=com.example.note2snap.recognition.PaddlePrimaryDeviceTest`.
   It checks Android blank/noise/frame guards and real Paddle handwriting without
   allowing CRNN construction. These tests explicitly instantiate primary mode
   and do not change the ordinary default.
3. Perform real single camera and gallery scans with clear/faint/cursive writing,
   numbers, formulas, technical terms, drawings, rotation and multiple resolutions.
   Verify decisions, coordinates, note editing/saving, preserved source/diagrams and
   repeated scanning. Repeat in airplane mode with Wi-Fi/mobile data off.
4. Verify fallback behavior and subsequent recovery using the unit coverage and
   phone error logs; do not corrupt the dictionary, alter model hashes or change
   trained assets just to force a phone fallback. Confirm CRNN_ONLY rollback.
5. Compare labeled CER/WER and memory/time before deciding to change any default.

Negative image heuristics do not prove semantic handwriting validity. Complex
misclassified diagrams, textured boards and smudges can still hallucinate; clear
frame checks can also reject unusual boxed/square writing. This is a deliberately
opt-in safeguard set, not a universal blank/non-text classifier. Very faint ink
above grayscale 230 can still be missed by the *existing* segmentation threshold;
that behavior is unchanged. The existing final readability rule also requires
three alphanumeric characters somewhere in recognized output, so very short notes
and formula-only content retain that existing UI limitation. No structuring or
segmentation rules were changed to hide those problems.

Host validation results are recorded in `docs/paddle-primary/validation.json`.
Default builds remain CRNN_ONLY. No commit, push, merge or LiteRT work is part of
this implementation.

## Recorded host results

The opt-in primary debug and Android-test APK builds pass, with 10 focused primary
tests passing. The restored ordinary build passes debug/release/Android-test APK
assembly. Full unit results: **132 passed, 14 unchanged existing failures, 1 skipped**
versus baseline 122/14/1; no new failures. All 13 Python tooling/checkout tests pass.
The combined Gradle task returns failure solely for those 14 existing test failures.
The isolated temporary-project test correctly refuses the bad Windows CRLF digest
before packaging; the real repository dictionary was never modified. Both final
default application APKs contain the exact official dictionary and unchanged ONNX
model. Generated debug and release configuration is restored to CRNN_ONLY and all
optional flags false. Native device tests are compiled only; none were executed.
