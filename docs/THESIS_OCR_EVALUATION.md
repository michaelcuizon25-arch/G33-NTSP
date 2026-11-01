# Thesis: controlled raw OCR and document evaluation

Work remains on `feature/ocr-accuracy-improvements`. No model/decoder/segmentation,
primary-mode policy, note rules, default engine or default feature setting changes.
The validation results below were collected before committing these evaluation changes. The phone photo/records for the four-line/six-entry
case have not been supplied here: its specific cause is not established.

## Exact build and device sequence

Android Studio: JDK 17, local Gradle 8.5, SDK 34, debug variant. This checkout has
no runnable Gradle wrapper. Create a Gradle Run Configuration with task
`:app:installDebug` and these arguments (one line):

```text
-PSINGLE_SCAN_RECOGNITION_MODE=CRNN_ONLY -PENABLE_PADDLE_OCR=true -PENABLE_OCR_EVALUATION_EXPORTS=true -PENABLE_OPENCV_ENHANCEMENT=false -PENABLE_OFFLINE_TEXT_CORRECTION=false -PENABLE_CRNN_DIAGNOSTIC_EXPORTS=false
```

Equivalent PowerShell: `gradle.bat :app:installDebug` followed by those arguments.
The evaluation export flag requires CRNN_ONLY + Paddle comparison; incompatible
configuration fails the build. Ordinary primary-mode scanning remains available
with its original primary-mode property and evaluation exports false. Default
builds remain CRNN_ONLY with all optional flags false. Launch via phone icon after
install; a normal App Run configuration can rebuild without the properties.
Run `PaddleRecognitionDeviceTest` with the same flags using
`:app:connectedDebugAndroidTest` and
`-Pandroid.testInstrumentationRunnerArguments.class=com.example.note2snap.recognition.PaddleRecognitionDeviceTest`.
Stop on dictionary/native parity failures. Retain commit, APK hash, model hashes,
device/Android/ABI, CRNN preprocessing mode and all flags in the Stage 3C manifest.

Use camera SINGLE or gallery SINGLE, one source image per run. Save the original
photo and assign a stable image ID. Batch remains ML Kit and is outside this paired
evaluation. Include clear/faint/cursive, formulas/numbers, technical vocabulary,
blank boards and diagrams. Use multiple writers and independent images. Derived
variants/repeated scans are not independent subjects. Do not select only successful
scans or drop the failed 49-record run from an execution-failure report.

## What is recorded

The same existing CCL/physical-line segmentation is used by CRNN and Paddle. There
is no second Paddle detector or segmenter. Every recorded prediction is raw, before
correction, whitespace normalization in CRNN's returned line, or note structuring.
CRNN still produces the displayed note in evaluation mode; Paddle never replaces it.

Each scan's `files/paddle-comparisons/UUID.jsonl` now has stable `scanId`/`lineId`,
region ID, global box, crop dimensions, both engine identities and predictions,
latencies, Paddle hash and relative provenance paths. `crnnError`/`paddleError` are
failures, not empty predictions. In evaluation-export mode Paddle is still run on
the exact crop if CRNN preprocessing/inference/decoding throws, so failures are not
silently excluded. Cancellation still cancels the scan. Original primary/default
behavior and ordinary shadow-comparison failure behavior are unchanged.

Companion `UUID-assets/` contains:

- `source.png`: exact decoded pipeline input before enhancement/downscaling. For
  camera scans this is after guide crop/orientation, not the pre-crop sensor JPEG.
- `recognition.png`: the bounded/enhanced image used for CCL recognition crops.
- `source.json`: dimensions/scaling coordinate space, actual CRNN preprocessing
  mode, model identity, ALL region boxes and TEXT/NON_TEXT classifications.
- `line-NNNN.png`: exact physical-line bitmap supplied to both engines.
- `events.jsonl`: TEXT regions for which segmentation returned no physical lines.

Boxes are recognition-image pixels with exclusive right/bottom. Original source
mapping uses sourceWidth/recognitionWidth and sourceHeight/recognitionHeight.
`duplicateBoxOf` marks exact duplicate BOX candidates only, never automatically
removes a prediction. Repeated text is not automatically a duplicate. NON_TEXT
regions are preserved in the source manifest; rejected capture/zero-CCL scans must
also be recorded manually in the run manifest. Images contain private note text;
no uploading or automatic device deletion is implemented.

## Export, side-by-side report, and manual transcription

