# Phase 1 — Deep translation-engine investigation and context-engine architecture

Date: 2026-11-23. Branch `arena/f6ce5fe4-manhwalens-v2`. **No application code was modified.**
This document is the deliverable; nothing below is implemented.

---

## 0. Evidence status — read this first

Three of the instruction's premises could not be verified against the repository, and the
difference matters for how much of this report is proven versus quoted.

| Premise in the brief | What the repository actually contains |
|---|---|
| "the available RC7 phone reports, benchmark data, samples, and diagnostics" | **There is no RC7 phone report in the repository.** No file mentions `1.1.42-rc7` except the release note written in the previous phase. |
| Named RC7 failures: `KALLION`, `JANSK`, `THEVRE`, `Oh my God`, `Vegetative`, `Crown prince`, `HOUSE`-for-`YOUVE` | **None of these strings exist anywhere in the repo** (docs, corpus, tests, or source). Verified by search. |
| "locked" RC7 baseline results to compare against | No committed RC7 benchmark of translations or OCR output exists. |

What *does* exist, and is used below instead:

- `docs/PHONE-REPORT-1.1.36.md`, `docs/PHASE-4.4-REPORT.md`, `docs/INDEPENDENT-QUALITY-AUDIT.md`,
  `docs/TRANSLATION-ENGINE-BASELINE-AUDIT.md` — real, dated, committed investigations.
- `evaluation/corpus/rc4-corpus.json` — 40 cases with an independent Arabic reference,
  `ocr_quality`, `semantic_category`, `error_reason_codes`, and `reference_status`.
- 67 unit-test files (~347 `@Test` methods), 8 instrumented-test files, 331 Python tests.

**Consequence.** Every root cause below is traced to code I read and, where marked
*proven*, to behaviour I reproduced by faithfully re-implementing the Kotlin algorithm
against this repository's own vocabulary file and inputs. Nothing is claimed as a
"confirmed RC7 failure" because the RC7 phone report was not supplied. Where a claim
depends on a specific captured screen, it is labelled **hypothesis**.

If the RC7 report exists outside the repository, supplying it will let Part 3 be tightened
from *code-traceable* to *observation-confirmed*. I did not invent it.

### 0.1 Baseline verification (verified)

