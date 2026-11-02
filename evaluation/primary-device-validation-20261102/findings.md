# Research fixture evidence — not the original pangram image

The historical evaluation/run01/records folder was absent. No historical records
were recreated. This export was produced by board_test_5.jpg, a 4032x3024 bundled
whiteboard fixture. Recognition coordinates are 1800x1350.

Scan: acb94c11-befa-47af-b6d3-2d797ea6c49f
Status: started / incomplete. The 180-second instrumentation command timed out
during CRNN invocation for physical line 7. The app test process was stopped
without removing app data. No correction or final structured result was reached
for this scan; correctionLoaded=true alone does not prove execution.

| Physical line | Box | Paddle raw | CRNN raw | Selection |
| --- | --- | --- | --- | --- |
| 1 | [206,334,959,408] | Mobile Application Development | not run | Paddle |
| 2 | [1696,396,1769,431] | empty | empty | CRNN fallback; no output |
| 3 | [1641,423,1768,450] | empty | empty | CRNN fallback; no output |
| 4 | [211,434,624,505] | LECTURE SERIES | not run | Paddle |
| 5 | [1745,442,1796,487] | I | not run | Paddle |
| 6 | [1653,464,1767,499] | empty | empty | CRNN fallback; no output |
| 7 | [1716,484,1769,512] | empty | incomplete | pending |

The line-5 crop visibly contains frame/reflection, not handwriting. This is a
verified false text candidate on this fixture. It is not proof that the user's
three I entries have identical geometry.

Region 100006 spans [1635,442,1796,487]. Accepted raw text candidates overlapping
it include [1645,449,1761,477] (a wide reflection) and [1783,468,1786,481]
(a short thin frame fragment). Median text height is 29. The side-border rule uses
unpadded boxes: at 1800x1350, its edge band is 12 and minimum height is 135.
The short fragment ends 14 px from the edge and is only 13 px tall, so it fails
both requirements for rejection. The wide reflection also fails the narrow/tall
criteria. The physical crop then focuses on the right-side visible stroke; Paddle
returns usable I, so CRNN is intentionally not run on that crop.

This establishes a CCL false-positive / narrow-filter limitation on a different
real fixture. Broadening the filter to normal-height narrow strokes risks deleting
legitimate I/l/1, so no geometry threshold or text blacklist was added.

Recorded attempt times (research overhead present):
- Line 1 Paddle: 765.7 ms.
- Line 5 Paddle: 760.7 ms.
- Blank fallback attempts for lines 2/3/6: 2143.0 / 1209.4 / 3712.0 ms.
- Line 7 fallback did not complete; no timing or blank prediction was invented.

See scan.json for exact nanoseconds, source hash, actual model/dictionary hashes,
all filtering decisions, region crops and physical-line crops. Preserve this partial
scan independently from any later completed small-fixture export.
