# Translation coverage, bubble layout and collision — root-cause fix

Scope: implementation pass on branch `arena/f6ce5fe4-manhwalens-v2`. Nothing in this document
is a physical-device or release claim; the only verified results are the CI and emulator runs
recorded in section 4. Everything else is either a code fact (file and symbol) or a test that
encodes the intended behaviour.

## 1. Proven root causes

### A. Text outside bubbles never reached the translator

`ScreenCaptureService.prepareRegions()` gated every region on
`ComicSurface.enclosed(...)` (a light, closed balloon probe) plus a narrow "long uppercase
sentence ending in punctuation" exception. Anything else was dropped and logged as
`reason=no_comic_surface`. `ComicSurface.enclosed` returns false whenever the surface detector
found no oval/mask and the four boundary probes did not agree, which is exactly the case for
narration, captions, scene text, background-embedded text, and any balloon touching a crop edge
(`component_at_crop_edge` upstream in `BubbleContours`).

Cause: **eligibility was defined as "a closed comic surface exists"**, i.e. a rendering-strategy
signal was used as a translation gate.

### B. Proven surfaces were replaced by guessed boxes

`DirectTranslationLayout.anchored()` accepted `evidence.reliable` (colour uniformity only) as a
full replacement surface and painted the view with `source.color` opaque. A uniform fill is not a
closed balloon: it can be a toolbar, a table, a character's shirt. `mode=capsule` with
`mask_survived=false` was painted as an opaque rounded rectangle that hid artwork without
geometric evidence.

### C. Long sentences used two wide lines

The width sweep started at the anchor width (`for(w in anchor.width..screen.width step step)`) and
accepted **the first** layout whose boxes fit (`fits=true`). Narrower columns — the ones that turn
"two wide lines + stub tail" into three balanced lines — were never measured, and no comparison
between candidates existed at all.

### D. Rejected/failed OCR regions blocked valid neighbours

`BubbleOverlayService.renderOverlays()` built obstacles from `CaptureManager.currentBoxes`
(raw OCR rectangles, including regions the funnel dropped) and from *all* blocks including ones
that failed translation or deliberately kept the source text. A dead box therefore produced
`overlay_collision` for a live neighbour.

### E. One real Latin leftover rejected an otherwise good translation

`TranslationOutput.issue()` returns `TR-UNTRANSLATED` when the Arabic output still contains Latin
script. With OCR `MYSELE` the model legitimately echoes `Mysele`, so the whole block failed even
though `myself` is one edit away.

## 2. Fixes