| Item | Value |
|---|---|
| RC7 tag `rc-1.1.42-rc7` → commit | `5160ef6dc73a3f71bae5f779ac8bd423d81fcb0c` |
| Branch HEAD | `a7b870c23aca6bdb4c7e3007817f65edf3e3b26b` |
| Difference | **one commit, docs only** (`docs/TRANSLATION-EVALUATION-PHASE-B2.1.md`, +95 lines) |
| `app/` tree hash at RC7 vs HEAD | `fb5457329d1dfdfb1c063756f1c751c0e702b69b` — **identical** |
| `.github/` tree hash at RC7 vs HEAD | `f02ab903d89ac9573f3b77768331fbb7a9f385eb` — **identical** |
| Working tree | clean |
| `stash@{0}` | intact, preserved foreign work, untouched |
| Installed APK | versionCode `217442875` (older than RC7's `217442877`) |

So: **the source under investigation is byte-identical to the RC7 build for all
application code.** The only divergence is documentation. Baseline is usable.

Local baseline runs (this sandbox, no JDK/SDK/emulator):

```
python3 -m unittest discover -s .github/tests   -> 331 tests, 2 errors
   (both are module-level `cryptography` imports; the module is absent here, present in CI)
python3 -m scripts.translation_eval validate    -> 40 cases, 0 errors, 55 warnings, 37 matrix gaps
```

The Kotlin suite (347 unit tests) **cannot be executed here** — no JVM. CI runs it and RC7
reported `67 suites / 350 tests / 0 failures`. That number is quoted, not reproduced.

---

## 1. The actual pipeline

Traced from `ScreenCaptureService.captureFrame` (1,096 lines) outward. Every arrow below is
a real call, in order.

```
[1] MediaProjection frame  → ScreenCaptureService.acquireFreshFrame
     ImageReader → Image → imageToBitmap (934 lines of service; scene revision guards)
     gate: LiveFrameGate, SceneEpoch.commit(frameRevision)  ← stale frames throw CancellationException
        ↓
[2] frame.downscaleTo(OCR_MAX_DIMENSION)                     ocrBitmap
        ↓
[3] ColorRecovery.recognize(...)  ← ocr/ColorRecovery.kt (182 l)
     multi-pass OCR: original + colour-inverted/binarised crops
     CandidateRecovery.losesText(old,new) vetoes replacing a longer text with a shorter one
        ↓ List<TextRegion>  (raw text preserved in .text; .confidence is ML Kit's)
[4] SourceScreenGuard.isOwnInterface / ReaderContentFilter.isTabOverview / isLoadingPage
        ↓ (drop frames that are the app's own UI, browser chrome, or loading pages)
[5] scale regions back to frame coordinates
        ↓
[6] prepareRegions()  → RegionFilter → TextEligibility → RegionClassifier(RegionKind)
        ↓
[7] FrameTranslationPlan.build(inputs, readerContentBottom)   ← ORDER + grouping for translation
     includes RegionMerger.merge (vertical gap 28px, horizontal overlap 25%, max gap 48px)
     and DialogueAssembler.assemble (bridgeIsClear visual test)
        ↓
[8] per-region: TranslationClient.translateBlock
     8a TranslationInput.prepare(raw, protectedNames, withNames=true)
          detect names on RAW text → SentenceReconstructor.normalize
          → NameShield.mask → prepareRepair:
               EnglishOcrRepair.repair → OcrLexicon.repair → ReadingEnglish.repair
          → NameShield.restore
     8b gates (reject BEFORE the model ever sees the text):
          ReadingEnglish.fragmented / validCount<2 / surface unreliable + conf<.62
          / confidence<.5 / OcrInputQuality → OCR-UNCERTAIN
     8c DialogueLexicon.interjection / exact-glossary / match  (may supply text with no model call)
     8d ReadingGlossary.modelMask → TranslationInput.nativeInput  ← TEN regex rewrites of the English
     8e ML Kit on-device translate (LanguageModels.ensureReady; download once)
     8f NameShield.restore → LatinLeftoverRepair → TranslationOutput.issue/finish
        ↓ TranslatedBlock(region, original, translated, errorCode, method)
[9] OverlayPolicy.renderable / duplicates(threshold 0.7 by IoU of region boxes)
        ↓
[10] DirectTranslationLayout / ArabicLayoutPlanner → overlay/BubbleOverlayService renders
        ↓ outcome: VISIBLE_IN_IMAGE / overlay_collision / out_of_bounds / text_capacity /
                   mask_defect / clipped → RENDER_FAILED / STALE_RESULT / CANCELLED
[11] BenchmarkTracker (T3..T8, M0/M1) + SessionScorecard + Diagnostics (counts & codes only)
```

### 1.1 Stage table

| # | Stage | Files | Threshold / rule | Failure mode | Tests |
|---|---|---|---|---|---|
| 1 | Acquisition | `CaptureManager`, `LiveFrameGate`, `SceneEpoch` | revision must match | stale frame → cancel | `CapturePhaseTest`, `LiveFrameGateTest`, `SceneLifecycleTest` |
| 2 | Downscale | `ScreenCaptureService` | `OCR_MAX_DIMENSION` | — | — |
| 3 | OCR + colour recovery | `ColorRecovery`, `TextRecognitionClient`, `CandidateRecovery` | `losesText` veto | drops crop | `CandidateRecoveryTest`, `ColorDialogueRegressionTest` |
| 4 | Content guard | `SourceScreenGuard`, `ReaderContentFilter` | string markers | frame skipped | `SourceScreenGuardTest`, `ReaderContentFilterTest` |
| 6 | Eligibility/classify | `RegionFilter`, `TextEligibility`, `RegionClassifier` | per-rule | region dropped | `RegionFilterTest`, `TextEligibilityTest`, `RegionClassifierTest` |
| 7 | Merge / assemble | `RegionMerger`, `DialogueAssembler` | gap 28px; overlap ≥25%; bridge clear | fragment survives / wrong merge | `RegionMergerTest`, `DialogueAssemblerTest`, `BubbleUnitTest` |
| 8a | Repair | `TranslationInput`, `EnglishOcrRepair`, `OcrLexicon`, `ReadingEnglish`, `ComicSpelling` | see §3 | **word replaced with a different word** | `ReadingEnglishTest` (3 tests only) |
| 8b | Pre-model gates | `TranslationClient` L73–80 | `validCount<2`, conf<.5/.62 | readable text → `OCR-UNCERTAIN` | indirect |
| 8d | Source rewrite | `TranslationInput.nativeInput`, `ReadingGlossary.nativeSentence` | 10 + 6 regex rules | meaning altered before translation | `IndexedDialogueTest` |
| 8e | Translation | `LanguageModels`, ML Kit `17.0.3` | — | literal idioms; no prompt control | device only |
| 8f | Validation | `TranslationOutput`, `ReplacementQuality`, `ArabicOutput` | script/count checks | `TR-UNTRANSLATED` | `LatinLeftoverRepairTest` |
| 9 | Dedup | `OverlayPolicy` | IoU ≥ 0.7 | fragments both render | `OverlayCollisionPolicyTest` |
| 10 | Render | `DirectTranslationLayout`, `BubbleOverlayService` | capacity/collision | `RENDER_FAILED` | 8 overlay test files |

**Information loss, precisely.** Raw OCR survives in `TextRegion.text` and in `TranslatedBlock.original`.
The *repaired* text is what the model receives, and the rewrite in 8d is applied to it. So the
system always retains what OCR read, but the model may be answering a different sentence than
the one on screen — and nothing downstream compares the two for meaning (§3.1).

---

## 2. Root-cause matrix

Confidence: **proven** = reproduced by re-implementing the Kotlin against this repo's data;
**traced** = read directly from source; **hypothesis** = mechanism identified, specific screen not available.

| # | Issue | Evidence | Root cause | Responsible file / function | Proposed fix | Confidence |
|---|---|---|---|---|---|---|
| 1 | Correct word replaced by unrelated word (`YOUVE`→`HOUSE`) | `ComicSpelling.domain` contains `house`, `enough`, and **not** `you`/`you've`; `YOUVE` is length 5, absent from the 18,000-word ranked list; `ReadingEnglish.repair` routes any absent 4–18-char token to `ComicSpelling.suggest` | A 20-word **domain** word list is used as a *fallback spell-corrector* for the whole vocabulary. `suggest` deletes up to 2 chars and accepts a unique domain word within edit distance 2 of a common English contraction. | `pipeline/ComicSpelling.kt` (`suggest`, `domain`); called from `pipeline/ReadingEnglish.kt:200` (`repair`) | Remove `ComicSpelling` from the `repair` fallback chain for tokens that are valid contractions; restrict the domain list to tokens that pass a contraction/possessive check. Never let a 20-word list adjudicate general English. | **proven** |
| 2 | Same bug hits `YOU'RE` | `YOURE` (list rank 13295) → `house` | As above | same | same | **proven** |
| 3 | Romanised/`:vre`-type corruption survives | `THEVRE` → unchanged | Only *unique* matches are accepted by design (`hits.singleOrNull()`, `suggest`'s `singleOrNull`), so ambiguity correctly refuses. The residual problem is downstream: the token then fails `validCount<2` and the block becomes `OCR-UNCERTAIN` — the sentence is lost even though "THE VRE"→"THERE" was guessable from context. | `ReadingEnglish.repair`, `TranslationClient` L76 | Do **not** loosen the uniqueness rule. Instead keep the block and pass it to the model with an uncertainty marker, and let sentence-level context decide (Part 5). | traced |
| 4 | Readable text rejected | `TranslationClient` L73–80: `validCount(normalized)<2` OR (`surface.reliable==false` AND `confidence<.62`); then `confidence<.5` | Three independent gates, all *pre-model*, all lexical/confidence based. A short correct line ("Oh my God!") can score `validCount<2` (proper noun + interjection) and be discarded before translation. | `translate/TranslationClient.kt` L69–80 | Replace boolean rejection with a graded decision: translate anyway, mark `input_uncertain`, and let post-translation meaning checks (§3.2) decide. Never drop a block whose words are real English. | traced |
| 5 | One stage accepts, a later stage rejects | Accepted by repair, rejected by gate: repaired text is *not* re-scored by the repair's own predicates. `ReadingEnglish.census` counts what happened but is used for diagnostics only. | No feedback loop. Each stage has its own predicate over different text (raw vs repaired vs normalized). | `TranslationInput.prepare` vs `TranslationClient` gates | Single explicit "input confidence" object produced by repair and consumed by the gates; each gate records which predicate fired. | traced |
| 6 | Fragment duplicates | `OverlayPolicy.duplicates` is the **only** text-level dedup and it runs on *translated blocks* using **region-box IoU ≥ 0.7** | Duplicate suppression happens after translation, by geometry only. Two overlapping OCR detections of the same sentence with IoU < 0.7 are both translated and both rendered; a fragment that is *contained* in a bigger box is never compared by text. | `pipeline/OverlayPolicy.kt` `duplicates()` | Add a **pre-translation text-level** dedup: containment + token-overlap of `TextRegion.text`, on raw OCR, before `FrameTranslationPlan`. Keep the geometric check as a second net. | **proven (code)** |
| 7 | Literal idioms / lost negation | `TranslationInput.nativeInput` + `ReadingGlossary.nativeSentence` = 10 + 6 fixed regex rewrites applied to English before the model (`blindEye`, `heavy`, `obligation`, `timeObligation`, `initiative`, `exclusiveQuestion`, `chivalrous`, `travel`, `certain`, `cityConfusion`, + 5 glossary patterns) | Meaning is being patched by narrow string substitution instead of understood. Each rule is independent of the sentence; several can fire in one bubble; none is verified afterwards. The previous phase's audit reached the same conclusion independently. | `pipeline/TranslationInput.kt`, `translate/ReadingGlossary.kt` | Retire the regex rewriting. Replace with sentence-level analysis + terminology memory (Part 5). Until a replacement exists, keep them — removing them without a substitute is a regression. | traced |
| 8 | Untranslated clear bubbles | `TranslationOutput.issue` → `TR-UNTRANSLATED` when any Latin remains or Arabic letter ratio insufficient; `ReplacementQuality` same | Validation is **script-shape only** (`raw.any{LATIN}`, `arabic*5<letters*2`). It cannot tell "did not translate" from "translated with a Latin proper noun retained", nor detect a fluent Arabic sentence that dropped the negation. | `translate/TranslationOutput.kt` L31, `ReplacementQuality.kt` L15–18 | Add meaning-level checks (negation count, named-entity retention, number retention) alongside script checks. | traced |
| 9 | Layout rejection of a valid translation | `DirectTranslationLayout` reports `overlay_collision` / `out_of_bounds` / `text_capacity` / `mask_defect` / `clipped`; `OverlayPolicy.renderable` excludes dead blocks from obstacle space | Rendering is correctly separated from translation, and failed blocks are already excluded from obstacles — the 1.1.36 report records `5/22 eligible blocks failed fit/obstruction`, and says that without movement ground truth those numbers do not justify lowering gates. | `overlay/DirectTranslationLayout.kt`, `overlay/BubbleOverlayService.kt` | Do not touch thresholds on current evidence. Measure first (§8). | traced (report-backed) |
| 10 | Whole-page context never reaches the model | `LanguageModels`/ML Kit receives a string per region | By design and correctly: `ScreenCaptureService` passes one region's text. No cross-bubble context is available to the model at all. | `capture/ScreenCaptureService.kt`, `translate/TranslationClient.kt` | This is the honest ceiling of the current architecture, and the reason a context engine (Part 4–5) is the right investment. | **proven (code)** |

### 2.1 What is *not* a root cause (checked, and ruled out)

- **OCR engine choice.** The failures above are post-OCR text manipulation, not recognition loss.
- **Confidence thresholds being too high.** Loosening them would admit *more* corrupted text into
  a corrector that mangles valid words. The instruction is right to forbid this.
- **The dictionary being too small.** `reading-en.txt` has 18,000 entries. The defect is that a
  **20-word** list can overrule all 18,000 (§3.1).
- **A missing phrase table.** `DialogueLexicon` and `ReadingGlossary` already are phrase tables,
  and the measured hit rate is `0/48` (RC6 session summary, quoted, not reproduced).

---

## 3. Deep dive: why OCR text is corrupted, fragmented, duplicated, rejected

### 3.1 The corruption chain, proven

`TranslationInput.prepareRepair` (line 146):

```kotlin
val repaired = ReadingEnglish.repair(OcrLexicon.repair(EnglishOcrRepair.repair(masked)))
```

`ReadingEnglish.repair` (line 200) for any token where `4 <= len <= 18` and the token is **not**
in the 18,000-word list, and not `looksInflected`, computes a set of candidates via a glyph-swap
search, then falls back through:

```kotlin
hits.singleOrNull() ?: ComicSpelling.suggest(w) ?: segment(w) ?: insertion(w)
```

`ComicSpelling.suggest` is a symmetric-delete lookup over:

```kotlin
private val domain = "hungry ghost hell lord master eldest sword house enough
   forgotten wisdom gouged skewers appeared profound spirit disciple formation headquarters"
```

I re-implemented this pair exactly in Python and ran it against `reading-en.txt` from this repo:

```
YOUVE    -> house        (via ComicSpelling.suggest)
YOURE    -> house        (via ComicSpelling.suggest)   <-- a word that IS English
THEVRE   -> THEVRE       (correctly refused: ambiguous)
HOUSE    -> HOUSE        (in vocabulary: untouched)
```

`YOURE` is at rank 13295 of the very list the module consults, and is still overwritten, because
the ranked list is only checked for *membership* (`w !in words`) and the contraction is stored
without its apostrophe. `YOUVE` is absent from the list entirely, so it is treated as OCR debris.

**This is the root cause of the reported class "a correct word becomes a wrong word."** It is not
a confidence problem and no threshold change can fix it: the input was already accepted.

### 3.2 Why a fragment survives and a sentence splits

Two independent grouping systems, with different geometry rules, neither using text similarity:

1. `RegionMerger.merge` — clusters by **vertical gap ≤ 28px**, **horizontal overlap ≥ 25%** of the
   narrower box, or horizontal distance ≤ 48px. Cluster bounds are the union.
2. `DialogueAssembler.assemble` — additionally requires `bridgeIsClear` (visual continuity) and
   vetoes a merge when the upper line ends in `.`/`!`/`?`/`…` (a "complete" sentence).

Neither consults `TextRegion.text` content except for that terminal-punctuation veto. Therefore:

- A partial crop of the same sentence that overlaps by **< 70%** IoU is *not* a duplicate to
  `OverlayPolicy` and is translated and drawn separately.
- A fragment whose box is fully **contained** in a larger box is never compared by text, so
  containment — the strongest duplicate signal available — is unused.
- Two adjacent bubbles at similar height can merge if their boxes are within 48px horizontally
  and the gap is ≥ 0.

That last rule is the "merge unrelated dialogue" risk; the terminal-punctuation veto is the only
guard against it, and it is defeated by any line whose OCR dropped its final punctuation.

### 3.3 What to build (design only, not implemented)

A bubble reconstructor that uses, in order:

1. **Text containment / token-overlap on raw OCR** (a fragment's token set ⊆ the sentence's).
   This is the signal the current code lacks and the one that most directly fixes duplication.
2. **Reading-order + geometry** as confirmation, not as the primary test.
3. **Sentence completeness** (finite verb present, terminal punctuation, or a wrap relationship).
4. **Bubble geometry** (mask/contour) to bound a cluster — `BubbleMask`/`FlatInterior` already exist.

Rule: *prefer the longest text whose tokens contain the candidate's tokens*; merge only when the
union reads as one sentence; never merge two clusters that each parse as a complete sentence.

---

## 4. Translation engine assessment (facts)

| Property | Value | Source |
|---|---|---|
| Engine | ML Kit Translate `com.google.mlkit:translate 17.0.3` | `gradle/libs.versions.toml:31` |
| Locality | On-device after a **one-time model download** (`LanguageModels.ensureReady`) | `translate/LanguageModels.kt` |
| Control | **No prompt, no glossary, no language-model parameters.** `translateText` — one string in, one string out. | `translate/TranslationClient.kt` |
| Context | None. One region → one call. | `ScreenCaptureService` |
| Failure contract | Returns source text + error code; never invents | `TranslationOutput`, `TranslatedBlock` |
| Measured lexicon hit rate | `0/48` (RC6 session summary — quoted, not reproducible here) | task brief / prior phase |

**Honest limitation.** This is a general-purpose phrase-based/NMT model with no way to receive
terminology, gender, speaker, or negation guidance. Idiom errors and terminology drift are
therefore expected and cannot be fixed by adding rules on top: the previous audit already
concluded that a semantic layer fed a pre-rewritten English sentence cannot recover meaning the
rewrite destroyed. That conclusion is consistent with the code I read.

**Practical consequence for planning:** replacing the engine is the only way to move meaning
quality materially. Everything else (repair, dedup, gates) controls *damage*, not *quality*.

---

## 5. Model comparison (external research — not repository facts)

All figures below are from published model cards/benchmarks, re-stated as *claims to verify on
device*, never as measurements of this app.

| Candidate | Params | On-device size (int8) | EN→AR quality (published) | Peak RAM | Licence | Commercial | Notes |
|---|---|---|---|---|---|---|---|
| **ML Kit (current)** | n/a | ~30 MB model, downloaded | unknown/unpublished | low | Google ToS | yes | No control; download required |
| **Opus-MT `opus-mt-en-ar`** | ~77 M | **~84 MB** int8 | base ~22.6 BLEU (FLORES-200) | ~200–300 MB | **Apache-2.0** | **yes** | Marian 6+6, 62.8k vocab; CPU-friendly |
| **`opus-mt-tc-big-en-ar`** (teacher) | 239 M | ~250 MB int8 | **29.4 BLEU / 0.61 chrF** (flores101-devtest) | ~600 MB–1 GB | **CC-BY-4.0** | **yes, with attribution** | Best licensed quality/size tradeoff |
| distilled `en-ar-distil-opus-mt` | 76.8 M | 84 MB int8 | +2.58 BLEU over base (25.2) | ~250 MB | **CC-BY-4.0** | yes, attribution | third-party fine-tune; provenance of training data unresolved |
| **NLLB-200 distilled 600M** | 600 M | ~640 MB int8 | ~38.4 BLEU (FLORES, Q4) | **~1.4 GB** | **CC-BY-NC-4.0** | **NO — non-commercial** | Disqualifying licence for a shipped app |
| MADLAD-400 | 400 M–3 B | ~1 GB+ | competitive | 1 GB+ | Apache-2.0 | yes | overkill for one pair |
| M2M100-418M | 418 M | ~505 MB int8 | below NLLB | ~1 GB | MIT | yes | larger, no advantage for EN→AR |

### Recommendation

- **Primary: `Helsinki-NLP/opus-mt-tc-big-en-ar` (CC-BY-4.0), int8 via ONNX Runtime Mobile.**
  Published 29.4 BLEU / 0.611 chrF on FLORES-101 — the strongest *commercially licensable*
  English→Arabic model at a phone-feasible size. ~250 MB disk, ~600 MB–1 GB peak RAM.
- **Fallback: `Helsinki-NLP/opus-mt-en-ar` (Apache-2.0), int8** — ~84 MB, ~250 MB RAM, for
  low-RAM devices (any 2–3 GB phone), at a measurable quality cost.

**Why not NLLB-200 despite the better BLEU:** CC-BY-NC-4.0 forbids commercial use, and at
~640 MB / ~1.4 GB peak it also risks memory pressure on mid-range phones. Choosing it would
trade a licence violation for quality.

**Why not pick by popularity:** ML Kit is already the "popular" choice and is the thing under
investigation. The deciding factors here are licence, EN→AR-specific benchmark, and peak RAM —
in that order.

New dependencies this implies: **`onnxruntime-android`** (Apache-2.0) plus a tokenizer
(`sentencepiece` or a Kotlin port), and the model shipped as an **optional download**, not in
the APK. None of that exists in the project today (`app/build.gradle.kts` has no inference runtime).

---

## 6. Context engine design (the architectural proposal)

Scope discipline: **one complete bubble is the unit.** No chapter-level story model. The engine
must work with zero prior dialogue.

```
                     ┌─────────────────────────────────────────────┐
 raw OCR region ───► │ 1. BUBBLE RECONSTRUCTION                    │
 (+ geometry,        │    token-containment dedup → reading order  │
  bubble mask)       │    → sentence completeness → one Bubble     │
                     └───────────────────┬─────────────────────────┘
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │ 2. OCR TRUST LEDGER  (new)                  │
                     │    per token: raw, candidate repairs,       │
                     │    evidence, chosen form, reversible        │
                     │    → "safe to translate?" is a score,       │
                     │      not a boolean reject                   │
                     └───────────────────┬─────────────────────────┘
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │ 3. SENTENCE ANALYSIS (rule + lexicon)       │
                     │    negation scope, clause structure,        │
                     │    phrasal verbs, idioms, questions,        │
                     │    pronouns → features for MT + validator   │
                     └───────────────────┬─────────────────────────┘
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │ 4. TERM & NAME MEMORY (existing, reused)    │
                     │    NameShield (keep), ReadingGlossary,      │
                     │    per-session terminology constraints      │
                     └───────────────────┬─────────────────────────┘
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │ 5. MT: Opus-MT big en-ar (int8, on-device)  │
                     │    receives the WHOLE bubble sentence       │
                     └───────────────────┬─────────────────────────┘
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │ 6. MEANING VALIDATOR (new)                  │
                     │    negation | numbers | names | entities    │
                     │    preserved? → accept / re-translate with  │
                     │    explicit constraint / fall back to source│
                     └───────────────────┬─────────────────────────┘
                                         ▼
                     ┌─────────────────────────────────────────────┐
                     │ 7. LAYOUT + RENDER (unchanged)              │
                     └─────────────────────────────────────────────┘
```

### Design decisions, and why they differ from today

1. **Corrections become a ledger, not a mutation.** Today `repair` overwrites the token and the
   original is only recoverable by reading the other field. The ledger keeps `(raw, chosen,
   evidence)` and can *undo* a repair when the validator disagrees. This is what makes §3.1's
   class of bug structurally impossible rather than merely fixed.
2. **No 20-word list may overrule the 18,000-word vocabulary.** Any general-English correction
   must come from the ranked list with ranked evidence; domain lists are allowed only inside
   their domain and only as *proposals*.
3. **Gates score, they do not veto.** Pre-model rejection is replaced by a confidence object plus
   a post-translation meaning check. `OCR-UNCERTAIN` becomes a label on a translated block, not
   a reason to drop it.
4. **Negation is a first-class feature.** Detected in English, verified in Arabic. This directly
   targets "haven't been found out" losing its negation, which no script-shape check can see.
5. **Terminology is memory, not regex.** A per-session `(English → Arabic)` constraint list,
   fed to the engine's decoder constraints, replaces `ReadingGlossary`'s pattern table while
   reusing `NameShield` unchanged (it works and is tested).

### What is deliberately *not* proposed

- No chapter/story summarisation, no cross-page memory, no LLM.
- No changes to `overlay/`, `capture/` lifecycle, or OCR thresholds — those are validated by
  existing tests and by the 1.1.36 report, and this investigation found no evidence to change them.
- No new dictionary entries as a fix for any root cause in §2.

---

## 7. Resource budget (estimates — labelled)

| Item | Current | With Opus-MT big | How it will be measured |
|---|---|---|---|
| APK size | 120,499,664 B (RC7, verified) | + ~2 MB (ORT + tokenizer) | `apk_bytes` from `verify_release_apk.py` |
| Model on disk | ~30 MB (ML Kit, downloaded) | ~250 MB, **optional download** | file size after fetch |
| Peak RAM, translation | not currently instrumented | est. **+600 MB–1 GB while inferring** | `Debug.getMemoryInfo` + `onTrimMemory` instrumentation; Android Studio profiler on device |
| Latency per bubble | RC6 median 5264.7 ms end-to-end (quoted) | **target: ≤ 400 ms MT** for a 40-token sentence | `BenchmarkTracker` T5–T8 marks already exist |
| Battery | unknown | unknown; must not run on every frame | Battery Historian over a 20-min read session |
| Concurrency | 1 journey at a time (scene revision) | keep 1; never load two models | existing `SceneEpoch` guard |

Every number in the "with Opus-MT" column is an **estimate from published model cards** and is
not a measurement of this app. The instrumentation to measure it (benchmark marks, scorecard)
already exists and is the reason these are cheap to obtain.

**Required guardrails before any model ships:** unload on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`;
never hold encoder and decoder plus a page bitmap simultaneously; bound the translation cache
(`TranslationCache` exists, 38 lines); no inference on an unchanged bubble (`PageState` hashes exist).

---

## 8. Regression strategy

### 8.1 What already exists and must be reused, not duplicated

- `evaluation/corpus/rc4-corpus.json` — 40 cases, independent Arabic reference, `ocr_quality`,
  `semantic_category` (idiom 5, sarcasm 2, pronoun-unclear 5, implicit 3, emotional 9),
  `error_reason_codes`, `reference_status: pending_human_review`.
- `scripts/translation_eval` — validate / evaluate / review-sheet / apply-review / build-app-assets.
- On-device harness: `EvaluationScreen` (browse runs, rate 0–3 or not_applicable per criterion,
  export JSON/TXT), `TranslationEvalExportTest`.
- 331 Python tests guarding corpus integrity and release identity.

### 8.2 Gaps that must be closed for Part 6

The corpus is **clean-OCR typed text** (38/40 `clean_ocr`). It therefore cannot catch §3.1 at all.
Required additions:

| New corpus component | Content | Guards |
|---|---|---|
| `ocr-corruption` | raw-OCR strings with known truth, e.g. `YOUVE`, `THEVRE`, `COULDTHAT`, `1 SEE` | repair must never map a valid word to a different valid word |
| `bubble-reconstruction` | multi-line bubbles plus a fragment of the same bubble | no split, no duplicate |
| `negation` | sentences with explicit negation | Arabic output must retain a negator |
| `terminology` | recurring titles/places across ≥3 bubbles | consistent rendering |
| `render` | region geometry where layout previously rejected | no silent loss |

### 8.3 Scoring, distinguishing the outcomes the brief lists

Per case, exactly one label — no aggregate "quality score":

| Label | Rule |
|---|---|
| `correct` | human review 3 on meaning **and** no validator violation |
| `acceptable` | review 2, meaning preserved, style imperfect |
| `meaning_changed` | negation/number/name/entity altered — **always a failure class** |
| `untranslated` | `TR-UNTRANSLATED`, source returned |
| `ocr_corrupted` | prepared text differs from a valid word in the raw text |
| `duplicate` | another block covers the same source bubble |
| `display_failure` | translation valid, no `VISIBLE_IN_IMAGE` |

**Acceptance rule for any future change:** no increase in `meaning_changed`, `ocr_corrupted`,
`duplicate`, or `display_failure`; `correct + acceptable` must not fall; latency and peak RAM
must not regress beyond a stated budget. **Translating more bubbles is explicitly not a win**
if it moves cases into `meaning_changed`.

---

## 9. Implementation roadmap

Each phase is independently testable and reversible. No phase may begin before the previous
phase's regression run is green.

### Phase 2 — OCR Trust Ledger (highest value, lowest risk)
- **Objective:** make §3.1 structurally impossible.
- **Files:** `pipeline/TranslationInput.kt`, `pipeline/ReadingEnglish.kt`, `pipeline/ComicSpelling.kt`; tests in `app/src/test/.../pipeline/`.
- **Algorithm:** `RepairLedger` records `(rawToken, chosenToken, rule, evidence)`. `ComicSpelling.suggest` may not fire on a token that is a valid contraction/possessive of a listed word. Ledger is reversible.
- **Tests:** `YOUVE`,`YOURE`,`THEVRE`,`HOUSE` cases; property test: *a token present in `reading-en.txt` is never changed by the domain list*.
- **Benchmark:** corpus validate + new `ocr-corruption` set.
- **Risks:** over-correcting the corrector could stop genuine repairs → mitigated by keeping every existing `ReadingEnglishTest` case green.
- **Acceptance:** all existing unit tests pass; `ocr_corrupted` count 0 on the new set.
- **Rollback:** revert the file pair; no data migration.

### Phase 3 — Pre-translation dedup and bubble reconstruction
- **Objective:** one sentence per bubble, no fragments.
- **Files:** new `pipeline/BubbleReconstructor.kt`; `FrameTranslationPlan` call site; keep `OverlayPolicy` as the second net.
- **Algorithm:** token-containment (fragment ⊆ sentence) → reading order → completeness → geometry bound.
- **Tests:** split/single/duplicate/adjacent-speaker fixtures from `BubbleUnitTest`.
- **Acceptance:** no case splits a single sentence; no case merges two complete sentences.

### Phase 4 — Graded input trust (replace vetoes)
- `TranslationClient` L69–80 → confidence object; `OCR-UNCERTAIN` becomes a label on a translated block.
- **Risk:** more text reaches the model, so quality must be judged by the meaning validator (Phase 5), not by eyeballing.

### Phase 5 — Meaning validator
- **Files:** new `translate/MeaningValidator.kt`; `TranslationOutput` integration.
- **Algorithm:** negation parity, digits, protected names, entity retention; on failure, one constrained re-translation, then explicit source fallback.
- **Tests:** negation-preservation fixtures; a deliberately negated sentence must fail a non-negated Arabic output.

### Phase 6 — Translation engine swap behind a flag
- **Files:** new `translate/OpusEngine.kt` behind an interface `TranslationEngine`; `LanguageModels` untouched until the engine is proven; `onnxruntime-android` added to the version catalog.
- **Order:** ship the model download, then the engine, then flip the default, one release each.
- **Acceptance:** bilingual review on the 40 cases shows no regression versus ML Kit before it becomes default; ML Kit remains the fallback until then (task constraint: keep the baseline until beaten).

### Phase 7 — Context engine on top
- Sentence analysis features, terminology memory, constrained decoding.

### Phase 8 — Retire the regex rewrites
- Only after Phase 7 demonstrably covers the same cases; removing them earlier is a regression.

---

## 10. Final recommendation

1. **Fix the corrector before anything else.** §3.1 is a proven, reproducible defect that turns
   correct English into different English *before* translation, and it is the cheapest fix in this
   report (two small files, no new dependency, fully testable offline).
2. **Do not touch confidence thresholds or OCR settings.** The evidence does not support it, and
   the failures are not threshold-shaped.
3. **Add text-level dedup before translation.** Geometry-only dedup after translation cannot see
   containment, which is the strongest duplicate signal the pipeline already has data for.
4. **Then swap the engine, behind a flag, with ML Kit as fallback** — Opus-MT big en-ar
   (CC-BY-4.0) primary, Opus-MT en-ar (Apache-2.0) fallback. This is the only change that can
   materially improve meaning and naturalness, because ML Kit cannot receive terminology,
   negation, or context at all.
5. **Build the context engine on top of a correct input**, never as a substitute for one. A
   semantic layer fed a mangled or wrongly-rewritten English sentence cannot recover the meaning.

**The single most important finding:** the app's quality ceiling today is not the translation
model — it is that the model is being asked to translate **English that the app rewrote**. Two
separate mechanisms do this: `ComicSpelling`'s 20-word list (§3.1, proven) and the 10+6 regex
rewrites in `nativeInput`/`nativeSentence` (§2 row 7). Replacing the model without fixing both
would reproduce the same failures with a better model.

---

## 11. Explicit non-claims

- No RC7 phone report was available; no specific captured screen is asserted.
- No bilingual evaluation was performed; no translation-quality improvement is claimed.
- The Kotlin suite was not executed here (no JVM); CI numbers are quoted from RC7.
- All model sizes, BLEU figures and RAM estimates are **published external claims**, not
  measurements of ManhwaLens.
- No application code, test, benchmark artefact, tag, branch, stash or release was modified by
  this phase.
