# RC9 — Complete bubble reconstruction, context-aware OCR & Arabic review

Release: `1.1.42-rc9` · versionCode **217442880** · APK published as a prerelease
Branch: `arena/f6ce5fe4-manhwalens-v2` · base `f9278d6` (RC8 tip)

Everything below separates what was **measured** from what was **not**. No phone
test was run in this session, and nothing in this report claims one.

---

## 1. Git

| Commit | Scope |
|---|---|
| `19d2184` | Phase A — elect the complete reading instead of the first overlapping one |
| `f6566c3` | Phase B — rejoin one utterance split across OCR detections |
| `5ccc374` | Phase C — restore a dropped apostrophe on evidence, record every decision |
| `a4238a9` | Phase D — review whether the Arabic kept the facts of the English |
| `ed34fd6` | Phase E — RC9 benchmark, collapse duplicate reads before translation |
| `c7e4168` | Fix — move the benchmark type alias to file scope (Kotlin 2.0.21) |
| `b084876` | Release — RC9 candidate defaults, phase guard re-based |

`git_revision` bound into the APK: **`b084876e9f8c8bf643166ec9478cf0a827de7c26`**.
No reset, no clean, no rebase, no force-push, no branch/stash/tag deletion.
RC8's tag is untouched.

---

## 2. What was actually wrong, and what changed

### Phase A — the translated line was a fragment (`19d2184`)

`OverlayPolicy.duplicates()` matched each block against the first overlapping
one. Measured on the reported A/B/C geometry: `A-B = 0.897`, `B-C = 1.000`,
`A-C = 0.000`, producing `{1:0, 2:1}` — only `"IL MAKE HIM REGRET"` survived.
The relation is now a transitive group election, with the representative chosen
by captured text (confidence only as a later tie-break):

```
completeness  A 0.2180 · B 0.4162 · C 0.2128  ->  B wins, on any input order
```

**Important limit found while tracing the caller:** `duplicates()` only decides
what is *rendered*. It cannot make the full sentence the *translated* text. That
needed Phase B/E, and the report says so rather than implying Phase A fixed it.

### Phase B — `EVEN` / `THOUGH` arrived as two utterances (`f6566c3`)

`DialogueAssembler` bridges two boxes only when the pixels between them read as
continuous (`LightCorridor` / `TonalCorridor`). A decorated circular panel has no
light corridor, so the bridge refuses. That is a **textual** defect, repaired
after recognition:

- **geometry is a gate, text is the decision** — same column, or same row with a
  real gutter; a completed sentence is never continued; two independently
  complete halves are never joined; the seam must be grammatical.
- `joinable()` is never satisfied by overlap alone; `ContinuationGrammar` is a
  closed table of function words, not a phrase list.
- a join may never lose a word (`CandidateRecovery.losesText`), is recorded with
  its evidence and its raw parts, and is independent of OCR arrival order.

Two real bugs were found by the tests rather than assumed away:

1. reading order was taken from `top`, so a same-row pair whose right half sat
   **2 px higher** was ordered tail-first and the join was silently hidden;
2. `THOUGH` was an open ending but missing from the continuation openers, so a
   fragment *starting* with it could not be attached.

**Insertion point (traced, not assumed):** `ColorRecovery.recognize` →
`TextRecognitionClient` → `ScreenCaptureService` frame scaling → `prepareRegions`
→ `FrameTranslationPlan`. The reconstruction sits immediately after scaling and
before `prepareRegions`. `RegionMerger` is imported by `TextRecognitionClient`
with **zero call sites** (tests only): it is dead in production and was
deliberately not revived, because wiring it would have created the second random
merge mechanism the release forbade.

### Phase C — `ILL` never became `I'LL` (`5ccc374`)

Phase 1's protection was correct — `ILL` is a real word — and the sentence was
still wrong because the apostrophe was never restored. Restoration now requires
evidence:

