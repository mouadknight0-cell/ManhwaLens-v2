# Phase 4.4 — Translation input integrity and contextual proper-name protection

Candidate **1.1.42-rc3** (versionCode **217442872**), branch `arena/f6ce5fe4-manhwalens-v2`.
(`1.1.42-rc2` shipped the name/OCR work; `rc3` adds the diagnostics codes below.)
Every number below comes from a CI run on the commit named next to it. **No statement in this
document claims phone verification, and none claims a semantic-quality improvement.**

## 1. Commit and evidence

| Item | Value |
| --- | --- |
| Head commit under test | `62b7685` (phase work starts at `925838c`) |
| CI (`ci.yml`) | run [45049231119](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45049231119) — **success** (`62b7685`) |
| Release candidate (`release-candidate.yml`) | run [45049495177](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45049495177) — **success** (`62b7685`, dispatched explicitly) |
| Instrumented / emulator (`quality.yml`) | run [45045606708](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45045606708) — **success** (`89f1d0c`) |
| Earlier candidate | run [45047319657](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/45047319657) — **success** (`c300f08`), `1.1.42-rc2` |

Release unit tests on the rc3 run: **64 suites, 320 tests, 0 failures, 0 errors, 0 skipped**
(1.1.42-rc2: 63 / 313; 1.1.42-rc1: 59 / 266). Workflow-logic tests (`.github/tests`):
**41 OK** (was 28).

## 2. Why this phase exists

The phone report contained two independent defects, and conflating them is what made the first
attempts wrong. They are treated as separate classes throughout:

* **Class A — protected names.** `I CAN'T LET ANYTHING PUT LIZzy AND DOROTHY IN DANGER.` The
  engine returned `لا أستطيع السماح لأي شيء بوضع Lizzy و Dorothy في خطر.`, the model input
  carried no protected tokens (`tokens=0`), `LatinLeftoverRepair` found nothing, and the strict
  validator therefore rejected the block. Nothing was wrong with the validator: the names were
  never protected in the first place, and the recovery path could not see them.
* **Class B — OCR input integrity.** Raw OCR `A HORNED RABBIT` reached the engine as
  `A HORN ED RABBIT`. The segmenter split a valid English inflection because `HORNED` is not in
  the 18,000-word ranked list while `HORN` (rank 2013) and `ED` (rank 1792) both are.

A third report, `RINSIWG`, is **investigated, not fixed** — see §7.

## 3. Files changed and why

All 17 files are listed in §11. Grouped by responsibility:

| Area | File | Change |
| --- | --- | --- |
| Diagnostics | `pipeline/ReadingEnglish.kt` (`InputCensus`, `census()`), `pipeline/TranslationInput.kt` (`NameReport`), `translate/TranslationClient.kt` | One code per outcome, computed from the repair's own predicates; see §8. |
| Name detection | `names/ContextualNameDetector.kt` (new, 258 lines) | Conservative detector over the **original OCR text**, run before cleanup. Requires source syntax + grammar; returns the exact printed surface (`Gus's`, `GUS'S`, `LIZzy`); bounded candidates (`MAX_CANDIDATES=6`, `MAX_INVENTORY_NAMES=8`); ambiguous candidates are counted and rejected, never silently protected. |
| Masking | `names/NameShield.kt` | Possessive-first matching so `Gus's` is masked **including** its suffix instead of leaking `'s`; restore is now order-independent (was corrupting the next sentinel); remembers the exact printed spelling. |
| Input preparation | `pipeline/TranslationInput.kt` | Detector runs on raw OCR; contextual names already confirmed in the glossary are dropped (no double counting); `NameReport(detected, ambiguousRejected, truncated, reasons)` exposes structured counts only. |
| Segmentation | `pipeline/ReadingEnglish.kt` | Morphology guard: a token that is a valid English inflection of a known stem is never split, and never "corrected" into a different word. `COULDTHAT → COULD THAT` and the existing contextual repairs are untouched. |
| Leftover repair | `translate/LatinLeftoverRepair.kt` | Unchanged policy; the "no unrestricted fuzzy replacement" note is now explicit. |
| Translation flow | `translate/TranslationClient.kt` | detect → mask → translate → validate → bounded recovery; a protected name surviving is **not** an automatic ML Kit retry. |
| Validation / recovery | `translate/TranslationOutput.kt` | Recovery maps a retained name back onto the sentinel the request already assigned (see §5); requires the engine's occurrence count to match; still ends in the **unchanged** validator. |
| Release plumbing | `.github/scripts/candidate_version_code.py` (new), `.github/workflows/release-candidate.yml`, `.github/scripts/verify_release_apk.py` | Explicit `min_installed_version_code` floor; candidate above every floor, failing closed; one source of truth for the candidate name. |

