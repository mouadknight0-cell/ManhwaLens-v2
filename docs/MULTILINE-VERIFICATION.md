# Multi-line reading and multi-line display — verification report

Scope: the only two defects in this task.

1. Detect and read long multi-line text, especially 3/4/5-line bubbles.
2. Let a translation flow over 3–4 (at least 4) lines inside the bubble without cutting
   words, shrinking the font to illegibility, or overflowing.

No new model, no LLM, no new native dependency, no model files. RC4's existing translation
engine is used unchanged.

---

## 1. Starting point and what was inspected vs modified

| Item | Value |
| --- | --- |
| Starting commit (RC4) | `760707d3dda4fd528be68620c591f9af9be31497` |
| Baseline lock (tree byte-identical to RC4) | `5285de96e030666375e4dbd69b9b4a4602b527b2` |
| Branch head reported here | `d304548` on `arena/f6ce5fe4-manhwalens-v2` |
| Files inspected | the 30 files in `pipeline/`, the 16 in `overlay/`, `ocr/ColorRecovery.kt`, `ocr/TextRecognitionClient.kt`, `capture/ScreenCaptureService.kt`, `util/BitmapUtils.kt` |
| Files modified | **4 of those** (see below), plus tests and CI evidence collection |

Modified production files, all small and surgical:

| File | Change |
| --- | --- |
| `overlay/ReplacementTranslationView.kt` | one shared font ladder, budgeted re-measure at the full interior width |
| `overlay/DirectTranslationLayout.kt` | both ladders replaced by that one shared ladder |
| `ocr/ColorRecovery.kt` | recovery no longer skipped on confident pages; crop scaling from glyph height; word-preserving replacement |
| `ocr/CandidateRecovery.kt` (new) | `losesText` / `missingWords`, the completeness test used above |

**Not touched, as required:** `overlay/BubbleMask.kt`, `PrecisionBubbleTest`'s 14 ms budget,
package id, signing config.

**No forbidden construct was added.** `git diff 5285de9 -- app/src/main` adds no `maxLines`,
no `ellipsize`, no `substring` truncation, and no `.take(n)` on text. The `maxLines=1` at
`BubbleOverlayService.kt:327` is pre-existing chrome styling, untouched, and not the cause of
anything here.

---

## 2. Root cause per issue (verified in RC4 source, not inferred)

Phase 1 was run before any fix, and all 11 hypotheses were checked in the real code. The three
failure classes the brief required distinguishing came out as follows.

**Class 3 — read and translated, but never drawn.** This is the display defect, and it is the
central one. `DirectTranslationLayout.planned` tried only 15/14/13; `ReplacementTranslationView`
then re-measured only 15/14. When neither fit, `layout` stayed `null` and `onDraw` did
`val image = prepared ?: return` — so the bubble showed **nothing at all**. The diagnostic string
`preferred=15 min=14 max=15 no_fit` conflated "no size fits" with "out of sizes to try".

**Class 2 — seen but lost in filtering.** Two defects in `ColorRecovery.recover()`:

- `:61` returned early whenever every region was confident, which skipped recovery **and page
  discovery**. Text the first pass mis-scored was never looked for again.
- `:62` generated page proposals only when *all* regions were weak (`initial.size == weak.size`),
  so one confident block hid a missed line elsewhere on the page.
- `:82` `output.removeAll(same)` deleted **every** overlapping original. A short, clean re-read of
  a crop outscores a long noisy one, so a 2-line re-read routinely destroyed a 5-line
  transcription. This is a completeness failure that no confidence threshold can detect.

**Class 1 — OCR never saw the line.** Partly real: `:73` upscaled by crop **height**
(`r.height()<32 -> 3`, `<=100 -> 2`, else `1`), so a tall bubble containing small glyphs was read
at 1× and its lines blurred together.

**Checked and found NOT defective** — these were left alone rather than "fixed":

- No line or word cap exists in `DialogueAssembler`, `SentenceReconstructor.joinLines`,
  `ArabicDisplay.isolate`, `RegionMerger`, `RegionFilter` or `TranslationInput`.
- `joinLines` is lossless: it joins with `\n`, then flattens to a single space without dropping a
  token.
- RC4's grouping and `ArabicLayoutPlanner` scoring already satisfy the multi-line properties. This
  was pinned by tests committed *before* any fix (`2072fbf`), which passed on RC4 — so the defect
  was never in grouping or scoring, and neither was changed.

---

## 3. The fix, and why it is the smallest architectural change

