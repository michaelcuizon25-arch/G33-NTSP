# Actual pangram scan investigation

Analyzed scan: 90bc382b-9eb9-4da4-993b-bfd729ed6038. This is the completed
four-sentence whiteboard scan, not either small instrumentation fixture or the
incomplete board_test_5.jpg scan also present in this export.

Source: 4032x3024. Recognition image: 1800x1350. Source PNG SHA-256:
236b9f79d837acbe5ef9ee08f17b01a13ba324e247466afb161a808ef561f9c6.
The older controlled image's hash is unavailable, so exact historical file identity
is not established. The exported image visibly contains the intended four sentences.

Paddle primary with CRNN fallback was active. Correction was requested, loaded,
and executed. Actual model and dictionary hashes match the pinned expected values.
There are eight physical crops, seven selected outputs and six structured blocks.

## Per-line evidence

All boxes are [left,top,right,bottom] in recognition-image coordinates.

| Line | Box / dimensions | Raw Paddle (whitespace normalized here) | CRNN | Selected / after correction |
| --- | --- | --- | --- | --- |
| 1 | [264,505,1151,587], 887x82 | The quck brown fo jumps ver e | not run | Paddle; unchanged |
| 2 | [135,601,1318,666], 1183x65 | THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG | not run | Paddle; unchanged |
| 3 | [135,697,1305,805], 1170x108 | he k os | not run | Paddle; unchanged |
| 4 | [134,799,1391,879], 1257x80 | THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG | not run | Paddle; unchanged |
| 5 | [100,810,131,1065], 31x255 | empty | empty | fallback, unusable_output; no output |
| 6 | [102,1058,126,1091], 24x33 | I | not run | Paddle; I |
| 7 | [103,1090,126,1126], 23x36 | I | not run | Paddle; I |
| 8 | [102,1174,125,1204], 23x30 | I | not run | Paddle; I |

Unnormalized raw text is retained in the original scan.json and analysis-summary.json.
Line 1 includes a trailing space; line 3 includes repeated/trailing spaces.
After normalizing whitespace, all four sentence predictions exactly match the
earlier results supplied by the user. Both uppercase lines remain correct at the
word level. This scan does not demonstrate the reported four-line Paddle regression.

All eight physical crops and the recognition image were visually inspected.
The four sentence crops contain complete visible sentences; there is no obvious
text truncation. Lines 2-4 also contain part of the board frame at their left.
The effect of that contamination on line 3 requires a controlled crop A/B test;
its poor prediction alone does not establish a new segmentation defect.
Lines 5-8 contain board frame, with no handwriting.

## Proven source of the extra I entries

The three entries are Paddle predictions from lines 6-8, not CRNN output or
correction-generated text. The primary selector accepts I as usable alphabetic
text and correctly does not invoke fallback. Blank CRNN predictions for these
three latest crops are not recorded; do not infer them from line 5 or historical
claims. Only line 5 ran CRNN, returning blank successfully in about 1964.8 ms.

CCL classifies all eight regions as TEXT. The accepted raw frame components include:

| Output line | Raw component box | Raw size |
| --- | --- | --- |
| 6 | [112,1064,116,1085] | 4x21 |
| 7 | [112,1101,116,1120] | 4x19 |
| 8 | [111,1180,115,1198] | 4x18 |

Additional accepted, vertically aligned components occur at x approximately 109-121
from y=561 through y=1198, including a 10x172 component at [111,879,121,1051].
Their alignment follows the visible inset left board frame. These are separate CCL
regions, not three copies fabricated by physical-line segmentation.

At this image size and median height 32, SideBorderGeometry requires:
- contact within 12 px of the image's outer side edge;
- width at most 8 px;
- height at least 135 px;
- aspect ratio at least 12.

The three components start about 111-112 px inside the photo, are only 18-21 px
tall, and have aspect ratios 4.5-5.25. They fail the rejection conditions. The
longer 10x172 fragment also misses the side band and width constraint.
There are zero side_border_rejected events in this scan.

The predicate targets the image edge, while the photographed board edge is inset.
This is a confirmed applicability limitation of the installed filter, not an
outdated APK or a dictionary-integrity failure. The primary input guard deliberately
permits vertical stems because genuine I/l/1 can have the same local appearance.

## Correction and final structuring

Correction changed zero of seven selected outputs, produced no suggestions, and
reported no per-line correction errors. Its short-token/all-caps protections and
reviewed-alias policy explain why these predictions remain unchanged.

Structuring uses line 1 as the note title and heading. It joins lines 2 and 3 into
one regular-text block:

THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG he k os

Line 4 becomes a heading. The three I outputs remain three regular-text blocks.
Structuring therefore changes grouping and presentation, but does not replace the
recognized words. This joining can make a correct uppercase sentence appear to
contain the following line's poor prediction.

## Appropriate correction strategy

Prefer conservative, image-context verification of the detected board frame,
applied to raw CCL components before text grouping. Require evidence beyond the
suspect crop: an independently supported long board edge or a collinear fragmented
track across substantial image height, consistent frame/background appearance,
and spatial separation from the text body. Reject components only when they are
supported as belonging to that frame. Preserve uncertain components and any writing
that overlaps or touches an ambiguous boundary. Keep decisions in research exports.

This is a frame-aware filtering improvement, not a wider fixed photo-margin band.
Do not hardcode x=112 or this image's boxes. Simply widening the edge band and
lowering the height threshold would capture legitimate marginal I/l/1.

An extra CRNN pass on suspicious crops may be useful for a labelled experiment,
but a blank CRNN result is not reliable evidence of non-text. It could reject
legitimate handwriting that Paddle correctly reads; the earlier full-fixture run
also exposed a native CRNN stall. There is no need to change ordinary primary/fallback
selection to address this upstream classification defect.

Before enabling a candidate filter:
1. Replay this exact source and compare all four sentence boxes, pixels and raw text.
2. Confirm all frame-only regions are excluded, not just those decoded as I.
3. Test positive handwritten I/l/1 near margins, in the interior and near a frame,
   including broken strokes, tilted boards and missing/ambiguous board boundaries.
4. Preserve Paddle primary, lazy CRNN fallback and raw/corrected exports.
5. Run controlled frame-removal A/B experiments separately before changing mixed
   sentence crops or claiming an improvement to the two lowercase predictions.

No recognition/filtering/correction/structuring code or model assets were changed
during this investigation. Existing local modifications and all original exported
records were preserved. No device installation, new inference, build or test was
performed in this read-only evidence investigation; the reports are derived from
the recorded results and visual inspection, not a new recognition run.
