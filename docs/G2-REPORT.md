# Phase G.2 — Real-Page Evidence Collection & Context-to-Arabic Bridge

Commit `12826c0` · branch `arena/f6ce5fe4-manhwalens-v2` · PR #10 · release `rc-1.1.42-rc11`

**What this phase achieved, stated at the right strength:** the contextual engine can now run on a
device and can carry a settled fact through to the Arabic path, and the app can capture and export
real pages from the phone. **What it did not achieve:** there is still no evidence that the Arabic is
better on real Manhwa, because no real page has been captured yet and no human has reviewed anything.
Grade 1 is reachable. Grade 2 is now reachable in code and unproven on data. Grade 3 is untouched.

---

## A. Repository state

| | |
|---|---|
| Head at phase end | `12826c0a9efd1b7992dc635b6755f07d36641f54` |
| Branch | `arena/f6ce5fe4-manhwalens-v2` |
| PR #10 | open, mergeable |
| RC10 | `rc-1.1.42-rc10`, `versionCode` 217442881, cert `93794a…0806` |
| RC11 | `rc-1.1.42-rc11`, `versionCode` 217442882, cert `93794a…0806` (identical) |
| History | no force-push, no reset --hard, no commits dropped, no re-founding on an old revision |

The worktree was verified against GitHub before any edit. One `git stash` was used to test whether a
fix was load-bearing; it was popped and the file verified byte-identical afterwards, and the stash
list is empty. That is recorded here because the repository has a test that forbids foreign changes
appearing in the tree, and it flagged the uncommitted state before this phase's commit.

## B. Audit of the existing engine — what it can and cannot do

The audit's central finding is not a list of capabilities but a broken link between two of them.

`ContextBuilder.tryAnswer` settled a pronoun question through story memory and then returned only a
`ContextLevel`. The answer itself — the name — was discarded. `frame.pronounReferents`, the only
field `ContextBridge` reads for referents, was therefore never populated by the production path.
The engine could report `settledAt = STORY` while the bridge saw an empty frame and honestly returned
`NO_ACTIONABLE_SEMANTIC_FACT`.

Every layer was individually correct and tested. The chain between two of them was broken. This is
exactly the failure the phase brief predicted: *analysed* is not *used*.

**Fixed and verified by reverting the fix** — 3 of 5 new tests fail without it, pass with it.

The ladder, now that it is measured rather than assumed:

| Stage | State |
|---|---|
| analysed | works — `UtteranceAnalyzer` extracts subject, verb, tense, polarity, mood, entities, idioms, open questions |
| retrieved | works — bounded, capped at 4 snippets / 600 chars |
| relevant | partial — retrieval is deliberate but narrow (`memoryEntryFor` maps one question kind to one memory kind) |
| settled | works, and now *records what it settled* |
| convertible to a decision | **only pronoun referents**, and only under three narrow conditions (below) |
| approved change | works through `ContextTranslation.run` + verification |
| provably meaning-preserving | verified for the text layer only; **not proven for Arabic** |

### The three conditions for the one live capability

1. Memory must be keyed by the **pronoun** — `him -> Jin`. A record keyed `jin -> Jin` is a name the
   engine already knows, not an antecedent, and settles nothing. Verified both shapes.
2. The pronoun must be **object or possessive** — `{him, her, them}` / `{his, her, their}`. A subject
   pronoun can never be clarified, because rewriting a subject changes the sentence's own syntax and
   the bridge only performs verifiable single-token swaps.
3. The name must be a **single token**. "Jin Woo" is refused, stated as a limitation.

### The gender case, answered rather than deferred

"That day, I became a mage." with a known female speaker produces **no Arabic change**, and this is
correct. Arabic first-person past is gender-neutral (`أصبحتُ`), so there is nothing grammatical to
inflect. Producing a feminine form would mean inventing Arabic. The refusal is recorded with a stable
reason (`first_person_arabic_is_gender_neutral`), not hidden as a gap.

## C. Real Manhwa data

**No real page data exists in the repository.** The 40-case corpus is 38 `clean_typed` + 2
`ocr_transcribed`, every one `pending_human_review`. Nothing from a real page was ever stored, and
the engine had never run on a device because `pageContext` had no production assignment.

| Category | Count |
|---|---|
| Real captured pages | **0** |
| Real OCR results stored | 0 |
| Synthetic / typed cases | 38 |
| OCR-transcribed (not real captures) | 2 |
| Human-reviewed | 0 |
| Meaning-verified | 0 |

**Missing:** everything that would come from a capture. This is why the capture mechanism, not a
comparison UI, is the deliverable (§4).

## D. Capture mechanism

Lives in the app, reached from Settings ("جمع حالات حقيقية · Real-page evidence").

**Usage from the phone, no computer:** read a page normally → open the screen → save the case →
repeat → export one file → share it through the ordinary Android share sheet.

- Storage: `filesDir/evidence-cases`, append-only, `MAX_CASES = 200`, corrupt entries quarantined
  rather than deleted.
- Export: single JSON, `FORMAT_VERSION = 1`, via `FileProvider` + `ACTION_SEND` as `application/json`.
- Re-analysable **without re-running OCR**: regions, bounds, reading order, OCR confidence and page id
  are all recorded, and OCR confidence is passed through unmodified rather than blended.