**Display.** One ladder (`15,14,13,12,11`) is now the single source of truth, and the view
re-measures it at the **full interior width** before ever giving up. A wider column needs fewer
lines and a shorter block, so it is strictly more likely to fit the measured height than the
optimizer's narrower guess. Below 11 sp is genuinely illegible, so the floor is stated rather than
extended indefinitely; at the floor the bubble reports that it could not fit instead of drawing
nothing.

Measurement is budgeted (`MAX_FIT_PASSES = 6`, matching an existing regression guard), because
measurement happens on the main thread and a resize can arrive mid-scroll.

**Reading.** Recovery now runs where the page is confident but the detector proposes text the
result does not cover, and the early return requires the detector to propose nothing new. Candidates
can no longer evict an original that holds a word they lack (`CandidateRecovery.losesText`), and
newly discovered text is added rather than discarded. Upscaling uses median glyph-line height rather
than crop height, so a tall bubble of small glyphs is no longer read at 1×.

---

## 4. Measured results

All figures below are from CI runs on the reported head. Unit job and device job both **success**.

### 4a. Device matrix (real on-device OCR client, emulator API 34 x86_64)

Emitted as one `MATRIX-SUMMARY` line by `MultiLineCaseMatrixTest`:

| Case | Source lines | OCR regions | Missing source words | Region span | Translated length | Lines drawn | Block height | Clipped at 150 px box |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3-line | 3 | 1 | **0** | 120 px | 111 | 3 | 89 px | false |
| 4-line | 4 | 1 | **0** | 166 px | 143 | 4 | 118 px | false |
| 5-line | 5 | 1 | **0** | 213 px | 153 | 4 | 118 px | false |

Read that table with one caveat stated plainly: the detector returned these as **one paragraph
block** (`ocr-audit detected blocks=1 lines=4`), so the assembler had nothing to bridge and
`sourceLineCount` is 1 in those cases. The lines *were* all read — no word is missing and the span
covers every line — but these rows therefore demonstrate that no line is lost, not that block
bridging was exercised. Bridging is exercised separately by the geometry case (2 blocks → 1:
`ocr-before-assembly count=2` → `ocr-after-assembly count=1`, keeping all three lines in order).

The 5-line case drawing 4 lines is correct, not a defect: the same sentence at a 300 px column fits
in 4 lines once laid out, and `missingWords=0` confirms nothing was dropped.

### 4b. Display (Arabic, real `StaticLayout`)

| Column | Lines | Complete (no ellipsis, no clip) |
| --- | --- | --- |
| 320 px | 3 | true |
| 260 px | 4 | true |
| 220 px | 5 | true |

Every readable size draws the whole translation, so the ladder never silently drops text:

| Size | 15 sp | 14 sp | 13 sp | 12 sp | 11 sp |
| --- | --- | --- | --- | --- | --- |
| Lines | 4 | 3 | 3 | 3 | 3 |
| Complete | true | true | true | true | true |

Translation longer than its source, in a 300×520 box:
`ready=true fittedSp=15.0 fitPasses=1 fits=true`.

### 4c. Timing (Phase 5)

`TIMING case=fourLinePage emulator=true samplesMs=[3897, 3915, 3992, 4154, 4204] medianMs=3992 budgetMs=5000 withinBudget=true note=emulator_only_not_phone_not_end_to_end`

**This is an emulator figure for the OCR read only.** It is not end-to-end latency and it is not a
phone measurement; the 5 s target is a goal and is **not** claimed as met on real hardware.

### 4d. Suite totals

| Suite | Count |
| --- | --- |
| JVM tests | 341 (`@Test` annotations; RC4 had 311) |
| Instrumentation tests | 52 |
| New JVM tests | 30 (`CandidateRecoveryTest` 12, `MultiLineLayoutTest` 11, `MultiLineCompletenessTest` 7) |
| New instrumentation tests | 22 (`MultiLineDisplayTest` 9, `MultiLineCaseMatrixTest` 13) |

---

## 5. Failures met along the way (not hidden)

Every one of these was found by running the device suite, and each is recorded because a report
that only lists successes is not evidence.

