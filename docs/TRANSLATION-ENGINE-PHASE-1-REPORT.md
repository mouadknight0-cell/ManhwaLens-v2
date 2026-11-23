# Phase 1 — Safe OCR Correction Engine: delivery report

**Status: complete.** Root cause fixed, 375 tests green in the protected release run, signed APK published as `rc-1.1.42-rc8`.

| | |
|---|---|
| Branch | `arena/f6ce5fe4-manhwalens-v2` |
| Source commit | `6933ee8` — the fix |
| Candidate commit (APK) | `bc0dd38` — fix + release plumbing |
| Candidate | `rc-1.1.42-rc8` |
| versionCode | `217442879` (floor `217442878`) |
| Tests | **375 tests / 69 suites / 0 failures** (release unit tests) |
| Signer | `93794a2f…ce8e0806` — unchanged from RC7 |

---

## A. Root cause

**Corrected from the investigation report.** The investigation claimed `YOURE` → `house`. **That claim was wrong and is withdrawn** (`docs/TRANSLATION-ENGINE-PHASE-1-INVESTIGATION.md` §2 is superseded by this section).

`youre` is in `reading-en.txt` at **line 13295**, and `ReadingEnglish.repair` already guarded with `w !in words`, so it returned unchanged. The defect is narrower and more specific:

> **Out-of-vocabulary contractions reached a 20-word domain list, which overruled an 18,000-word vocabulary.**

The failing path, exactly:

```
repair(masked)
  └─ token not in the 18,000-word ranked list
      └─ ComicSpelling.suggest(w)          // symmetric-delete over a 20-word domain list
          └─ returns a single word within distance ≤ 2
              └─ accepted with no independent evidence
```

Because the token had already passed input validation, no confidence threshold downstream could catch it. Measured corruptions — all 35 common English contractions reduced to their apostrophe-free OCR form and run through the real chain:

| Token | Before | Why |
|---|---|---|
| `YOUVE` | **`HOUSE`** | `youve` ∉ vocab; unique domain match within distance 2 |
| `THEYLL` | **`HELL`** | `theyll` ∉ vocab; unique domain match within distance 2 |

**Scale of the hazard, measured:** 237 of the vocabulary's own 18,000 words would be rewritten if `suggest` were allowed to run on them. The vocabulary guard is the only thing standing between the domain list and the corpus.

**Explicitly not defects** (verified, not assumed): `YOURE`, `HOUSE`, `DONT`, `WERE`, `WELL`, `IM`, `IVE`, `ILL`, `ID`, `HES`, `ITS`, `THATS`, `THERES`, `WHATS`, `LETS`, `ISNT`, `WASNT`, `ARENT`, `DOESNT`, `HAVENT`, `HASNT`, `HADNT`, `COULDNT`, `WOULDNT`, `SHOULDNT`, `SHES`, `WEVE`, `THEYVE`, `YOULL`, `HERES` are all already in the vocabulary as bare forms. `KALLION`, `JANSK`, `KRASILOF`, `THEVRE` return no unique suggestion and were already safe.

---

## B. Implementation

Three files. No new dependency, no language model, no dictionary change, no deletion of any word.

### `pipeline/ReadingEnglish.kt`

| Addition | Role |
|---|---|
| `lookupForm(token)` | One canonical lookup form: lowercase, curly quotes (`’` U+2019, `‘`) folded to ASCII, apostrophes removed. **Deliberately narrow** — no morphological stripping, so `YOURS` never collapses to `YOUR`. Lookup only; never edits displayed text. |
| `knownWord(token)` | The ranked vocabulary as a **protection source**, matching raw *or* apostrophe-stripped form. This is what makes `YOURE` (rank 13295) and `YOU'RE` equally protected. |
| `carriesApostrophe(token)` | An apostrophe is evidence the recogniser saw a contraction, so a proximity guess is not sufficient evidence to replace it. |
| `isContractionForm(word)` | Structural recognition: pronoun/auxiliary stem + contraction tail (`you`+`ve`, `they`+`ll`). **Not a word list** — no per-word exceptions. |

**New precedence inside `repair`, and nothing below may skip a step:**

```
digits → in-vocabulary (raw or apostrophe-stripped)
       → length guard 4..18
       → BLOCK if looksInflected ‖ carriesApostrophe ‖ isContractionForm
       → glyph-swap single hit → ComicSpelling.suggest → segment → insertion
       → otherwise preserve
```

The guard sits **before the whole fallback chain**, not just before `suggest`. This matters: blocking `suggest` alone caused `YOUVE` to become `YOU VE` through `segment`. Both mutations are now closed.

### `pipeline/ComicSpelling.kt`

`CONTRACTIONS = {"youve","theyll"}` + `isProtectedContraction`, and an early `return null` in `suggest`. Populated **empirically**, not guessed: exactly two contractions out of all common English contractions were rewritable. It is a veto, never a repair.

### What deliberately did *not* change