## 4. Class B — HORNED before / after

| Stage | Before | After |
| --- | --- | --- |
| Raw OCR | `A HORNED RABBIT` | `A HORNED RABBIT` (unchanged, always retained) |
| Prepared input | `A HORN ED RABBIT` | `A HORNED RABBIT` |

Evidence: `OcrInputIntegrityTest` (7 tests) asserts the raw value, the prepared value, that
`HORN ED` never appears, and that genuinely separate `HORN ED` is **not** glued together. Valid
inflections absent from the vocabulary (`GLANCING`, `SINGED`, `STINGING`, `GRAYING`, `GAZING`,
`VOWING`, `RINSING`) survive untouched, and `ReadingEnglish.segment("horned")` returns `null` —
the split is refused by morphology, not by a hardcoded word.

Not claimed: this is a unit-level input result. No phone screenshot of the translated bubble
exists yet.

## 5. Class A — Lizzy / Dorothy / Gus before / after

| Case | Before | After |
| --- | --- | --- |
| `…PUT LIZzy AND DOROTHY IN DANGER.` | `tokens=0`; names reached the engine unmasked; output rejected `TR-UNTRANSLATED` | both names masked to sentinels before inference; a retained name is recovered to the sentinel it already owns and passes the unchanged validator |
| `Gus's` (possessive) | suffix `'s` leaked past the sentinel; `Gus` alone was masked, `'s` went to the engine | masked as one unit, restored with the same apostrophe style |
| `GUS'S` (uppercase possessive) | restored as `GUS` (suffix lost) | restored as `GUS'S` |
| `THE HYEONGJUNG SECT'S RECORDS` | possessive lost for lowercase glossary keys (`SECT RECORDS`) | kept for both key styles |

**Validator behaviour is unchanged and was not weakened.** The recovery only reintroduces a
name onto the sentinel the request itself minted; the `NameShield` token-preservation check still
runs, every Latin token in the output must be a declared name that exists in the source, the
occurrence count must match (a dropped or duplicated name is rejected, never repaired), and the
result must still pass `issue()`/`finish()`. No dictionary word is whitelisted, no Arabic name is
invented, and a Latin word is never accepted merely because it appears in the source.

Boundary cases proven by test: an undeclared word (`Danger`) keeps the ordinary failure; a mix of
declared and undeclared tokens is rejected; an empty declaration list cannot excuse the block.

### Two real bugs found while making this pass

1. **Sentinel restore was order-dependent.** Restoring `7000` first rewrote the leading digits of
   `7001`, so `Lizzy` came back as `Lizzy1` and the block failed validation. Restoration now
   works in token order; a longer number that merely contains a token is left alone. Regression
   test: *several sentinels are restored independently of order*.
2. **Recovery minted sentinels the mask did not know about.** `mask.maskedText` is the **source**,
   so a freshly allocated token made `issue()` fail with `TR-NAME-LOST` before recovery could be
   judged. Recovery now reuses the assigned sentinel. Earlier in the same stretch, the recovery
   pattern also demanded an apostrophe (`"['’][sS]?"`), which made a plain `Lizzy` unmatchable.

## 6. False-positive name results

Conservative detection means a rejected candidate is a **success**, not a miss. Recorded
behaviour (asserted in `ContextualNameDetectorTest` / `ContextualNameProtectionTest`):

