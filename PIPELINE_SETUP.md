# Note2Snap working recognition pipeline

The scan flow now runs this complete no-training pipeline:

`photo -> adaptive preprocessing -> CCL region detection -> per-line crops -> ML Kit OCR -> List<RecognizedLine> -> rule-based note structuring`

Main source folders:

- `preprocessing`: grayscale, adaptive thresholding, downscaling, and noise cleanup.
- `ccl`: connected-component labeling, text-line grouping, and text/non-text classification.
- `recognition`: per-line on-device ML Kit recognition and the pipeline coordinator.
- `structuring`: reading-order conversion and integration with `WhiteboardRuleEngine`.

## Why ML Kit is used

The reference source used `MockHandwritingRecognizer`, while its CRNN/TFLite `runInference()` method was still a placeholder. No trained `.tflite` model or matching charset was included. ML Kit therefore replaces only that unfinished recognition stage so the full pipeline can execute now.

When a trained CRNN model becomes available, add a CRNN implementation behind the recognition stage and retain preprocessing, CCL, ordering, and structuring unchanged.
