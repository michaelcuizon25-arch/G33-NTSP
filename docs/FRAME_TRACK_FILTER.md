# Image-supported whiteboard frame filtering and physical-line boundaries

The confirmed pangram export is
evaluation/next-primary-run/90bc382b-9eb9-4da4-993b-bfd729ed6038.
Its three false text entries are disconnected pieces of an inset metal frame.
The unchanged SideBorderGeometry predicate only covers the outer image edge;
its existing thresholds were not widened.

## Frame verification

FrameTrackFilter runs after raw CCL and before text grouping. It:
1. Finds long thin seeds or chains of at least eight disconnected thin components.
   Aligned support must span substantial image height and three vertical quarters.
2. Fits a near-vertical track, with slope magnitude at most 0.08. Coordinates are
   original recognition-image coordinates; neither source pixels nor masks change.
3. Verifies the source image independently: long edges, a separate groove/lip edge,
   and a light neutral strip must jointly span over half the image. A plain gray
   stroke or a simple two-edge stripe is insufficient.
4. Considers only narrow components at the verified outer track edge. Short wider
   components are retained as potentially branched/serifed handwriting.
5. Compares only the component's foreground pixels with surrounding source-frame
   profiles, compensating for edge drift and gradual illumination. Symmetric
   interpolation limits contamination from neighbouring ink. Darker, coloured,
   bright, or poorly supported marks are retained.

Rejected pieces are removed only from text candidacy and detached-mark reattachment.
Original source images and existing visual/diagram handling remain available.
There is no character blacklist and no use of CRNN blank predictions as a veto.

These are conservative geometric/photometric heuristics, not calibrated probabilities.
The foreground check permits limited sensor/JPEG variation (mean dark residual <=7,
mean absolute residual <=8 gray levels, with at most 6.25% strong outliers).
Actual captured frame pieces and negative handwriting cases determine coverage;
broader handwriting/photo validation remains necessary.

## Cost and observability

The detector considers at most eight seed hypotheses and two verified tracks.
It samples approximately 128 rows for edge support, reuses cached candidate-corridor luminance and
row support, and allocates its full image arrays only when plausible seeds exist.
At 1800x1350, those temporary RGB/luminance arrays total about 12 MB and are released
after CCL verification; no bitmap or model session is retained by the filter.

NOTE2SNAP_PERF records FRAME_TRACK_FILTER separately. Research exports record
inset_frame_track and inset_frame_rejected events and the revision
frame-track-v1.1+side-border-587e0e6. Ordinary Logcat does not contain handwritten text.
Detailed images/text remain behind ENABLE_PRIMARY_OCR_DIAGNOSTIC_EXPORTS.

## Structuring correction

WhiteboardRuleEngine.canMerge previously checked the following line for a heading
boundary but not the previous line. A wide all-caps line followed by lower-case OCR
could therefore be merged before heading classification.

The existing short all-caps heading criterion now applies to the previous line too.
Explicit sentence-ending connectors still permit continuation. Normal lower-case
wrapped sentences retain the existing joining rules; formatting rules are unchanged.

## Validation and replay

FrameTrackFilterTest covers the actual captured recognition image, unchanged pixels
and mask, all four body rows, disconnected tracks, normal/faint narrow glyphs near
photo edges and the frame, and ambiguous gray strokes. CapturedLineStructuringTest
covers the actual four outputs/order, valid lower-case wraps, all-caps connector
wraps, and independent headings.

Captured research-image tests explicitly skip if the private evaluation corpus is
not checked out. Synthetic preservation tests always run. The captured image is not
packaged into production assets or bundled automatically into the test APK.

Use an explicit original-source replay on device 5f4c58c6. Build debug and androidTest
APKs with the primary/correction/export properties documented in
PRIMARY_OCR_DIAGNOSTICS.md, install with adb -s 5f4c58c6 install -r, then run:

```text
adb -s 5f4c58c6 shell am instrument -w -r -e class com.example.note2snap.recognition.CapturedFramePipelineDeviceTest#originalPangramHasFourLinesWithoutFrameEntries -e capturedSource /sdcard/Android/data/com.example.note2snap/files/primary-ocr-diagnostics/90bc382b-9eb9-4da4-993b-bfd729ed6038/source.png com.example.note2snap.test/androidx.test.runner.AndroidJUnitRunner
```

The replay verifies the source hash, model/dictionary hashes, four selected Paddle
lines, four separate structured blocks and correction execution. It writes a new
research UUID export without saving a note. Additional tests cover native CRNN
fallback using a test-only injected Paddle failure and an isolated in-memory note
edit/save/reload. They never clear or write the user's actual notes database.

Evidence, measured before/after results and remaining limitations are recorded under
evaluation/frame-filter-validation. Do not interpret a partial export as success.

## Deliberate abstentions

Black/coloured or ungrooved frames, large perspective angles, insufficient visibility,
ambiguous short/wide marks and unsupported extra track hypotheses can remain.
The filter is intentionally not a general board-boundary detector. It does not
repair lower-case recognition errors or change correction aliases, models, decoders,
camera/gallery preprocessing or physical-line coordinates.
