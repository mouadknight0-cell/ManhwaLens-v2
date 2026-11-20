# Critical rendering failure: safe replacement (v1.1.21)

## Evidence and root causes

Inspected the supplied `ManhwaLens-full-report-1795180208080.txt` (v1.1.20, Xiaomi 23106RN0DA / Android 14), native capture/OCR/grouping/model/cache/state/overlay code and existing tests. No additional screenshots requested. Captured text is not committed as a dataset.

The requested example is present in the report:

- Source: `I DO! I WILL TAKE THAT POWER I WILL SEIZE THAT CHANCE!`
- Stored output: `أفعل! سوف آخذ هذه القوة سوف اغتنم هذه الفرصة!`
- OCR region 253,272–594,448; mask 251,270–596,450; confidence ~0.693.
- Other captures split the same passage into `TAKE THAT POWER!`, `I WILL SEIZE THAT`, `CHANCE!`, with 38–42px-high masks. Another has `IWILL` and untranslated `iwill` in output.

This establishes both segmentation inconsistency and that at least one complete translation survived storage. It does NOT establish that the model returned one character for that particular capture or identify which exact pixels the phone displayed.

Definite rendering defects in previous implementation:

1. `ReplacementTranslationView` painted its opaque background **before** checking for a text layout. Its explicit tiny-region fallback drew `…`; its scrolling fallback hid most of a paragraph while masking the whole source.
2. `BubbleOverlayService` attached that view without measuring/preparing it or checking `needsDetails`. Failed translation/error messages were also treated as replacements.
3. Surface uncertainty was encoded only by a white color fallback, so the renderer could not distinguish reliable white evidence from no reliable reconstruction.
4. Script validation accepted a single Arabic character for a long English utterance. It did not flag suspiciously missing segments, Latin remnants or error/placeholder-shaped outputs consistently.
5. Progressive block delivery was consumed directly by the replacement layer. These were whole completed blocks, NOT token streaming, but a partial frame could already be masking source. Timestamp acceptance was weaker than explicit current-request identity.
6. Window attachment failure had no transaction rollback. General failure automatically opened a large error panel over the reader.

There is no HTTP translation API, streaming decoder or JSON response parser in the production translation path. ML Kit returns a completed Task<String>. JSON elsewhere is local history/report persistence, not model response parsing. `CaptureResult.previewOf` truncates history previews only, not overlay dialogue.

## Changes

### Prepare, validate, then commit

- Replacement waits for the **final** capture result and current `activeResultId`. Capture cancellation, scene changes, pauses and new requests invalidate old identity. Progressive in-app results can remain available, but cannot mask the page.
- Every candidate passes error/OCR/structural-completeness checks, reliable-surface checks, non-overlap checks and native full-fit layout before any replacement windows attach.
- Mask and **all** text are drawn together into a private offscreen ARGB bitmap. Only successful complete preparation produces a drawable replacement. The screen never sees an independent background-paint operation.
- No ellipsis-only or scroll-slice replacement. Too small, oversized, low-confidence, unfit, malformed or failed work leaves source visible. A manually opened reader remains scrollable.
- All windows are preflighted before attaching. Attachment failure or identity change rolls back attempted windows. Hide-before-remove protects visibility if WindowManager removal throws. This is atomic **per-region prepared content**, with best-effort multi-window rollback, not a SurfaceControl transaction across the whole screen.
- If any candidate is unsafe, preserve the frame's original text and offer a reader launcher outside all detected text rectangles. If no safe launcher slot exists, add none. This cannot guarantee avoidance of text OCR did not detect.
- General errors clear/hide replacements and use a transient Toast rather than automatically painting an error reader over dialogue.

The original reader image is never destructively edited: captures are private bitmaps and Android overlay windows are reversible. Failure recovery removes/hides overlays to expose the app's original page; no screenshot needs to be painted back into another app. Captured bitmaps retain the existing bounded lifecycle rather than keeping full frames indefinitely.

### Validation and conservative fallbacks

`ReplacementQuality` uses source word/letter counts, terminal structure, source/output contraction, ellipsis context, Arabic-script expectation, malformed/control/placeholder checks and OCR status/confidence. One-character long-sentence output and severe multi-clause loss are rejected. Long nonterminal source fragments and confidence below 0.5 are reader-only; this is a conservative overlay policy, not a calibrated OCR accuracy claim. It can reject valid titles, fragments or concise translations.

`TranslationOutput` applies completeness checks before caching and rejects unprotected Latin remnants in Arabic output. Confirmed name sentinels remain supported. Cache namespace changed so pre-policy cache entries cannot bypass new model validation. Source-level checks also run at render time, including reviewed lexicon output. No invented retries, phrase-specific translations or extra inference were added.