From repository root in Windows PowerShell, after processing finishes:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
py -3 tools/evaluate_ocr.py collect --adb "$adb" --output evaluation/run01/records
py -3 tools/evaluate_ocr.py template --records evaluation/run01/records --output evaluation/run01/labels.tsv
py -3 tools/evaluate_ocr.py html --records evaluation/run01/records --labels evaluation/run01/labels.tsv --output evaluation/run01/records/comparison.html
Invoke-Item evaluation/run01/records/comparison.html
```

Collector now includes safe companion PNG/JSON/events paths as well as top-level
paired JSONL. It avoids PowerShell binary-redirection corruption. Existing files
are never silently overwritten; use fresh run folders. Use the Stage 3C `--baseline`
snapshot option to exclude previous completed scans. Collection size limit is
32 MiB per exported file; a larger source must be exported separately using Device
Explorer, not silently resized for thesis scoring. Device Explorer path:
`/data/data/com.example.note2snap/files/paddle-comparisons/`.

Keep HTML inside `records/` so relative image links work. The report displays the
source recognition image with line-ID box overlays and exact crops next to both
predictions. Missing referenced PNGs cause the scorer/reader to fail explicitly;
legacy records lacking provenance are still readable but do not establish crop
identity on their own. Retain original files and hashes in the experiment archive.

In labels.tsv, manually verify the original handwriting BEFORE reading predictions
(hide the prediction columns during transcription). Preserve sample_id and assign
`reference_line_id` identifying the real handwritten line. Mark:

- `correct`: one complete intended handwritten line.
- `fragment`: only part of a reference line; multiple crop IDs may share its ID.
- `merged`: one crop contains multiple intended lines; list their IDs in notes.
- `duplicate`: repeated coverage of a reference line already represented elsewhere.
- `extra`: no corresponding intended reference line (smudge/artifact/diagram).
- `unreviewed`: not yet adjudicated; never imply this is a clean segmentation.

Use `segmentation_status` independently of handwriting category/source. Fill
`ground_truth` with the actual visible text in the crop for conditional recognition
scoring. For extra/duplicate entries, use empty truth for an explicitly documented
insertion-penalty analysis; do NOT also count them as legitimate reference lines.
Keep reference text in notes for auditing duplicates. Set `transcribed=yes` only
after review. For blanks use empty truth + category blank/diagram. Import all TSV
columns as text, preserve Unicode, punctuation, case and whitespace. Have a second
reviewer adjudicate ambiguity and record disagreement/exclusions before scoring.
Regenerate HTML under a new filename after annotation; outputs do not overwrite.

```powershell
py -3 tools/evaluate_ocr.py score --records evaluation/run01/records --labels evaluation/run01/labels.tsv --output evaluation/run01/line-scores.json
```

Reports contain per-line edit counts/predictions/errors, micro-aggregate CER/WER,
paired-success sample count, independent per-engine successful-row scores and
execution failures, blank predictions/empty-reference false positives, manual
segmentation-status counts, duplicate-box candidates and correctly-segmented paired
scores. Failures are excluded from successful-recognition metrics but reported;
never interpret them as zero-error blanks or present conditional accuracy alone.
Latency reports use all collected rows, not just labeled/successful paired rows;
failed CRNN rows have no usable timings, and are excluded rather than counted as
zero-latency successes. Report denominators and failure/coverage rates alongside
all metrics. Empty-reference-only CER/WER is undefined (null), not zero.

## Document-level and final-note accuracy are separate endpoints

Crop-only scores omit reference lines missed by both engines and can reward
segmentation errors if fragments are mislabeled. For thesis end-to-end accuracy,
create a UTF-8 TSV with these columns:

```text
scan_id  layer  transcribed  ground_truth  crnn_text  paddle_text
```

Copy `docs/thesis-document-template.tsv` as the starting file. Use actual TAB delimiters. One row per scan/layer, `transcribed=yes`. `layer` is
`raw_ocr` or `final_note`. A spreadsheet text cell can contain embedded newlines;
export valid quoted TSV. For raw_ocr, manually verify reference reading order on
the ORIGINAL image and assemble each engine's raw outputs in that declared order,
including all extra/duplicate `I` entries, blanks, fragments and omissions. Do not
deduplicate, correct spelling, repair truncation or discard failed scans. Document
how missing predictions/failures are represented (empty contributions) and retain
engine execution failures separately. Every original reference line occurs once
in this document ground truth. This is the primary endpoint for segmentation plus
recognition, alongside the correctly-segmented conditional endpoint.

For final_note, transcribe the actual generated note under each engine/configuration
separately. Evaluation mode displays CRNN notes, not hypothetical Paddle-structured
notes; obtaining Paddle final-note text requires a separate primary-mode scan.
Do not label raw Paddle prediction concatenation as final structured output. A Paddle-primary final note is a MIXED engine policy with CRNN fallback, not
pure Paddle output; label that final-note endpoint accordingly even though the
TSV convenience column is named paddle_text. Keep
correction flags and formatting-to-text policy explicit; never combine these layers.

```powershell
py -3 tools/evaluate_ocr.py documents --labels evaluation/documents.tsv --output evaluation/document-scores.json
```

CER is summed Unicode-codepoint Levenshtein errors / summed reference characters.
WER uses whitespace-token Levenshtein and summed reference words. No Unicode/case/
punctuation normalization is automatic; mathematical expressions particularly
need CER alongside whitespace WER. Report both raw and final-note tables with
sample/reference counts and failures. Do not report averages of line percentages
as corpus CER/WER. For uncertainty, use independent image/writer-level resampling
with a predeclared method; repeated lines/scans are clustered, not independent.
The current scripts provide descriptive metrics, not significance tests, power
analysis or automatic confidence intervals. No new measured accuracy is claimed.

## Trace the four-line/six-entry case without changing recognition

Inspect line overlays/crops and compare raw JSONL to the final displayed note:

- Different boxes for isolated stems, bullets, borders, noise or diagram edges:
  CCL candidate classification/grouping or physical-line segmentation is implicated.
  A narrow vertical component can plausibly decode as `I`; inspect pixels, not the
  character alone. Legitimate pronoun/variable/Roman `I` must not be globally filtered.
- Same/overlapping boxes repeated: candidate duplication/overlap; adjudicate using
  source.json, line crops and duplicateBoxOf. Exact-box marking is not a full overlap detector.
- Four correct crops but extra characters in their predictions: OCR error.
- Four raw entries but six displayed note entries: investigate structuring/formatting
  downstream; extra JSONL entries are created before structuring and cannot have
  been generated by that downstream step. Existing rule/grouping baseline failures
  mean note duplication must not be dismissed without examining both outputs.
- Four intended lines, six entries with three extra `I`s may also imply a missed or
  merged intended line, not just three insertions. Audit the complete reference.

Long-line code inspection: legacy CRNN fits the entire crop onto fixed 512x32 with
aspect preservation/padding; very long lines lose stroke resolution rather than
being intentionally sliced into extra entries. SimpleHTR reference preprocessing
returns an unsupported-width result above 512 and the existing wrapper handles its
fallback. Paddle dynamically resizes/pads to height 48, rejecting width >3200
explicitly; the evaluation records that error. Paddle's training max_text_length
25 is not an inference truncation rule here. Fixed CRNN temporal output (128 steps)
and finite Paddle temporal resolution can cause omissions/repeated-character CTC
losses on long lines. Neither decoder adds new region records. Physical-line
segmentation uses thresholded horizontal ink runs, gap merging and crop padding;
CCL can already have separated marks into different regions. Do not change any of
these components merely to reduce entry count or improve reported test scores.

## Android latency and validation

Capture NOTE2SNAP_PERF (model init, preprocessing, segmentation, inference, CTC,
structuring and TOTAL) as in Stage 3C, then:

```powershell
py -3 tools/evaluate_ocr.py perf --log evaluation/run01/perf.log --output evaluation/run01/performance.json
```

Exports add PNG/JSON I/O. For latency, repeat a separate run with the SAME comparison
configuration but `ENABLE_OCR_EVALUATION_EXPORTS=false`; keep raw JSONL and PERF logs.
Do not compare export-heavy total latency to ordinary scanning as a model speedup.
Separate first-line/session initialization from subsequent invocations. CRNN model
init occurs before inference; Paddle's first recorded invoke includes session init,
while dictionary construction precedes its line timings. Use initialization-stage
and per-scan totals rather than subtracting unrelated aggregates. No raw model
confidence selection is allowed. Report hardware, warmups, p50/p95, sample counts,
resolution, modes, failures, instrumentation and memory conditions. Phone timings
must not be substituted with host timings. Native tests, real camera/gallery,
source alignment and new records still require device validation here.

## Reported gallery incident: evidence available so far

The user reports gallery import, primary-with-fallback, enhancement/correction/
shadow/detailed exports all disabled, with flags not independently verified in the
installed APK. The board had four mixed-case/uppercase versions of the pangram.
Only the final screen (not paired records) showed six entries, including three I's.
Logs showed seven Paddle selections and one unusable-output CRNN fallback.
Those logs prove primary execution but not exact transcription, crop identity,
final-note line mapping, or installed flag provenance. Seven successful Paddle
selections append seven raw recognized lines; that one exact-crop fallback can add
at most one more. Six visible entries therefore cannot be treated as a six-crop
count. Title extraction/joining/rendering can change visible counts. One fallback
alone cannot explain three distinct raw I predictions; duplication downstream
remains possible. The original photo and screenshot are still required. No cause
or CER/WER for this incident is claimed from paraphrased screen text alone.

## Preparation validation

Evaluation-enabled debug and Android-test APK assembly passed. The export
regression (injected predictions, not OCR accuracy) passed. Restored-default
debug/release/Android-test assembly passed. Full host unit results: 133 passed,
14 unchanged existing failures, 1 skipped; no new failures. All 18 Python tests
passed using synthetic records. The overall Gradle unit command remains nonzero
for the same 14 baseline failures. All optional flags are restored false, and
ordinary mode remains CRNN_ONLY. Dictionary integrity and unchanged model, primary
policy, segmentation and CRNN CTC source were checked. See
`docs/thesis-evaluation-validation.json`. No Android phone is attached here; no
new phone accuracy, latency, or improvement measurements are claimed.
