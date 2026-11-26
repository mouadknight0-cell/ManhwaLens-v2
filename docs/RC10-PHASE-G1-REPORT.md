# RC10 Phase G.1 — Context engine audit and RC10 candidate

**Status: READY FOR DEVICE VALIDATION — NOT YET FIELD-VALIDATED**

No translation-quality claim is made in this document. The engine has still never processed a real
Manhwa page; every fixture in the test suite is synthetic. What follows separates what was built and
verified from what was audited and found, and from what cannot be claimed without the phone.

---

## A. Repository state (verified against GitHub, not local state)

| item | value |
| --- | --- |
| branch | `arena/f6ce5fe4-manhwalens-v2` |
| remote HEAD (fetched) | `1d6565c` |
| PR | #10, **OPEN**, base `arena/ae360a1c-manhwalens-v2`, mergeable |
| worktree | clean (0 modified, 0 untracked) |
| Phase G commits | `a54ddd1` (engine + wiring), `92fb3dc` (phase guard) |
| Phase G.1 commits | `045d3db` (§14 release fix), `1d6565c` (comparison + report) |
| RC9 tip | `a9b6b60`; RC9 build commit `b084876` |
| proof of base | `git merge-base --is-ancestor a9b6b60 HEAD` → true; `b084876` likewise |

### The stale-clone event, and what was done about it

This session began with the local clone at `ca967b5` — the *parent* of RC9's tip — while GitHub had
`92fb3dc`. This is the second occurrence of the Phase G failure mode, from sandbox re-cloning.

No destructive operation was used. The sequence was:

1. `git fetch origin arena/f6ce5fe4-manhwalens-v2` → the real tip.
2. Proved `ca967b5` is an ancestor of `92fb3dc` (fast-forward possible, no history rewrite).
3. Proved the worktree content was already correct: all **358** tracked blobs at `92fb3dc` hashed
   byte-identically against disk — 358 identical, 0 missing, 0 differing. The large `git diff`
   figure earlier was an artefact of untracked files being invisible to the index, not lost work.
4. `git reset --mixed 92fb3dc` — moves HEAD and the index only; the worktree was untouched, so
   nothing could be lost even if the check in step 3 had been wrong.

No force-push, no rebase, no `--hard`, no `clean`, no history rewrite at any point.

### Tags

`rc-1.1.42-rc9` → `b084876` (RC9, unchanged). `rc-1.1.42-rc10` **did not exist** before this phase
and was created by the release workflow from `1d6565c`.

---

## B. Audit of the Phase G engine (answered from the code, not the previous report)

### B.a — Does it understand, or only analyse?

**It analyses. It does not yet translate.** This is the central finding of the audit and it changes
what the benchmark is for.

`UtteranceAnalyzer` builds a `SemanticFrame`: polarity, mood, tense, entities, pronoun surfaces,
open questions. `ContextBuilder` escalates by level. `StoryMemory` retrieves facts. `SemanticVerdict`
checks the result. But the only component that produces Arabic is `ArabicPhrasing.phrase()`, and
inspected directly its **only** transformation of the text is terminal punctuation:

```kotlin
Mood.QUESTION    -> if ('؟' in tail) text else "$body؟"
Mood.EXCLAMATION -> if ('!' in tail) text else "$body!"
```

Everything else it does is *recording*. Glossary terms produce the reason `glossary_term_available`
with the comment "nothing is swapped blindly". A resolved gender produces the reason
`gender_resolved_feminine` and **does not touch the Arabic**.

Measured on the real production path (`ContextTranslation.run`), with a deliberately
wrongly-punctuated baseline:

| case | reached | settled | Arabic changed? | why |
| --- | --- | --- | --- | --- |
| `Stay back!` | NONE | NONE | **yes** | punctuation only |
| `I'll make him regret throwing me away!` | STORY | NONE | **yes** | punctuation only |
| `He looked tired.` | STORY | NONE | no | declined |
| `That day, I became a mage.` | STORY | NONE | no | gender unresolved |
| `That day, I became a mage.` **+ feminine memory** | STORY | **STORY** | **no** | gender resolved, text untouched |
| `Is that so` | NONE | NONE | no | declined |
| `NO ENTRY` (sign) | NONE | NONE | no | declined |
| `They left without a word.` | STORY | NONE | no | declined |