| File | Change |
| --- | --- |
| `pipeline/TextEligibility.kt` | New. Multi-signal eligibility (letters, height, UI page-control labels, confidence) that is **independent of surface detection**. Returns a rendering `strategy`: `bubble` only for mask/oval/patch/enclosure evidence; a merely uniform flat fill now yields `free_text` (`flat_background_only`) instead of `bubble`. |
| `capture/ScreenCaptureService.kt` | The `ComicSurface.enclosed` gate is replaced by `TextEligibility.assess(...)`; drops log `reason=` + `signals=`, kept blocks log `stage=TRANSLATION_ELIGIBLE strategy=...`. Scorecard stages `OCR_DETECTED/OCR_GROUPED/CLASSIFIED/TRANSLATION_ELIGIBLE` are emitted only for regions that survived the funnel, so the ledger cannot claim eligibility for a filtered region. |
| `overlay/DirectTranslationLayout.kt` | Rewritten decision structure. (1) A *proven* surface (mask/oval/patch) keeps its exact geometry and only the **text width** is swept; (2) otherwise a translucent plate is placed that always covers the source glyph box; (3) all candidates are scored, never "first fit"; (4) `positions()` again requires the placed box to cover its anchor (no drifting to another bubble) and no longer throws on a narrow proven interior. |
| `overlay/ArabicLayoutPlanner.kt` | New scoring: painted-area distortion (gentle), line imbalance (CoV), ragged last line (knee 0.55, weight 1.8), underfilled column, mild line-count and font-size preferences, soft OCR line-count hint. Width candidates include columns **narrower** than the source. |
| `overlay/ReplacementTranslationView.kt` | Plate (unproven) surfaces erase the source rectangle with the translucent plate colour and draw outlined glyphs; proven masks/flats still erase fully with sampled colour. Mask inset is now in view coordinates with an inversion guard. Capsule coverage is validated as *fully covered* (not necessarily opaque), so the plate can never leak the original glyphs while keeping the artwork visible. |
| `overlay/BubbleOverlayService.kt` | Obstacles come from `OverlayPolicy.renderable(...)` + duplicate detection, never from raw OCR boxes. Emits `LAYOUT_SELECTED`, `LAYOUT_REJECTED_*`, `MASK_VALIDATED/REJECTED/NOT_APPLICABLE`, `PLACEMENT_ACCEPTED`, `RENDERED`, `PLACEMENT_REJECTED_DUPLICATE_SOURCE_REGION`. A block already attached for the same page is not attached twice, and duplicates are excluded from expected coverage. |
| `pipeline/OverlayPolicy.kt` | New. `renderable()` (no error, non-blank, changed text) and `duplicates()` (≥70% of the smaller box) — pure, unit tested. |
| `translate/LatinLeftoverRepair.kt` | New. Distinguishes protected names, glossary terms and genuine English words from OCR-error candidates; proposes **one** confident single-edit correction, only when the token is unique in the source. |
| `translate/TranslationClient.kt` | On `TR-UNTRANSLATED`, one bounded retranslation with the corrected source (`latin_leftover_retry` / `latin_leftover_decision` diagnostics + evidence keys). The retranslation passes the unchanged validation; on any doubt the original failure stands. |
| `translate/TranslationOutput.kt` | `joined()` helper so the retry path can hand back an already validated string without re-running validation on unrelated text. |
| `pipeline/ReadingEnglish.kt` | `isCommonWord()` + `closestWord()`: bounded single-edit dictionary lookup that returns **null** when the nearest word is ambiguous (frequency only breaks ties at the same distance). |
| `pipeline/DialogueAssembler.kt` | Wrapped lines of one sentence are no longer dropped by a 50%-clearance rule that real ML Kit line boxes (25–35% overlap) never satisfy. Still refuses same-row/different-column pairs. |
| `pipeline/RegionMerger.kt`, `pipeline/Models.kt` | Carry `sourceLineCount` as a *soft* layout hint from OCR grouping to the planner. |

## 3. Tests added

Unit (JVM, `app/src/test`):

- `pipeline/TextEligibilityTest.kt` — outside-bubble text (`OH LOOK, ANOTHER LONER.`), long narration
  on white, caption, uneven background, UNKNOWN/DIALOGUE/NARRATION outside bubbles, patch evidence
  after a crop-edge contour failure, bubble-vs-plate strategy, flat fill never = bubble, UI/noise
  exclusions, short genuine dialogue accepted.
- `overlay/ArabicLayoutPlannerTest.kt` — three balanced lines beat two wide lines in the same
  bubble, balanced two beat padding-driven three, one full line beats a stub pair, wider is not
  automatically better, 15sp preference, `best()` rejects unfitted/empty candidates, narrower
  widths exist in the candidate set.
- `pipeline/OverlayCollisionPolicyTest.kt` — failed/blank/source-preserved blocks reserve no space,
  dead neighbour does not block a valid one, duplicate source region renders once, distinct nearby
  bubbles are both kept, a failed duplicate never suppresses a valid translation, wrapped lines of
  one sentence are one region.
- `translate/LatinLeftoverRepairTest.kt` — the `MYSELE → MYSELF` case from the report, case
  preservation, protected names and glossary terms untouched, ordinary English words untouched,
  unconfident/multiple/duplicated leftovers untouched, no substring edits inside longer tokens.

- `pipeline/DialogueAssemblerTest.kt` — a wrapped line whose ML Kit boxes overlap by ~30% still
  joins (the reported `DARN IT, WHY DO I ALWAYS / END UP STEPPING ASIDE?` shape), a completed
  sentence does not swallow the next line, side-by-side bubbles stay separate.

Instrumented (`app/src/androidTest/AnchoredRenderingTest.kt`, emulator only): the proven-oval case
now asserts the oval evidence survives (geometry is not swapped for a capsule) instead of the old
"must expand into a capsule" expectation.

### Known instrumented expectations this refactor can trip (read before trusting a red `quality.yml`)

