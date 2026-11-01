# Conservative side-border text rejection

Branch: `feature/ocr-accuracy-improvements`. Validation below was completed before
this fix was committed; actual phone geometry confirmation remains pending.
The user visually identified four real sentence crops and four left-board border
crops in the evaluation HTML; Paddle decoded three of the latter as I. That supports
an upstream false-text-candidate explanation for those raw predictions. The actual
photo, image dimensions and raw boxes have not been supplied here, so the new rule
is deliberately narrow and is not claimed to cover all four reported artifacts.

## Why the old path admitted them

CCL's raw and final side-edge heuristics largely use contact within two image-edge
pixels. Inset border fragments can escape them. Text-component checks permit some
tall components; the thin-long-component exclusion is conditional on height being
no greater than the handwriting median. Once a component is labeled TEXT, the
physical-line segmenter finds ink runs, without semantic knowledge of borders.
The old primary guard rejected vertical separators only at ink width <=2, globally:
a thicker border could pass, while a legitimate very thin I/l/1 elsewhere could
be rejected. Model output I alone cannot identify which case occurred.

## Minimal changes

One CCL text-candidate predicate calls `SideBorderGeometry` on RAW unpadded boxes,
before grouping/crop padding. All conditions must hold:

- raw component is within min(12, max(2, imageWidth/100)) pixels of left or right edge;
- width <= min(8, max(2, floor(imageWidth*0.006)));
- height >= max(24, 3*medianTextHeight, imageHeight/10);
- height/width >=12.

These reject only very thin, unusually tall outer-side components. Interior tall
strokes, normal-height narrow handwriting, and wide marginal writing do not match.
They remain available to the existing non-text/diagram processing; source images
are never changed. This is not an inferred whiteboard-boundary detector. A board
border well inside the image, short fragments, perspective-slanted/thicker borders
or ambiguous tall marginal writing are not safely solved by widening this rule.
Confirm raw geometry from crops/logs before extending it. The existing log prints
`Median text height`; exported region boxes include padding and are not the raw
component boxes tested here.

The position-blind vertical branch is removed from the primary `thin_separator`
guard. Horizontal separator checks, blank/noise/frame guards and CCL NON_TEXT
exclusion remain. Vertical stems now rely on upstream spatial evidence rather
than character shape alone. No I/l/1 string blacklist, confidence threshold, model,
normalization, physical segmentation, CTC, fallback or note-rule changes were made.
Complex interior non-text can still be misclassified; this is not a general-purpose
semantic separator classifier. Very tall writing clipped at the outer edge remains
ambiguous. Existing CCL edge heuristics outside this change are not rewritten.

## Regression and phone verification

Synthetic regressions cover both side edges slightly inset from the previous 2px
cutoff; normal-height 1–4px strokes near margins and in the interior; interior tall
strokes and wide edge writing; four body rows plus four border fragments; CCL narrow
stems within a real row; and primary-guard acceptance of 1–3px vertical strokes.
These synthetic tests are not measured improvements on the user's image.

Run the same gallery image and settings before/after, retaining the source PNG and
manually verified reference. Use the thesis comparison configuration in
`docs/THESIS_OCR_EVALUATION.md` to inspect overlays/raw predictions, then primary mode
to check final notes. Region/line IDs can renumber after filtering: match by source
image/boxes, not ID alone. Confirm all four real sentences remain complete and
legitimate I/l/1 handwriting at normal text height elsewhere is retained. Keep
cases where the filter has no effect and do not silently delete extra entries from
thesis measurements. No device is attached in this workspace; actual phone results
remain pending, as do exact geometry checks for the four reported border crops.

## Exact annotation values in evaluate_ocr.py

The column is `segmentation_status` (not `segmentation`). Accepted case-sensitive
values are exactly:

`unreviewed`, `correct`, `fragment`, `merged`, `duplicate`, `extra`.

For 0001–0004 use `correct` only after confirming each crop contains one complete
intended sentence, and assign its reference_line_id. For the four confirmed border
crops 0005–0008 use `extra`, not fragment or duplicate. An equal prediction I does
not make two different non-text border crops duplicates. Suggested fields:

| Field | Confirmed border crop |
| --- | --- |
| source | gallery |
| category | other |
| segmentation_status | extra |
| reference_line_id | empty |
| ground_truth | empty |
| transcribed | yes |
| notes | Left whiteboard border; no handwriting |

`other` is an accepted handwriting/category label, and the scorer explicitly permits
empty truth for segmentation_status extra/duplicate. `border`, `non_text`,
`false_positive`, `incorrect` and `skip` are NOT valid segmentation_status values.
Keep the actual raw predictions untouched, including empty outputs/errors. Use the
full four-line reference once for document-level CER/WER; count extra I's as
insertions rather than deleting them or repeating a reference line. Correctly
segmented recognition-only metrics and end-to-end document metrics remain distinct.

Host results are recorded in `docs/side-border-validation.json` after verification.

Validation completed: all six new regressions pass. Full unit suite: 139 passed,
14 unchanged existing failures, 1 skipped, with no new failures. All 18 Python
tests pass. Debug/release/Android-test APK assembly succeeds; the combined Gradle
command remains nonzero for those same baseline unit failures. Model assets/hashes
are unchanged. No CER/WER improvement on the actual image is claimed.