So today: **the engine can settle a question but cannot act on the settlement.** Story memory
resolves a gender and the Arabic keeps whatever agreement ML Kit chose. That is a safe design — it
cannot corrupt anything — but it means Phase G.1's benchmark will predominantly measure `NO_CANDIDATE`,
and that is the correct, honest outcome rather than a failure.

**Consequence for §10:** the quality axes (sense, idiom, naturalness, register) cannot be evaluated
for this engine yet, because it emits no text of its own to evaluate. Reporting otherwise would be
inventing results.

### B.b — Does context play a real role?

Yes, and the ablation record is now a contract rather than a claim. `ContextPackage` separates:

- `level` — how far the walk **reached** (cost),
- `settledAt` — what **answered** something (benefit),
- `escalations` — which levels were consulted,
- `unresolved` — what stays open.

A walk that climbed to story memory and answered nothing reports `reached=STORY, settled=NONE`.
That distinction was a defect fixed in Phase G and is pinned by tests in both
`ContextBuilderTest` and `ContextBenchmarkTest`.

### B.c — Are the evidence rules correct?

The five requirements were each checked against code and test:

| rule | where it is enforced |
| --- | --- |
| retrieving a snippet ≠ settling a pronoun | `ContextBuilder.answers()`; levels 3–4 settle only `ENTITY_IDENTITY` |
| a nearby name ≠ the pronoun's referent | `aNeighbouringNameDoesNotSettleAGenderedPronoun` |
| a character in memory ≠ the current speaker | `answers()` and `memoryEntryFor()` are question-kind-scoped |
| old memory does not beat clear current text | `EvidenceKind.rank`; `anOldMemoryDoesNotOverrideTheCurrentSentence` |
| a hypothesis never becomes a fact | `EvidenceKind.HYPOTHESIS` can never answer a fact question; `theReloadedMemoryStillRefusesAHypothesis` |

### B.d — Is memory stable?

Isolation (asserted twice, on filename *and* on read), atomic write-then-rename, damaged files
quarantined as `.corrupt-<time>` rather than deleted, Arabic emitted unescaped so it round-trips
byte-identically, and a hostile work id (`../../etc/passwd`) proven unable to escape the directory.
All covered by `StoryMemoryJsonTest` (18) and `StoryMemoryFilesTest` (18).

**One design limitation found, not a defect:** memory is capped at 600 facts per work, keeping the
newest by sequence. A long-running title will silently evict its oldest facts. This is bounded and
documented, but it is not yet surfaced to the user, and it should be before any large-scale run.

---

## C. What was built this phase

### §14 — the release job (fixed, and verified fixed)