* Common words, dictionary words and short tokens are **not** promoted to names.
* `THANK YOU, DOCTOR.` counts `ambiguous_rejected` rather than protecting `DOCTOR`.
* Capitalisation alone never makes a name; the detector needs a source-syntax and grammar
  pattern (speech verb, addressing, conjunction of two names, possessive).
* The detected count is bounded, and truncation is reported rather than silently dropped.
* Nothing detected by this path is ever written to the glossary as a confirmed entry; decisions
  are local to one translation request.

## 7. RINSIWG — evidence level

**Investigated only. Not fixed. Not claimed as the cause of any failure.**
The raw ML Kit output for that block was never captured, so the cause of its `TR-UNTRANSLATED`
status is unknown. What `RinsiwingInvestigationTest` (4 tests) does establish:

* `RINSIWG` is preserved verbatim — not split, glued, or rewritten into another word.
* No unrestricted fuzzy substitution runs: `ReadingEnglish.closestWord("RINSIWG")` is `null`.
* The earlier glyph-swap path that could turn the *correct* `RINSING` into `RINGING` is blocked
  by the morphology guard — the corruption class this phase forbids.
* Negative cases (`RINSIWG`, `QZXV`, `RINSIW`, `OQZ`) stay exactly as OCR read them.

## 8. Diagnostics

Structured reason codes and counts only — no captured dialogue, and no dialog text in any
summary. Raw model input/output stays on the existing explicit debug path, bounded to 512
characters per field.

The categories are deliberately *not* one Boolean. Each of these is now emitted:

| Code | Means |
| --- | --- |
| `input_unchanged` / `input_normalized` | preparation did / did not alter the block |
| `ocr_word_repaired` | a word the vocabulary did **not** contain was corrected (AHEAO → AHEAD) |
| `valid_word_protected` | a word outside the vocabulary survived untouched because it looks inflected (HORNED, RINSING) |
| `concatenation_split` | one token became two on evidence (COULDTHAT → COULD THAT) |
| `source_word_substituted` | a token the vocabulary *does* contain was overridden anyway (GARTH → EARTH) |
| `introduced_by:<verb>`, `coordinated_with:<name>`, `known_name_token`, `person_position:not_ordinary`, `preposition_and_name_shape` | why a name was detected |
| `shape_only` | name-shaped but ambiguous → counted, **not** shielded |
| `protected_name_preserved` / `protected_name_lost` | whether a detected name actually reached the model as a sentinel |
| `latin_word_recovered` | a leftover Latin word was recovered through the bounded path |
| `unexplained_latin` | Latin remained and the block was rejected with the source retained |

The counts come from `ReadingEnglish.census()`, which compares raw OCR with the text the engine
will actually receive using the repair's *own* predicates. It describes what happened; it never
asserts that the translation was correct.

`NameReport.summary()` emits
`names_detected=… ambiguous_rejected=… truncated=… shielded=… lost=… repaired=…
protected_words=… segmented=… reasons=…`.

Seven tests (`DiagnosticCodesTest`) pin the contract. Two deserve mention:

* **HORNED is not a repair.** It is counted as `valid_word_protected` with `repaired=0`, which is
  the distinction the phone report was missing.
* **GARTH → EARTH is not a repair either.** `GARTH` is a real dictionary word (rank 14540), so
  calling that an OCR fix would misreport a deliberate contextual override as a correction.

## 9. Release

| Item | Value |
| --- | --- |
| versionName | `1.1.42-rc3` |
| versionCode | `217442872` (floor 217442871 + 1) |
| Task | `:app:assembleRelease` — **release** build, not debug |
| Signer certificate SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` |
| Pinned certificate SHA-256 | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` (identical — existing pinned key, no new identity) |
| APK bytes | 120,363,526 |
| APK SHA-256 | `bab3d7e03d3be4624328144cb3d61c201c767aaf5f447db6cada161135cf70e5` |
| R8 / minify | disabled (unchanged; `isMinifyEnabled=false`, `isShrinkResources=false`) |
| applicationId | unchanged (`com.manhwalens.app`) |