- Raw OCR is still what is preserved when evidence is insufficient.
- The reviewed string rules that *insert* an apostrophe (`WASNT` → `WASN'T`, `ISNT` → `ISN'T`) run **before** the token loop and are untouched.
- The contextual rules (`PROM HELL` → `FROM HELL`, `HUNG AY` → `HUNGRY`, `ALVE` → `ALIVE`) still fire.
- No apostrophe is added for `YOUVE`/`YOURE` — the brief forbids inventing one without proof. Keeping the letters is the safe outcome.
- The 20-word domain list keeps its role; it only loses the ability to overrule a word.

---

## C. Regression table

Measured by running the **same 41 cases** through the **original `HEAD` build** and the **Phase 1 build**, on the same JVM, and diffing the output.

**Exactly 2 of 41 outputs changed. Both are the target defect.**

| Category | Input | Before | After |
|---|---|---|---|
| contraction | `YOUVE` | `HOUSE` | **`YOUVE`** |
| contraction | `THEYLL` | `HELL` | **`THEYLL`** |

Everything else is byte-identical. Acceptance criteria:

| Criterion | Result |
|---|---|
| `YOUVE` never becomes `HOUSE` | ✅ `YOUVE` |
| `YOURE` never becomes `HOUSE` | ✅ `YOURE` |
| `HOUSE` preserved | ✅ `HOUSE` |
| `THEVRE` ambiguous correction refused | ✅ `THEVRE` |
| Known vocabulary protected | ✅ 18,000/18,000 unchanged |
| Apostrophe handling consistent & safe | ✅ 11-token matrix |
| Unknown names not replaced | ✅ `KALLION`, `JANSK`, `KRASILOF` |
| Existing valid corrections preserved | ✅ all 15 clear-OCR cases |
| Original OCR retained | ✅ nothing irreversible |
| New dependencies | ✅ none |

Legitimate corrections, confirmed still working:

```
AND THAT WASNT ALL.            → AND THAT WASN'T ALL.
THIS ISNTIT.                   → THIS ISN'T IT.
SHE'S STILL ALVE...!           → SHE'S STILL ALIVE...!
COULDTHAT EVER                 → COULD THAT EVER
THERE'S A NALL AHEAO...!       → THERE'S A WALL AHEAD...!
HOW DIO YOU GET HERE           → HOW DID YOU GET HERE
IF YOU CAN HEAR ME, ANGWER ME! → …ANSWER ME!
THE COMMANO HEADOUARTERS       → THE COMMAND HEADQUARTERS
A HUNG AY GHOST PROM HELL!!!!!! → A HUNGRY GHOST FROM HELL!!!!!!
```

Ambiguity is refused, not guessed: `THEVRE`, `QZXV`, `RINSIWG`, `OQZ` all unchanged.

---

## D. Vocabulary verification

| Sweep | Count | Result |
|---|---|---|
| Every ranked word through `ReadingEnglish.repair` | **18,000** | 0 rewritten |
| Deterministic sample through the production entry point `TranslationInput.prepare` | **1,060** (every 17th, >500 enforced) | 0 rewritten |
| Casing independence (upper/title/lower) | **340** (every 53rd) | 0 disagreements |
| Domain list re-check (`suggest` on every vocab word) | **18,000** | 237 would be rewritten, 0 are |
| Apostrophe triples (bare/ASCII/curly) | 5 words × 3 forms | lookup forms agree, letters preserved |

Verified in the real Kotlin test suite, not a reimplementation.

One residual is documented rather than hidden: `ComicSpelling.suggest("youre")` still returns `house`. It is **unreachable**, because `youre` is in the ranked vocabulary and is therefore returned unchanged before `suggest` is consulted. `VocabularyProtectionTest.remainingDomainSuggestionIsUnreachableThroughRepair` fails loudly if that ever stops being true.

---

## E. Performance (§8)

Same corpus (60 OCR-style lines), same JVM, 3 warmups, 40 timed trials, median of 3 independent runs.

| | median/run | µs/line | µs/token | lines/sec |
|---|---|---|---|---|
| Original `HEAD` | 2,634–2,728 µs | 43.9–45.5 | 7.14–7.39 | 22.0k–22.8k |
| Phase 1 | 2,364–2,778 µs | 39.4–46.3 | 6.41–7.53 | 21.6k–25.4k |

**No measurable difference; the ranges overlap completely.** Structurally the cost cannot increase much: the new work is one `lowercase()` and at most two set lookups, and it is *skipped entirely* for the common case (a token already in the vocabulary short-circuits as before). In fact the guard lets `suggest`/`segment`/`insertion` be skipped for contraction tokens, which is why the modified build's best-case line rate is the higher of the two. No repeated vocabulary loading (lazy, unchanged), no object churn added, no scroll-path change.

---

## F. Actual test results

Run by the **protected release workflow** (`testReleaseUnitTest`), bound to commit `bc0dd38`:

```
suites=69 tests=375 failures=0 errors=0 skipped=0
```