| Input | Result | Why |
|---|---|---|
| `ILL MAKE HIM REGRET IT.` | `I'LL …` | bare verb follows; adjective impossible |
| `ILL NEVER FORGIVE YOU.` | `I'LL …` | adverb then verb |
| `I AM ILL.` | `ILL` | predicate adjective (copula before) |
| `THE ILL KNIGHT ARRIVED.` | `ILL` | attributive: a noun follows |
| `ILL-FATED MAN.` | `ILL` | hyphenated compound, never touched |
| `HE IS ILL.` | `ILL` | no following word |

`RepairLedger` records **every** proposal — accepted *and* refused — with the raw
spelling, the evidence, the confidence and the decision. A refusal is now
distinguishable from a word nobody considered, which is what made the original
defect hard to diagnose. Sub-threshold proposals are recorded but never applied.
The raw OCR text is never overwritten.

### Phase D — the Arabic lost the meaning (`a4238a9`)

`TranslationOutput.issue()` is structural and is unchanged. It passed
`"في ذلك اليوم، أصبحت ماجاة."` because structure was never the problem.
`MeaningValidator` reviews **information preservation**:

- **numbers** — a dropped or altered figure is a meaning change;
- **names** — every protected sentinel must survive;
- **negation — polarity, never counting.** `"NOBODY SAID NOTHING."` →
  `"لم يقل أحد شيئا."` has two negatives on one side and one on the other;
  counting would falsely condemn it.
- **leftovers** — an unprotected Latin run means text was copied through.

Case D specifics, decided from evidence: `genderSensitive` flags a
`I became/am/was` source whose output contains a gender-ambiguous undiacritised
copular form (`أصبحت` is the same five letters for "I became", "she became",
"you became"). It **does not guess a gender** — the release says gender must come
from reliable context, and this layer has none. `mage` gains a reviewed glossary
term (`ساحر`) instead of the transliteration `ماجاة`, carrying no grammatical
gender. `throwing me away` is in a small idiom table with its sense recorded.

The review is **advisory**: it runs only after the structural gate accepted the
output, can only add review-stage codes, and never promotes or rejects.
`OUTPUT_VALIDATED` keeps exactly its existing meaning — a negative review lands
as its own stage code, so confirmed and unreviewed results stay distinguishable.

### Phase E — the benchmark found a bug in the release (`ed34fd6`)

`rc9-01` did not collapse. Phase A made the *overlay* elect the complete reading
for display, while the translator still received all three readings — one line,
three translated blocks, two partial. `collapseDuplicates` closes it: group
regions sharing most of their area, elect by captured text, total tie-break. It
runs *before* joining, so a partial read can never be joined onto a full one into
a duplicate phrase.

The benchmark also exposed a **second instance of the reading-order defect**:
`merge()` ordered by `top`, so the joined text came out
`"THOUGH AE KNEw HOW HARD I WAS TRNINGo EVEN"` — all words present, wrong order.
`readingOrder` is now row-aware. This was only visible because the benchmark
asserts on the joined *text*, not on the join count.

---

## 3. Measurements

### JVM (this machine, local harness)

| Suite | Result |
|---|---|
| Full local suite, 15 classes | **170 tests, 0 failures** |
| `reading-en.txt` sweep through `prepare()` | **18 000 / 18 000 words unchanged**, 0 text losses, 0 exceptions |
| RC9 benchmark | 10/10 OCR metric, 4/4 translation metric |

Per-suite: BubbleReconstructor 25 · OverlayCollisionPolicy 16 · OcrCorrectorSafety
18 · VocabularyProtection 7 · ReadingEnglish 3 · RinsiwingInvestigation 4 ·
OcrInputIntegrity 9 · PhoneReportRegression 10 · DiagnosticCodes 7 ·
DialogueAssembler 7 · RegionMerger 6 · SentenceReconstructor 7 ·
ContractionRepair 20 · MeaningValidator 22 · Rc9Benchmark 9.