| Failure | Real cause | Resolution |
| --- | --- | --- |
| `dexBuilderDebugAndroidTest` failed | backticked test names; D8 rejects spaces below DEX 040 | androidTest names are camelCase (JVM keeps backticks) |
| Compile error at `DirectTranslationLayout.kt:172` | re-assigned generic `var` collection | explicit `List<Pair<…>>` type |
| `colourRecoveryAfterEmptyFirstPass` failed | **my regression**: `proposals.all{}` is vacuously true when nothing is detected, so recovery was skipped on the empty first pass | guarded on `initial.isNotEmpty()` |
| Same test failed again | **my regression**: requiring an original to be covered meant an empty first pass added nothing | a hit with no overlapping original is new text and is added |
| `fitPasses <= 6` guard broke | widening the ladder pushed passes past the guard | budget set to 6 by removing duplicate width sweeps, guard **not** weakened |
| 3-line-at-480 px assertion failed | **my test was wrong**: 15 sp Arabic fills ~66 chars per 480 px line, so 132 chars is 2 lines | measured at 320/260/220 px, plus the real invariant (narrowing never reduces lines) |
| "OCR lost source words [down], [staying here]" | **my fixture was wrong**: a fixed 520 px canvas clipped the last glyph onto the edge | canvas sized from `pen.measureText` |
| `sourceLineCount must record all lines expected:<5> but was:<1>` | **my test over-claimed**: the recogniser legitimately returns one paragraph block | asserts zero missing words + full vertical span instead |
| "lost [i, never]" → read as "Inever" | recogniser's spelling of a lone capital `I`; nothing added rewrites OCR text | case uses a normal sentence; logged, not suppressed |

`PrecisionBubbleTest`'s 14 ms measurement (`phase5 budget_pass=0 outcomes=[accepted, accepted]
elapsed_us=22355`) is **pre-existing** and unchanged. It reports to `ManhwaLensFunnel` and does not
fail the build; the 14 ms budget and the file were not modified.

---

## 6. Acceptance conditions (the 12), with evidence

| # | Condition | Status |
| --- | --- | --- |
| 1 | No source line lost | **Met** — `missingWords=0` for 3/4/5-line, plus word-level loss tests |
| 2 | Grouping correct, whole-unit translation | **Met** — 2 blocks → 1, order preserved; two bubbles stay two |
| 3 | Distinct bubbles never merged | **Met** — `twoBubbles regions=2 separate=true`, with per-bubble word checks |
| 4 | Bubble detection not a precondition for reading | **Met** — reading path takes regions directly; recovery now adds discovery regions |
| 5 | 3–5 line bubbles read in full | **Met** — spans 120/166/213 px, zero missing words |
| 6 | Translation flows over ≥4 lines | **Met** — 260 px → 4 lines, 220 px → 5 lines, both complete |
| 7 | No word cut, no `maxLines`, no `ellipsize`, no `substring` | **Met** — none added (`git diff` verified) |
| 8 | Font never illegible | **Met** — floor is 11 sp, asserted as `fittedSp >= 11` |
| 9 | Arabic words/shaping/bidi intact | **Partly** — every ladder size lays out complete Arabic text and word-boundary tests pass; **no bidi visual-order verification on a phone** |
| 10 | No reprocessing of unchanged bubbles, background only | **Not expanded** — unchanged from RC4; recovery caching and the `Vote`/`Track` mechanism still gate re-reads |
| 11 | ≤5 s/screen | **Not claimed for a phone** — emulator median 3992 ms for the OCR read alone |
| 12 | Build success alone is not evidence | **Respected** — every claim above cites a measured number |

---

## 7. Phone verification — what is and is not verified

**Not verified on a phone.** Everything here ran on the CI emulator, and the report says so in each
measurement.

Verified on device (emulator): reading of 3/4/5-line bubbles, grouping, two-bubble separation,
Arabic display over 3/4/5 lines at five font sizes, translation longer than source, real
`xiaomi-three-lines` fixture, and reading cost.

**Not verified anywhere:** real Xiaomi hardware behaviour, real screen-capture frames (all reading
evidence uses generated and bundled fixtures), bidi visual order as a reader would see it, and
sustained scrolling performance under the overlay.

---

## 8. Remaining risks

1. **The device matrix reads paragraph blocks, not bridged lines.** For those cases the detector
   bypassed the bridging path. Bridging is covered by a separate case, but on real pages the
   recogniser's block segmentation will vary and can behave differently from any fixture.
2. **`sourceLineCount` is not a reliable "lines read" counter.** It counts bridged blocks. The span
   and word checks are the trustworthy signals, and anything relying on `sourceLineCount` downstream
   should not treat 1 as "one line of text".
3. **Recovery now runs on more pages.** Budgets are split (3 weak + 2 discovery) and the emulator
   stays inside the 5 s goal, but a busy real page has more components than any fixture and could
   reach the budget limit more often.
4. **The 11 sp floor is a judgement, not a measurement.** Nothing measured reading comfort; 11 sp is
   asserted as a floor rather than proven legible on a small phone.
5. **`fitPasses` is an assertion on a number, not a latency bound.** Six measurements remain under
   the guard, but the guard does not prove the frame stays smooth.
6. **No end-to-end production path was exercised.** `ScreenCaptureService` was read, not driven, so
   the integration from capture to overlay is unchanged and unmeasured here.
