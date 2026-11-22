# Translation coverage, bubble layout and collision — root-cause fix

Scope: implementation pass on branch `arena/f6ce5fe4-manhwalens-v2`. Nothing in this document
is a device or release claim. Every "verified" statement below is either a code fact (file and
symbol) or a unit-test expectation that a JVM/Android run must still confirm.

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

## 4. What is NOT verified here

- No local JDK/Android SDK and no Maven/Google access in this environment: **no compile, no unit
  test, no instrumented test and no APK was run here**. Verification is delegated to GitHub Actions
  (`ci.yml` for compile + `testDebugUnitTest`, `quality.yml` for the emulator suite).
- No physical device session: bubble geometry, plate readability on real artwork, RTL glyph
  bounds under a real mask, and end-to-end latency are unmeasured.
- Semantic translation quality is unchanged by design (ML Kit, offline); nothing here claims
  better meaning, only better coverage/layout/rendering.