RC7 was 67 suites / 350 tests. The +2 suites / +25 tests are the new
`OcrCorrectorSafetyTest` (18) and `VocabularyProtectionTest` (7).

Also green in the same run: release Kotlin compilation, instrumented-test source compilation,
Python signing/governance suite (340 tests), and the signed APK assembly.

**Independently reproduced on a real JVM before CI** (JDK 25 via `jdk4py`, `kotlinc` 2.4.21) by compiling the
unmodified production sources plus the actual test files: 58 tests, 0 failures, covering the 7 suites that
depend only on the corrector subsystem. This is how three wrong assertions in my first draft were caught —
they asserted a normalisation this phase does not perform (`YOU’RE` → `YOU'RE`) and forbade the reviewed
`WASNT` → `WASN'T` insertion. The source was right and the assertions were wrong; the assertions were
corrected to state the verified contract, and the source was not changed to make a test pass.

Main CI (`testDebugUnitTest` + debug APK) is green on `6933ee8`.

---

## G. Git integrity

| Item | Value |
|---|---|
| Baseline at phase start | `724ffef` (one docs commit after the brief's `a7b870c`) |
| `app/` tree at baseline | `fb545732…` — matched RC7 exactly |
| Branch | `arena/f6ce5fe4-manhwalens-v2` (never switched, never renamed) |
| `stash@{0}` | **intact, untouched** — the preserved foreign RC5 work |
| No reset / clean / rebase / force-push | confirmed |
| Commits added | `6933ee8` (fix + tests), `3f3500e` (guard scope + floors), `bc0dd38` (candidate name) |

---

## H. APK identity

| Field | Value |
|---|---|
| Package | `com.manhwalens.app` |
| versionName | `1.1.42-rc8` |
| versionCode | `217442879` (floor `217442878`) |
| Signer cert SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` — same as RC7, matches pinned |
| `GIT_REVISION` in APK | `bc0dd38bafa6ce4481a6db2d7f2be658a49c528d` = build commit |
| Tag → commit (Git API) | `rc-1.1.42-rc8` → `bc0dd38bafa6ce4481a6db2d7f2be658a49c528d` ✅ |
| Bytes | `120499664` |
| SHA-256 | `b5344380ebde12ce3e63022c9c58027675de2b1472d6e56248db322fc068bb2a` |
| URL | https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc8/ManhwaLens-1.1.42-rc8-release.apk |

Independently re-verified against the **GitHub release API**, not just the run's own log:
the asset digest is `sha256:b5344380…68bb2a`, matching the published `SHA256SUMS.txt`.
The release is a prerelease, never `latest`, and no old tag was reused (`rc-1.1.42-rc8` was verified unused before publication).

---

## I. Remaining issues

1. **Release-unit-test summary could mask a hole.** The evidence step globs
   `app/build/test-results/testReleaseUnitTest/TEST-*.xml` and *also* fails when `tests == 0`. Before my change, a
   candidate in the wrong directory would have quietly reported `suites=0 tests=0` and passed. I have placed
   the evidence in this order: RC7 wrote results with the same command and the step passed, and on my first two
   attempts the step failed only *after* an unrelated earlier failure. The fix is a one-line path check, but it
   is outside what this phase was permitted to touch, so it is recorded, not fixed.
2. **`WARSHIP` → `WAR SHIP`** (pre-existing, unchanged by this phase). `warship` is not in the ranked list while
   `war` and `ship` both are, so `segment` splits it. Not a Phase 1 regression; listed because it is the same
   family of defect and belongs in a later corrector phase.
3. **`YOUVE`/`THEYLL` keep no apostrophe.** Deliberate: adding one requires proof that the specific contraction is
   right, which this phase does not have. Cosmetically imperfect, semantically safe.
4. **Governance guard was re-scoped.** `EngineUntouchedTest` pinned itself to the *evaluation* phase's start and
   banned all of `pipeline/`, which forbade this phase's own deliverable. Per your instruction the baseline moved
   to RC7 and the exemption is **file-scoped** to the two corrector files; every other protected path (and every
   other file in `pipeline/`) still fails the guard — verified by touching `WordBoundary.kt` and watching it fail.
5. **Sandbox limits.** No JDK, Android SDK, Maven or Gradle were reachable here; CI was the authoritative run. The
   local JVM evidence came from `jdk4py` and the `kotlin-compiler` npm package. GitHub Actions log *downloads* are
   blocked by the network allowlist, so step conclusions were read from the API and the run summary instead of raw logs.

---

## J. Recommendation for next phase

**Text-level deduplication** — proposed earlier as next, and still correct as next, now that this phase's tests and
build pass. Two conditions carried forward:

- Land it in a phase whose guard baseline is re-pinned to `bc0dd38`, and add its files to `PHASE_ALLOWED_EDITS`
  rather than widening the directory exemption.
- Add a full-corpus soak to CI (all 18,000 words through `prepare`, not the 1,060 stride) so the production entry
  point is swept exhaustively on every build. It costs ~0.3 s.