Root cause: the job defaulted to `1.1.42-rc9`, whose tag is published, so it reached its own
"refuse a taken tag" guard. The guard is correct; the default was stale. Advanced to `1.1.42-rc10`,
floors raised to `217442880` (RC9's shipped code) yielding candidate code `217442881`. The pinning
test caught a real second-order defect while I made the change: the `env` fallbacks and the `inputs`
defaults had drifted apart, and `test_push_fallbacks_equal_the_dispatch_defaults` failed.

**Verified by running it**: run `45451947760` passed every step including keystore decode, signer
pin verification, release unit tests, instrumented-test compilation, assemble, and signature /
certificate / identity / versionCode verification.

### §3/§4 — the fair comparison

`ContextBenchmark` with the three modes. The fairness guarantee is structural: `BenchmarkCase`
carries the OCR text and baseline Arabic that were already produced, and carries **no image**, so
OCR cannot be re-run per mode and one mode cannot be given different input. A test reads the type's
own fields to keep it that way.

`ContextMode.A_BASELINE` never invokes the engine at all, so the control survives a broken engine.
`B_SHADOW` runs the engine and shows the baseline. `C_GATED` shows the candidate only through the
shipping acceptance rule.

### §6 — level-by-level measurement and hindsight

`ladder()` measures each of the six ceilings separately in the engine's own escalation order, keeping
`reached` and `settled` distinct per rung. Memory from a later chapter is withheld, the withholding
is **recorded** as `FUTURE_KNOWLEDGE_EXCLUDED`, and a separate test proves that opting in is what
changes the answer.

### §8 — the exportable report

Hand-written JSON with no `org.json`, so it is testable on a JVM. Carries build identity (versionName,
versionCode, full commit), mode, and per case: source text, baseline, candidate, displayed text, the
context codes with provenance, the eight confidences **separately**, timings, the user verdict and
any error. A missing candidate exports `null` plus `NO_CANDIDATE`; an unmeasured confidence exports
`null`, never `0.0`. There is no image field, so a page image cannot be exported by accident.

### §11 — the ten Phase G defects

All ten remain covered by tests (verified by name, not by assumption):

1. retrieval ≠ resolution · 2. no random `he` binding · 3. STORY reached ≠ success ·
4. sentence-initial "The" is not a name · 5. hypothesis never becomes fact · 6. two works never
mixed · 7. Arabic survives save/load byte-identically · 8. corrupt file is quarantined, not deleted ·
9. old memory loses to current text · 10. engine failure cannot corrupt the baseline.

---

## D. Test and build state

| suite | result |
| --- | --- |
| engine (`context/` + `memory/`) | **223 tests / 12 suites / 0 failures** |
| full JVM tree | **531 tests / 55 suites / 0 failures** |
| `.github/tests` | **340 of 341 pass** (2 errors need keystore material this clone lacks) |
| CI `Test & assemble debug APK` | **success** (`1d6565c`) |
| CI `Official release candidate` | **success** (`1d6565c`) |

---

## E. RC10 candidate artefact

| field | value |
| --- | --- |
| release | `rc-1.1.42-rc10` (prerelease, **not** latest) |
| APK | [ManhwaLens-1.1.42-rc10-release.apk](https://github.com/mouadknight0-cell/ManhwaLens-v2/releases/download/rc-1.1.42-rc10/ManhwaLens-1.1.42-rc10-release.apk) |
| size | 120,599,646 bytes |
| SHA-256 | `5e0160735bf777d5d04eefa51e097735d124b49f2aaad1955d9ab41d1d8c248a` |
| versionName / versionCode | `1.1.42-rc10` / `217442881` |
| signer certificate | `93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806` (unchanged from RC9) |
| git revision | `1d6565cd4248c61aca8ce8d3c58f2c3f73b94059` |
| installable over RC9 | yes — 217442881 > 217442880 |

The certificate is identical to RC9's, so this installs as an update and does not require an
uninstall. **This has not been tested on a phone.**

---

## F. What is still missing, stated plainly

1. **No on-phone benchmark UI.** §7 asks for a phone screen showing source, baseline,
   candidate, chosen text, context, timings, and four rating buttons. **Not built.** The comparison
   and export *logic* is complete and tested; the Compose screen and the `TranslationClient` mode
   switch are not.

2. **No device run.** No performance figures exist — P50/P90, RAM, engine start-up, memory growth
   after several pages are all unmeasured. §12 is not satisfied.

3. **No human semantic review.** No reference Arabic. §10's twelve axes are unevaluated. The
   report states this in its own summary so the file cannot be misread as a quality claim.

4. **No real Manhwa page has passed through the engine.** All fixtures are synthetic.

5. **The engine cannot yet emit improved Arabic.** Per B.a, it can resolve a question and cannot
   act on it. Closing that gap is the substance of the next phase, and it must be done against
   real pages rather than more fixtures.

### Why the phone UI was not built in this phase

It would have been the visible deliverable, and skipping it is a deliberate trade. The screen must
be driven by the comparison contract, and that contract did not exist until this phase. Building the
UI first would have meant encoding untested assumptions about modes, fairness and export into Compose
code that this environment cannot run, on top of an engine that — per B.a — will emit `NO_CANDIDATE`
for almost every real page today. A tester would have spent a session producing a report of nulls.

The order taken was: fix the release path, make the comparison provably fair, make the report
honest, then build the thing a finger touches. That leaves one phase of UI work with no unknowns
behind it, instead of a UI whose numbers nobody can trust.

---

## G. Phase success criteria

**Software: substantially met, with one deliberate omission.** Base verified, Phase G proven on RC9,
engine and memory tests green, no RC9 regression, Gradle and CI green, three modes present,
same-input comparison present, diagnostics present, export present, release job fixed, signed APK
installable over RC9. **The three-mode switch inside the app and its phone UI are not built**, so
"modes exist" is true of the logic and not yet of the product.

**Field: not attempted.** Status is **READY FOR DEVICE VALIDATION — NOT YET FIELD-VALIDATED**.
