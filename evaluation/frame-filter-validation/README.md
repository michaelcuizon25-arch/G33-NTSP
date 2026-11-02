# Captured whiteboard validation — 2026-11-02

Branch: feature/ocr-accuracy-improvements. All existing local modifications retained.
No commit, push, merge, reset, stash, uninstall or data clearing was performed.

## Evidence and correction

The original source SHA-256 matches the old export exactly:
236b9f79d837acbe5ef9ee08f17b01a13ba324e247466afb161a808ef561f9c6

Before: evaluation/next-primary-run/90bc382b-9eb9-4da4-993b-bfd729ed6038.
Final: device-final/f7f6bf3a-63a8-4c59-bc7d-f8bcf53a9e5b.
Final revision: frame-track-v1.1+side-border-587e0e6.
Source 4032x3024; recognition 1800x1350. Model and dictionary hashes are recorded in validation.json.

The inset frame at about x110 bypassed the existing 12px outer-edge filter.
Independent source-image edges, neutral metal strip, groove and long-track continuity
now verify a frame before individual foreground components are compared against surrounding
frame profiles. The final replay rejected 33 raw frame pieces. It retained all four
handwritten rows. It does not blacklist I/l/1 or use a CRNN blank as rejection evidence.
Ambiguous marks are retained. See ../../docs/FRAME_TRACK_FILTER.md for thresholds and limits.

The structurer checked the following line for an all-caps heading boundary but did
not protect the preceding line. The same existing heading criterion now protects
that preceding boundary, with explicit sentence connectors still allowed to wrap.

## Before and after

| Physical line | Before Paddle raw (trimmed) | Final Paddle raw (trimmed) |
| --- | --- | --- |
| 1 | The quck brown fo jumps ver e | The quck brown fo jumps ver e |
| 2 | THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG | THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG |
| 3 | he k  os | he k  os |
| 4 | THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG | THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG. |

All final selections are Paddle; no fallback was needed for these four lines.
Correction executed with zero suggestions or word changes. Whitespace normalization
produces "he k os". Lowercase recognition errors remain; no invented words were added.
Line 4 gained a correctly recognized terminal period after frame removal.

Detected regions: 8 -> 4. Nonblank outputs: 7 -> 4. False I entries: 3 -> 0.
Structured blocks: 6 -> 4; physical rows 2 and 3 remain separate and order is preserved.
Final physical boxes: [264,505,1151,587], [261,601,1318,666],
[135,697,1305,805], [262,799,1391,879].
Rows 2 and 4 no longer include left-frame padding; row 3 conservatively retains some
ambiguous left-frame evidence. Original source and preprocessing pixels are unchanged.

## Validation

- Broader targeted JVM run: 55 passed.
- Final focused frame/edge/structuring run: 16 passed.
- Full JVM suite: 153 passed, 14 failed, 1 skipped. The 14 existing CRNN
  diagnostic/structuring failures remain; this is not a green full suite.
  Full suite preceded the final performance-only detector revision.
- Debug and instrumentation APK builds succeeded.
- Device 5f4c58c6: final original-source production-pipeline replay passed.
  Companion instrumentation: 8 passed, 1 opt-in full-fixture test skipped.
  Combined final device validation: 9 passed, 1 skipped.
- Companions cover native CRNN fallback under injected Paddle failure, real offline
  correction, Paddle tensor parity/dictionary/packaging and isolated in-memory
  note insert/edit/save/reload. No user notes were modified.
- Manual editor UI interaction was not verified. Production DB migration was not changed.
- git diff --check passed; model assets and CTC decoders were not modified.

Cold detector measurements varied: 618ms (v1), 973ms (final v1.1).
Final whole research-export pipeline measured 17.908s. Export IO and cold native
initialization are included; do not infer a speedup. Four extra recognition attempts
(one blank Paddle+CRNN and three false I results) are avoided. Broader timing and
frame/handwriting corpus coverage remain necessary.

## Retest

Run these Gradle tasks through Android Studio's Gradle runner (this checkout has no wrapper):

```text
:app:assembleDebug :app:assembleDebugAndroidTest
-PSINGLE_SCAN_RECOGNITION_MODE=PADDLE_PRIMARY_WITH_CRNN_FALLBACK
-PENABLE_OFFLINE_TEXT_CORRECTION=true
-PENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS=true
-PENABLE_PADDLE_OCR=false
-PENABLE_OPENCV_ENHANCEMENT=false
-PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
-PENABLE_OCR_EVALUATION_EXPORTS=false
```

The corrected debug APK is already installed with data preserved. Open Note2Snap normally.
Detailed exports are opt-in; disable ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS for ordinary builds.
Replay command and safeguards are in ../../docs/FRAME_TRACK_FILTER.md.
Export new scan evidence with:

```text
adb -s 5f4c58c6 pull /sdcard/Android/data/com.example.note2snap/files/primary-ocr-diagnostics evaluation/retest-primary
```

Keep these research images/text private. Ordinary Logcat excludes handwritten text.
Exact raw strings, correction outputs, hashes and structured blocks are in validation.json
and the final scan.json. Performance logs are in performance-final.txt.