Phase A's tests are **discriminating**, not decorative: compiled against the
original `HEAD` implementation they produce **4 failures**; against the fix, 0.

### Android (Gradle, on the release runner)

**73 suites, 458 tests, 0 failures, 0 errors, 0 skipped.**

### Build

`Signed release APK` job: every gate green — signing-vault decode, signer
verified against the pinned production certificate, release compilation, release
unit tests, instrumented-test source compilation, signed assemble, signature +
certificate + identity + versionCode verification, build-identity check,
debug-sign refusal, download re-verification, keystore removal.

`.github/tests`: 332 tests — 330 pass, 1 skipped, 2 errors that are
`ModuleNotFoundError: cryptography`, absent from this sandbox and installed on
the runner. Not a code failure.

### Performance

**Not measured.** The ≤10 % median/P90 target needs a matched on-device run. The
reconstruction stage adds one O(n²) pass over the regions of a single frame
(n < 20), and the duplicate collapse is the same shape; no per-frame OCR was
added and the frame-stability gate, `SceneEpoch`, `PageState` and stale-result
cancellation are untouched. That is an argument, not a measurement, and is
labelled as one.

---

## 4. APK

| Field | Value |
|---|---|
| File | `ManhwaLens-1.1.42-rc9-release.apk` (120 534 106 bytes) |
| versionName | `1.1.42-rc9` |
| versionCode | **217442880** (> RC8's 217442879; never lowered or reused) |
| APK SHA-256 | `035e71dd464dc613cbbec2f8333aae949d586f2b247bed622bd685c8821d8273` |
| Signer cert SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` |
| Pinned cert SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` |
| Build identity | `b084876e9f8c8bf643166ec9478cf0a827de7c26` |

Signer equals the pinned certificate, so the existing signing vault was used and
not re-initialised. Because the code is strictly above RC8's, the APK installs
**over RC8 without uninstall**, preserving app data.

Download: GitHub → Releases → **`rc-1.1.42-rc9`** (prerelease, never `latest`),
asset **`ManhwaLens-1.1.42-rc9-release.apk`**. The release body also carries
`APK-IDENTITY.txt` and `SHA256SUMS.txt`. Asset downloads are blocked from this
sandbox, so the values above were read from the published release body produced
by the build job's own verification step — not re-derived here.

---

## 5. Explicit separation of evidence

| Claim | Status |
|---|---|
| OCR reconstruction produces the complete line for the ten cases | **JVM-verified** |
| The 18 000-word vocabulary is unchanged by the new stage | **JVM-verified** |
| Existing corrector/overlay/assembler behaviour is intact | **JVM + Android-verified** |
| The APK is signed by the vault, carries this commit, and upgrades over RC8 | **Build-verified** |
| ML Kit produces good Arabic for these sentences | **NOT TESTED** — needs the phone |
| The `EVEN THOUGH …` panel works on a real decorated panel | **NOT TESTED** — needs the phone |
| Performance regression ≤10 % | **NOT MEASURED** |
| Translations are natural / idiomatic / correct | **NOT CLAIMED.** `MeaningValidator` checks preserved facts only; a pass is not a semantic verdict |

Two things I got wrong and corrected rather than papered over: nested `typealias`
is rejected by Kotlin 2.0.21 (my newer local compiler accepted it — CI caught
it), and three of my own test expectations were wrong and were fixed against the
code instead of by loosening the assertions.

---

## 6. Still open

1. **On-device run** of the ten cases, especially the decorated circular panel.
2. **Performance** measurement against a matched RC8 baseline.
3. **Human semantic review** of the Arabic. The idiom and glossary entries are
   reviewer notes, not verified translations; `أصبحت`'s gender needs a reader who
   knows the character.
4. `MV-GENDER-PENDING` and `MV-NUMBER` / `MV-NEGATION` codes will need triage on
   real pages — they are designed to be cheap to dismiss, not to be authoritative.
