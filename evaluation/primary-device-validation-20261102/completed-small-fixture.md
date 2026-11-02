# Completed native fixture export

This is paddle_reference/fractional.png, not the user's four-sentence source image.

Scan ID: de955184-ef71-46e6-a1f3-e6bc42184109
Export: completed-test-exports/de955184-ef71-46e6-a1f3-e6bc42184109/scan.json
Status: completed
Source / recognition dimensions: 755x70
Physical crop: [0,0,755,70]
Selected engine: Paddle (usable_text)
CRNN: not run
Paddle attempt: 810.1 ms (research run; not a production latency benchmark)

| Stage | Actual text |
| --- | --- |
| Paddle raw | Mobile Application Development |
| Selected raw | Mobile Application Development |
| After offline correction | Mobile Application Development |
| Final structured block | Mobile Application Development |

Correction requested, loaded, and executed are all true. Suggestions are empty,
so correction left the prediction unchanged. The existing structurer used a
regular-text block and title Untitled Scan; no recognized words were replaced.

The exported decoded-source PNG SHA-256 is
78aac285c8637882625c8bfaecfeb9d4992a20773226db746b495a02bd2619e0.
Actual model/dictionary hashes match the pinned official assets.

This confirms the small-fixture recognition/correction/export path, not accuracy
on the missing four-sentence image or successful completion of the full fixture.
