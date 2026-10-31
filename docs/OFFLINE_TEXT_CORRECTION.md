# Stage 2: optional offline OCR text correction

Base: Stage 1 commit `7f003bb3f97da4a7b7f18deb578716ffb91ade6a` on
`feature/ocr-accuracy-improvements`. Stage 2 builds on and preserves Stage 1.
No model/charset, CTC decoder, inference runtime, CRNN input normalization,
SimpleHTR implementation, CCL, physical segmentation, OpenCV algorithm, ML Kit,
note editing/storage or rule-engine changes are included. No new libraries.

## Configuration and scope

`BuildConfig.ENABLE_OFFLINE_TEXT_CORRECTION` is false by default. Evaluate with:

```sh
gradle assembleDebug -PENABLE_OFFLINE_TEXT_CORRECTION=true
```

OpenCV remains independently controlled by `ENABLE_OPENCV_ENHANCEMENT`; neither
flag changes the default LEGACY CRNN input mode. Omit the properties to preserve
the default behavior. The Context pipeline constructor also accepts an explicit
`enableTextCorrection` boolean. Tests can supply an `OfflineTextCorrector` to the
recognizer-injection constructor without loading Android assets or a model.

The single-scan camera/gallery/tutorial pipeline is now:

`photo -> optional OpenCV -> existing threshold/CCL -> recognizer/CTC -> optional text correction -> existing structurer -> editable note`

The correction stage accepts the existing `LineRecognizer` contract, so it also
works after an injected `MlKitLineRecognizer` without altering that recognizer's
region-text fallback. The current batch camera/gallery flows call ML Kit directly
and do not use this structuring pipeline; those flows remain unchanged. No new
CRNN-to-ML Kit fallback or replacement engine is introduced.

## Correction policy

The pure Kotlin correction package contains:

- `OfflineTextCorrector`: text in, `CorrectionResult` out.
- `CorrectionResult`, `SuggestedCorrection`, `CorrectionEvidence`: original and
  corrected strings, original UTF-16 offsets, candidate replacements, applied
  status, evidence source, edit distance and explanation. No probability score.
- `DictionaryTextCorrector`: conservative word-level edit-distance suggestions
  and a small explicit allowlist of reviewed OCR nonword spellings.
- `BundledTextCorrector`: loads the local resources only after opt-in and caches
  the immutable corrector for subsequent scans.

Resources under `assets/correction` are locally authored (no copied external
wordlist): 542 vocabulary entries, 25 reviewed alias pairs and a provenance/policy
README. They include common academic and computer-science terminology. No online
dictionary, grammar service, language model, network permission or runtime download
is added. Correction consists only of local asset reads and string operations.

Automatic changes require a lowercase, 5–32-letter token in the reviewed alias
list whose target exists in the vocabulary. Known words always win. Examples:

| Original | Corrected | Decision |
| --- | --- | --- |
| `cornputer algorithrn` | `computer algorithm` | Reviewed rn/m confusions |
| `defintion recognitlon` | `definition recognition` | Reviewed academic/OCR aliases |
| `functlon recurslon` | `function recursion` | Reviewed CS/OCR aliases |
| `Algorithrn` | `Algorithrn` | Suggest `Algorithm`; possible proper name |
| `form from modem modern` | unchanged | Valid-word preservation |
| `x = functlon(y) + 2` | unchanged | Formula preservation |

Title-case words are suggestion-only. All-caps, mixed-case, numeric tokens,
URLs/email, compound identifiers and formula lines are protected. Generic
Levenshtein neighbours (distance 1, or up to 2 for words of at least eight letters)
are suggestions only, even when unique. At most three generic neighbours per
eligible token are returned. Unknown or ambiguous text is not expanded into words
or sentences. Whitespace, CR/LF paragraph boundaries, capitalization and punctuation
are copied from the received text, except for the applied word replacements.

Explicit `protectedTerms` can override aliases when constructing a corrector.
This is a code-level extension point, not a new UI. An unmarked lowercase name or
valid domain term that exactly resembles an allowlisted OCR error remains a risk.
The dictionary is deliberately small and is not a complete English lexicon.

## Preservation and failure behavior

`WhiteboardRecognitionPipeline` invokes correction only after recognition returns.
It passes corrected line copies to the unchanged `PipelineTextStructurer`.
`RecognitionPipelineResult.recognizedLines` retains the original engine output for
existing callers and readability checks. `correctedLines` and `textCorrections`
provide the comparison. Region IDs, bounding boxes, OCR confidence and visual
regions are unchanged. OCR confidence is never reused as correction confidence.
The rule engine's existing `ocrCorrections` hook and other heuristics are untouched.