**Direct signed APK download** —
<https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc3/ManhwaLens-1.1.42-rc3-release.apk>

Previous candidate, still downloadable:
<https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc2/ManhwaLens-1.1.42-rc2-release.apk>
(`b10682ca…`, versionCode 217442871 — it lacks the diagnostics codes).

That URL serves the `.apk` itself (HTTP 302 to the release asset, `Content-Type:
application/vnd.android.package-archive`), not a workflow-artifact zip. It is attached to the
**prerelease** `rc-1.1.42-rc3`, which is never "Latest" and is not a published release.

New in this phase: `min_installed_version_code` is an explicit workflow input, and the candidate
is computed strictly above all three of the newest published release, the installed build, and
that minimum. A missing or malformed floor **fails closed**. The arithmetic is a unit-tested
helper (13 tests) rather than inline YAML bash, because an un-installable candidate cannot be
recovered after publication.

## 10. Protected behaviour (not modified)

Capture permissions, MediaProjection consent, `LiveFrameGate` thresholds, `SceneEpoch`
cancellation, OCR confidence thresholds, translation concurrency/timeout, overlay collision
policy, Arabic layout scoring, and bubble-mask geometry are all untouched. No translation engine
change. The emulator suite's one flaky failure during this stretch was
`budget_14ms_sampling` in `PrecisionBubbleTest` — a wall-clock sampling budget on a shared
runner. The budget was **not** relaxed; the job was re-run and passed.

## 11. Every file in the change set

Implementation: `names/ContextualNameDetector.kt` (new), `names/NameShield.kt`,
`pipeline/TranslationInput.kt`, `pipeline/ReadingEnglish.kt`,
`translate/LatinLeftoverRepair.kt`, `translate/TranslationClient.kt`,
`translate/TranslationOutput.kt`.
Tests: `names/ContextualNameDetectorTest.kt`, `names/NameShieldTest.kt`,
`pipeline/OcrInputIntegrityTest.kt`, `pipeline/RinsiwingInvestigationTest.kt`,
`pipeline/DiagnosticCodesTest.kt`, `translate/ContextualNameProtectionTest.kt`.
Release: `.github/scripts/candidate_version_code.py` (new),
`.github/tests/test_candidate_version_code.py` (new), `.github/scripts/verify_release_apk.py`,
`.github/tests/test_verify_release_apk.py`, `.github/workflows/release-candidate.yml`.
Existing Phone134/135/136/137 quality fixtures still pass unchanged.

## 12. Build configuration block

```
commit SHA           : 62b7685 (PR #10 head, branch arena/f6ce5fe4-manhwalens-v2)
CI run               : 45049231119 — success (ci.yml, 62b7685)
release candidate    : 45049495177 — success (release-candidate.yml, 62b7685)
emulator run         : 45045606708 — success (quality.yml, 89f1d0c)
versionName          : 1.1.42-rc3
versionCode          : 217442872
certificate SHA-256  : 93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806
APK SHA-256          : bab3d7e03d3be4624328144cb3d61c201c767aaf5f447db6cada161135cf70e5
APK size             : 120,363,526 bytes
APK (direct .apk)    : https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc3/ManhwaLens-1.1.42-rc3-release.apk
R8 / minify          : disabled (unchanged)
unit tests           : 64 suites / 320 tests / 0 failures / 0 errors / 0 skipped
workflow tests       : 41 OK (.github/tests)
compatibility risks  : candidate requires versionCode >= 217442872 to install over an
                       existing build (rc2 users included); this is a candidate, not a stable release; phone
                       verification of the two reported cases is still outstanding; wider
                       name masking can raise TR-NAME-LOST / TR-UNTRANSLATED on blocks where
                       the engine drops or duplicates a name (rejected by design, source
                       retained); RINSIWG remains unresolved.
```

## 13. Not claimed

* Phone verification of `HORNED` or `LIZzy/DOROTHY` on the installed app.
* Any semantic translation-quality improvement.
* A root cause for `RINSIWG`.
* A published stable release. PR #10 is **not** merged.