- **No images, by construction.** `EvidenceCase` has no image field, so an image cannot leak into an
  export even by mistake. Opt-in inclusion was considered and deliberately not built.
- Nothing leaves the device except the file the user chooses to share.

**Honesty gate (§4):** there is no better/worse control. `engineLine()` prints
`لم يعمل المحرك السياقي` / `NO_CANDIDATE` / `مرشح دلالي` / `تغيير شكلي فقط (ترقيم) — ليس تحسنًا دلاليًا`.
The screen never asks a reader to grade an engine that changed nothing.

## E. The bridge

**Option A only, narrowed to pronoun clarification.** B and C were examined against the implementation:

- **B (context-aware Arabic realization)** was investigated for gender and then **refused with a
  recorded reason**, not skipped. Arabic has no alignment anchor from an English token to a span of
  ML Kit's Arabic output, so "edit the right word" cannot be done safely. The bridge's own rule
  applies: if the Arabic span cannot be located confidently, do not edit.
- **C (local model)** not adopted. No sizeable on-device en→ar model with a compatible licence was
  validated, and adopting one would have meant a large APK and latency cost justified by taste
  rather than measurement.
- **A** is used because it is verifiable: the edit is a single English span, the original is
  preserved, and a token diff proves only that span moved.

**Interface (`ContextBridge.Decision`):** code, facts, rewrite, reasons, trail. Every edit carries its
reason, evidence source, affected span, confidence, verification result and accept/reject decision.
When nothing is actionable it returns `NO_ACTIONABLE_SEMANTIC_FACT` explicitly.

**`CONTEXT_RESOLVED` can never become `CANDIDATE_ACCEPTED` inside the bridge.** `decide()` does not
have `CANDIDATE_ACCEPTED` in its vocabulary; acceptance happens only in `ContextTranslation.run`,
after the Arabic exists and the verifier has passed. This is enforced by the type, not by convention.

### Wiring done this phase, which is what makes the engine actually runnable

- `ScreenCaptureService.pageContext` is now assigned before translation. Previously **no production
  code assigned it anywhere**, so `contextualPass` returned its input unchanged on every real page.
- `PageSnapshot.source` — the caller translating one region at a time supplies the line, so the
  analysed sentence is never an index coincidence. `ContextBuilder` already accepted an overriding
  text; nothing had ever used it.
- `connectClarifiedTranslator()` — without it, gated mode returned the baseline forever and every
  case would have been recorded `NO_CANDIDATE` even where a rewrite existed.
- Default mode remains `A_BASELINE`. An untouched install behaves exactly like RC10.

## F. Results

| | |
|---|---|
| Semantic candidates produced on real pages | **0** (no real page captured) |
| Candidates accepted / rejected | 0 / 0 |
| Comparison against baseline on real data | **none performed** |
| Human review results | **none** |
| JVM tests (context/) | **256 tests / 15 suites / 0 failures** |
| Repository Python suite | **341 tests, 2 errors, 1 skipped** — the 2 errors are the known missing-`cryptography` keystore environment issue, not defects |
| CI | all four workflows green at `12826c0` |
| Timing / RAM / APK impact | **not measured.** Has never run on a device |
| APK size | 120,681,614 bytes (RC11, measured) |

The brief asked for actual numbers against the reference 531/55/0. Those figures describe the full
Android test suite, which cannot run in this sandbox (Gradle cannot download its distribution). What
is measured and reported above is what actually ran. **I am not reporting 531/55/0 as verified.**

## G. Release

| | |
|---|---|
| versionName | `1.1.42-rc11` |
| versionCode | `217442882` (> 217442881) |
| Commit | `12826c0a9efd1b7992dc635b6755f07d36641f54` |
| APK SHA-256 | `b1067a7c6f7048a150dc6e4dec70623e2c3097e6ef3b2c80789a82a5560c0b65` |
| Signer cert | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` — **identical to RC10** |
| URL | https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/tag/rc-1.1.42-rc11 |
| Release workflow | green; signature, certificate, identity and versionCode all verified by the job |
| Install over RC10 | **not tested on a device.** The versionCode floor makes the upgrade legal; that is not the same as an install that was observed to succeed |
| User data | no migration, no deletion, no new storage location for existing data |

**This is a collection-and-diagnostics release.** It does not claim improved translation. Signature
verification and tag protection were not weakened.

## H. What is not proven

1. **No real Manhwa page has been captured.** Every claim about behaviour on real pages is inferred
   from typed English, not observed.
2. **No Arabic improvement has been demonstrated**, on any data.
3. **No human has reviewed any output.** Grade 3 is untouched.
4. **Nothing has run on a physical device.** CI building an APK is not device validation, and the
   install-over-RC10 path is verified only as "versionCode permits it".
5. **Timing, RAM and APK-size impact of the engine are unmeasured.** The `A_BASELINE` default means
   an untouched install pays nothing, but the cost of running the engine is unknown.
6. **The bridge's real coverage is unknown.** Three narrow conditions must all hold; how often that
   happens on real Manhwa is precisely what the capture mechanism exists to find out.
7. **The 531/55/0 reference could not be re-run** in this environment.

---

## The next honest step

Not more rules. **Twenty to thirty real cases from five or more pages, exported from the phone.**
They will show whether the three conditions are ever met in practice, and — if they are met — whether
naming the referent in the English actually produces better Arabic. Until those exist, adding a
capability would be building on a foundation nobody has looked at.