The existing CRNN per-line diagnostics remain raw recognition results. A separate
`text-corrections.json` records applied changes and suggestions, original offsets,
evidence and skipped reasons. Diagnostics remain observational. The existing
review/editor flow receives structured content as before; suggestions are exposed
through results/diagnostics, not a redesigned interface.

Asset/parse failures disable correction and preserve raw OCR. Per-line correction
exceptions preserve that line and do not discard other lines. Cancellation is
propagated. Recognizer failures retain their previous behavior. Text exceeding
32,768 UTF-16 units or 2,048 processed chunks is returned intact, not truncated.

## Tests and results

Run the full defaults-off suite and APK builds:

```sh
gradle testDebugUnitTest
gradle assembleDebug assembleRelease assembleDebugAndroidTest
```

New unit coverage includes reviewed substitutions, academic/CS vocabulary, valid
words, names/abbreviations, numeric/formula/identifier protection, punctuation,
empty/partial text, paragraph boundaries, UTF-16 offsets, ambiguous suggestions,
protected terms, idempotence, limits, invalid aliases, pipeline ordering, raw text,
coordinate/confidence preservation, a real diagram fixture, failure/cancellation,
diagnostics and accuracy evaluation. No unrelated tests were modified.

Baseline: 88 tests, 73 passed, 14 failed, one optional Stage 1 host audit skipped.
Stage 2: 114 tests, 99 passed, the same 14 failed, the same one audit skipped.
All 26 new tests pass. The prior failures are ML Kit context initialization,
two diagnostics/input-parity assertions, a title/editor-content assertion and ten
structuring assertions. They are retained rather than hidden or rewritten.

Two new Android instrumentation tests check real APK asset loading/caching and
Context-constructor enable/disable wiring with an injected recognizer. Run with:

```sh
gradle connectedDebugAndroidTest
```

They are compiled here; no Android device/emulator is attached for execution.
These tests inject OCR text and do not claim native model accuracy.

### Measured correction evaluation

`CorrectionEvaluationTest` writes `app/build/offline-correction-evaluation.txt`.
It evaluates the same production corrector using 16 saved Stage 1 CRNN title
predictions from `board_test_5.jpg` and its controlled variants. The fixture records
the source, model hash, input mode and OpenCV setting. These are captured host
CPU/Flex predictions decoded by the unchanged Kotlin CTC implementation, not
fresh Android measurements. Each table row contains four variants of one
30-character/three-word title (120 reference characters, 12 reference words).

| Input mode / OpenCV | Raw -> corrected CER | Raw -> corrected WER |
| --- | ---: | ---: |
| LEGACY / off | 37.50% -> 37.50% | 83.33% -> 83.33% |
| LEGACY / on | 36.67% -> 36.67% | 75.00% -> 75.00% |
| SIMPLE_HTR_REFERENCE / off | 8.33% -> 8.33% | 33.33% -> 33.33% |
| SIMPLE_HTR_REFERENCE / on | 0.83% -> 0.83% | 8.33% -> 8.33% |

No saved title prediction changes: errors are title-case, mixed-case, joined or
blank, and the conservative policy does not silently rewrite them. This dataset
therefore shows neither improvement nor worsening from Stage 2. It is too small
and correlated to estimate general correction quality.

Separately labeled synthetic unit controls demonstrate behavior, not model
accuracy: `cornputer algorithrn` has CER 22.22% -> 0% and WER 100% -> 0%; the academic
and CS error controls also improve to zero errors. Valid-word, name and formula
controls remain unchanged. A deliberately valid unmarked domain token `functon`
is incorrectly changed to `function`: CER 0% -> 14.29%, WER 0% -> 100%. This known
negative control is explicitly asserted and reported rather than counted as an
improvement. Do not interpret the synthetic error cases as held-out OCR accuracy.

## Performance, memory and rollout

The bundled resources total about 6.2 KB including the README. Vocabulary is
indexed by length and cached on first enabled use; disabled scans do not load it.
For eligible unknown tokens, work scales with the candidates in nearby length
buckets and bounded word-length dynamic programming. Applied aliases use a map
lookup. New memory consists of dictionary/index objects, per-line comparison
strings and bounded suggestions; images and inference buffers are untouched.

One warmed host run over 200 tokens measured roughly 0.67 ms median / 4.47 ms p95.
These are host observations, not Android latency guarantees or peak-memory data.
Measure first-use load time, long unknown-token inputs, repeated scans and memory
on representative Android devices before enabling the flag.

Keep correction disabled by default. Add independent, manually transcribed body
text and names/formulas to a held-out evaluation set, track false corrections as
well as CER/WER, and expand aliases only with evidence and preservation tests.
Title-case suggestions should require review unless a future validated source of
context justifies applying them. PaddleOCR and LiteRT work are outside this stage.