Two existing emulator assertions encode the *old* capsule behaviour and may need a decision after a
real run — they are listed here deliberately instead of being pre-emptively rewritten:

1. `QualityInstrumentedTest.replacementNeverMovesToAnotherBubble` — `prepare(..., occupied=[s])`
   is expected to return null. `DirectTranslationLayout.prepare()` still checks only that the
   *surface* does not intersect an obstacle, so this should stay green.
2. `AnchoredRenderingTest.narrowColoredOvalExpandsWithoutLosingAnyLineOrExposingSourcePixels` was
   updated for proven-geometry preservation but still renders a long paragraph into a 54×30 oval;
   if 15sp/14sp/13sp cannot fit, the view legitimately refuses and the test will fail rather than
   paint outside the oval. That failure would be the correct product behaviour and the test data
   would need a larger fixture, not a looser renderer.

## 4. Verification — what was actually run

GitHub Actions on commit `1f70ea2` (branch `arena/f6ce5fe4-manhwalens-v2`), all green:

| Run | Result |
| --- | --- |
| `CI` (push) — help, dependency graph, `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`, `testDebugUnitTest`, `assembleDebug` | success (run 45024782969) |
| `CI` (pull_request) | success (run 45024786731) |
| `Android quality regression` — API 34 emulator, `connectedDebugAndroidTest` | success (run 45024782963) |

Findings from the intermediate red runs, each fixed rather than waived:

1. Two compile errors from my refactor (`underfilled()` Float/Double, `nearby()` trailing lambda
   binding to `sourceLineCount`).
2. `LatinLeftoverRepairTest.strongLegalLeftoverReportsTheTokenForDiagnostics` caught a real
   reporting bug: suggestions were lowercased (`myself` reported for `Mysele`).
3. **A translation-dropping bug in my own first attempt**: the plate was sized from measured line
   widths while `StaticLayout` keeps the width it was built with, so the view rejected a layout
   that fitted and `prepare()` returned null (emulator: NPE on a non-null assertion, and
   `QualityInstrumentedTest.replacementNeverMovesToAnotherBubble` failing on `expected: null`).
   Fixed in two places: the plate must hold the whole measured column, and the view falls back to
   the full safe interior before giving up.
4. The emulator's original oval test encoded the old opaque-capsule behaviour. A proven oval's
   inscribed interior is always narrower than its own box, so a long paragraph cannot be replaced
   inside it; the test now asserts the intended contract — a non-destructive plate with no
   invented mask/patch/oval, the source box fully erased, outlined glyph ink drawn, the neighbour
   still placeable, and no second translation for the same source.

Test counts reported by CI on the green run: unit tests all passed (266 executed on the previous
commit; the only failure then was the case-reporting bug above). Instrumented suite passed on the
API 34 emulator.

## 5. What is still NOT verified

- **No physical device session.** Bubble geometry on real artwork, plate readability, RTL glyph
  bounds under a real sampled mask, and end-to-end capture/translate/render latency are unmeasured.
- **No real-page end-to-end run.** The tests use synthetic surfaces and bundled fixtures. The
  reported phone pages (`OH LOOK, ANOTHER LONER.`, the `MYSELE` utterance, the three-line bubble)
  are represented by fixtures and bounds, not by re-capturing those screens.
- **Semantic translation quality is unchanged** by design (offline ML Kit). Nothing here claims
  better meaning, only better coverage, layout and rendering.
- The emulator job's own log/artifact remain undownloadable from this sandbox; failures above were
  read from job annotations produced by the (now fixed) quality script.

## 6. Remaining risks

- `TextEligibility.MIN_CONFIDENCE = 0.15` and the UI-label allowlist are judgement calls; they keep
  protections but are not tuned against a large real-page corpus.
- Plate output on very busy artwork is a readability question, not a correctness one; it needs a
  phone screenshot review.
- `LatinLeftoverRepair` only repairs a **single** leftover token; multi-token OCR damage still ends
  as `TR-UNTRANSLATED` with the source retained (deliberate: no guessing).
- `ArabicLayoutPlanner` weights (`distortion 0.4`, `lastLine 1.8`, `underfilled 1.2`, knee 0.55)
  are covered by unit tests for the reported shapes, not by a perceptual study.