These are suspicion filters, **not proof of semantic equivalence**. A fluent but wrong Arabic sentence or omission with similar length may pass. Missing OCR words cannot be reconstructed reliably without visual evidence. Do not describe all translation completeness or naturalness as solved.

### Geometry and backgrounds

`ReplacementSurface.reliable` distinguishes sampled uniform tone / conservative oval evidence from guessed neutral-white fallback. Unknown/complex/irregular backgrounds do not qualify for automatic masking. Dark uniform backgrounds retain their color. This is intentionally safer than trying to invent an organic boundary, tail or artwork texture. Reliable evidence is ephemeral; historical surfaces are not trusted as fresh captures.

No new arbitrary-shape detector, rotated balloon recovery or image inpainting model was added. Black-background paragraph grouping still has limitations because cross-block corridors are light-tone oriented; the fix prevents fragmented tiny regions from becoming opaque dots, rather than guessing a merge across dark artwork.

## Tests

New JVM `ReplacementSafetyTest`:

- Full multi-clause source → single character, ellipses, empty/blank, loading placeholder, tiny fragment, truncated ellipsis, JSON-shaped output.
- Valid full Arabic and legitimate short `No!` → `لا!`.
- Real validator boundary: short output and unprotected `iwill` rejected.
- Partial/delayed and stale request identities cannot commit.
- Attachment failure after a successful add rolls back all attempted windows.
- Identity changes during commit roll back; successful commit preserves items.
- Complex background is unreliable; uniform dark background remains eligible.
- Incomplete/low-confidence OCR and translation error blocks cannot replace source.
- Delayed fake translation completes second region first; partial events cannot commit, final results retain original region order.

New native Android tests:

- Render before layout and valid-but-unfit repeated dialogue on a 345×38 black region leave the original bitmap **pixel-identical**; error case also preserves pixels.
- Inject raster failure after background/text preparation: no committed bitmap and original pixels unchanged.
- Exact full report reference dialogue across heights 38/84/180/400 and font scales 1.0/1.4/2.0: complete fit or fully transparent, never scrolling partial output; check readable font and drawn ink.
- Existing overflow test strengthened to require every fallback pixel be transparent. Existing grey-reference assertion now accepts only full-fit replacement or transparent fallback, no longer a scrollable partial paragraph. Prior bidi, glyph, resize, real OCR and cancellation tests retained.

Simulated streaming fragments are tested as incomplete candidate strings/final=false. This does not claim a production streaming API exists. Native WindowManager/Binder timing and GPU/compositor failure on physical Xiaomi remain untested; rollback tests inject attachment failures at the coordinator boundary.

## Performance and limits

No new OCR/model pass, remote service or automatic retry. Fit stays bounded at at most six native layout builds. Private prepared bitmaps are capped at 1,000,000 pixels per region and 4,000,000 pixels per frame (~16MB pixel payload, excluding overhead). Failed preflight never commits a mask. Detached views drop bitmap references without recycling pixels potentially still used by RenderThread.

Safety tradeoff: first replacement waits for full completion, and layout/raster preparation adds bounded main-thread work. This may increase first-overlay latency and memory versus v1.1.20; **no speedup or speed-parity claim**. Physical-device latency, memory pressure, frame rendering and touch tests are still required. Main-thread raster work is bounded, not eliminated.

General semantic quality, speaker/pronoun context, OCR recovery, idioms and fluent manhwa translation remain limited by local ML Kit. No promise of universal semantic completeness, automatic shape reconstruction or visually verified phone behaviour is made.

## Verification / publication

Execution results are recorded after GitHub tests finish. Same signing vault and certificate; no data reset. PR9 remains open; this task does not merge it.

### Verification correction

CI 44745299751 passed. Android runs 44745299837 and 44746056238 exposed a faulty new fixture assumption: the short full Arabic reference can fit 345×38 at the emulator density, so `assertFalse(replacementReady)` was incorrect. The guaranteed-overflow pixel-preservation test now uses a repeated long paragraph; the separate exact-reference matrix still tests the actual dialogue, allowing only complete fit or transparency. No production fit threshold was weakened. Full GitHub rerun required below.

### Passed execution on app/test source `5a9bec6`

- [CI 44746825723](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44746825723): compilation, unit tests and debug APK passed.
- [Android 14 44746825783](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44746825783): native instrumented suite passed, including new pixel-preservation, injected raster failure and reference-dialogue/scale matrix, plus existing OCR/bidi/glyph/resize tests.
- These are programmatic assertion results, not a manually inspected physical-phone screenshot. No device speed benchmark was run. Release metadata changes do not alter tested app code.
