# Phase B2 — Phone-only translation evaluation, evidence review and corpus benchmark

Phase A built the offline evaluation, and Phase B1 added a reference-review sheet.
Both assumed something the owner does not have: a computer. ADB, an
instrumentation runner and a Python review sheet all need a PC, so this phase
moves the whole evaluation loop onto the phone.

The rule that shapes every decision here: **this phase adds evaluation
infrastructure and changes no translation behaviour.**

## What the owner can now do from the phone alone

Settings → Diagnostics → **Translation Evaluation · تقييم الترجمة** opens a screen
with three independent sections.

| Section | Evidence source | What it answers |
|---|---|---|
| **عينات مباشرة** (live samples) | The 48 real `TranslationSamples` from real screen captures | What did the app actually output on my screen, and why might it be wrong? |
| **اختبار 40** (corpus benchmark) | Clean typed text from the RC4 corpus | How does the installed engine handle 40 known inputs, reproducibly? |
| **مراجعة بشرية** (human review) | The 40 canonical Arabic references plus device output | Do I accept each reference, and how good is each candidate translation? |

They are kept apart because they are not the same kind of evidence. Pooling them
would let a clean typed corpus result stand in for real capture behaviour, or let
a single reviewer's opinion look like a measurement.

## Section A — live sample inspector

Each sample shows its identity (`journey:left:top`), journey and trace id, app
version, raw OCR text (`ocr_source`), the prepared input (`source`), the Arabic
translation, processing status, error code, method, duration, OCR confidence, and
the exact surface coordinates.

Evidence is displayed when the sample kept it. Only the twelve most recent rows
retain a full `pipeline` map (that is the existing cap, unchanged here), and rows
that lost it say so. **Absent evidence is reported as absent, never as an empty
output** — those are different claims.

### The screenshot rule

A sample id begins with a journey id, and the journey id finds the corresponding
`CaptureResult` in `HistoryStore`, whose thumbnail is the real saved image for
that capture. That association is used, and nothing else is:

* the journey id is recovered by matching a known history id, **not** by splitting
  the sample id on `:` — a journey id may itself contain a colon, and a naive split
  could associate the sample with a different capture and show a wrong screenshot;
* when no history entry matches, the card says there is no saved image for that
  journey. It never falls back to a different, unrelated thumbnail;
* the thumbnail is shown whole, with no crop. Sample coordinates are in screen
  space and the thumbnail is a 512 px downscale, so a crop computed without a
  verified scale factor would point at the wrong region. A wrong crop is worse
  than no crop: it invents evidence.

Annotations (suspected cause, note, reviewer) are stored in the evaluation store,
separately from the sample. The sample itself is never modified.

### Suspected cause is not root cause

The eleven causes (`OCR_ERROR`, `OCR_REPAIR_ERROR`, `TEXT_SEGMENTATION_ERROR`,
`CONTEXT_LOSS`, `NAME_OR_TERMINOLOGY_ERROR`, `NATIVE_MT_ERROR`,
`ARABIC_GRAMMAR_ERROR`, `POST_PROCESSING_ERROR`, `BUBBLE_ASSIGNMENT_ERROR`,
`DISPLAY_ERROR`, `UNKNOWN`) are candidate labels. Choosing one records a
hypothesis; it does not confirm anything, and the UI says so above the chips.

## Section B — the 40-case benchmark

### One corpus, two payloads

Both application assets are generated from `evaluation/corpus/rc4-corpus.json`:

```
evaluation-engine-input.json   kind=engine_input  cases: {case_id, source_text}        40
evaluation-human-review.json   kind=human_review  + reference, classification, context 40
```

The split exists so that what reaches the engine *cannot* contain a reference
translation or reviewer-only context — not because the engine is trusted to
ignore them, but because they are in a different file. Tests assert the engine
payload's case objects have exactly two keys.

`load_canonical_dataset()` reads the JSON through the same `EvalCase.from_dict`
constructor the Phase A importer used, so the app assets and the offline corpus
cannot diverge. Regenerating them after this change produced byte-identical
files, which confirms the earlier TSV-derived assets already matched.

### How a run executes

A run goes through the production `TranslationClient` with the real runtime
configuration (post-translation corrections, the confirmed name glossary, the
reading glossary), the installed ML Kit EN→AR model, and a `TextRegion` of the
case's own text at confidence `1.0`.

It deliberately does **not** capture the screen, request MediaProjection, start a
capture session, create an overlay, or run OCR. The corpus inputs are clean typed
text; invoking OCR would misrepresent where the text came from.

Evidence collection is switched on through `TranslationClient`'s existing
`debug: () -> Boolean` constructor parameter, which populates the evidence map the
client already builds. No production file is edited, and the switch cannot change
a translation decision.

Two details that keep the results honest:

* **The cache is cleared before each case.** Several corpus cases share identical
  English (12/13/14, 06/11, 08/16, 21/25). Without clearing, the second would
  return the first's translation from cache and the run would report a
  translation that was never computed for that case.
* **A returned function call is not a completed case.** A blank translation is
  `empty`, an error code is `failed`, an absent model is `not_run`. Three states,
  never collapsed into one.

### Model readiness

The app shows the real model state. If the model is not installed, the run records
every case as `not_run` with `MODEL_UNAVAILABLE` and the run status
`model_unavailable` — an unavailable model is not a translation failure. Nothing
downloads until the owner taps the button that says it will.

`ensureModel(allowDownload = false)` only *observes*; it does not call
`ensureReady()`, because that call fetches the language file when it is missing and
a silent download is precisely what must not happen here.

### Context

`TranslationClient.translateBlock` accepts a `previous` argument, and it is used by
the local `DialogueLexicon` match. It is **not** passed to ML Kit. Every run record
therefore stores `native_model_context: false` and
`context_supplied_to_native_engine: false` per case, and the screen says so before
the run starts.

This benchmark measures isolated-bubble translation. It is not a contextual MT
evaluation, and the export cannot be read as one.

## Section C — human review

Two independent decisions, because they answer different questions.

**Reference review** — `pending_human_review`, `reviewed`, `disputed`, `rejected`.
Nothing is ever approved automatically; a reference stays pending until a person
decides. The original reference is preserved alongside any corrected Arabic, the
reviewer's name is required, the timestamp is recorded on explicit save, and
disputed or rejected decisions require an explanation.

**Candidate assessment** — meaning, fluency, grammar, context suitability,
names/terminology and completeness, each an explicit 0–3 rating or
`not_reviewed` / `not_applicable`. There is no aggregate score: averaging six
human judgements produces a number nobody can interpret. Fluency is not
correctness, and a translation that resembles the reference is not thereby
approved.

Review decisions live in `reviews.json` and `assessments.json` inside the private
evaluation store — never in the run records, and never in Git.

## Storage and export

Everything is under `filesDir/evaluation/`:

```
runs/<runId>.json   one run, rewritten atomically after each case
runs.json           index of runs
reviews.json        reference decisions
assessments.json    candidate assessments
annotations.json    live-sample annotations
```

`AtomicFile` gives every write a rename-or-nothing outcome, so a process death
mid-save cannot leave a half-written run. Resume skips cases that already have a
result and **never fabricates the ones that do not**. Runs are additive; an
earlier run is never overwritten, and a re-run of failed cases creates a new run.

Export is only ever triggered by an explicit tap, through the Android document
picker or the share sheet, and the privacy warning is on screen next to the
buttons. The JSON carries the run identity, the dataset fingerprint, every dataset
case (including the ones with no result, marked `missing`), all intermediate
evidence, the review decisions and the provenance. A TXT report is offered
alongside it. Nothing is uploaded, and nothing is shared automatically.

`exportRun()` iterates the **dataset** case ids rather than the run's recorded
cases, so a case that never ran appears as `missing` instead of being absent. An
export that silently omits the failures would overstate the run.

## Revision identity

`BuildConfig.GIT_REVISION` is the one build change in this phase, and it is
identity-only: CI passes the commit, otherwise the value is the literal string
`unknown` and the export marks the run non-reproducible. No SHA is ever invented,
and no runtime code reads the field.

The safety test still forbids edits to `app/build.gradle.kts`; it now verifies this
exception instead of waving it through — the diff must be pure additions, must not
touch `versionCode`, `versionName`, `applicationId`, signing, SDK levels or
dependencies, and must contain the revision field. Nothing else about the build
file may change.

## What this phase does not do

* No model, prompt, regex, lexicon, translation rule, OCR algorithm or runtime
  behaviour is changed.
* No claim about translation quality. The corpus results are observations; the
  ratings are one person's judgements; neither is a quality score.
* No synthetic or paraphrased Arabic. References are the RC4 originals, still
  pending review.
* No existing user data is touched: `HistoryStore`, `TranslationSamples`,
  benchmark records and the Phase A corpus are read, never rewritten.
* No private evidence is committed.

## Verification

`python3 -m unittest discover -s .github/tests` — 225 tests, 2 errors from the
`cryptography` module that CI installs and this sandbox lacks (pre-existing, in the
signing tests).

`.github/tests/test_translation_eval_phase_b2.py` (48 tests) covers asset
existence and fingerprinting, payload consistency against the canonical corpus,
engine/reviewer separation, absence of committed private evidence, and source-level
contracts for the runner and store.

The Android runner cannot execute here — there is no emulator, no JDK and no ML Kit
model in this environment. The Kotlin has not been compiled locally; CI is the
first compiler, and the instrumentation tests require a device. That limitation is
real and is not worked around by asserting on source text as if it were behaviour.

## Two defects this phase found and fixed

Building the phone-only loop exposed two problems that had nothing to do with
translation, and both had been invisible for a while.

**The APK was not installable.** The first candidate built for this phase carried
`versionCode 217442871` while the phone already had `217442875`. Android rejects
that as a downgrade, so the owner would have had to uninstall — which the task
forbids, and which would destroy the 14 history records, 48 samples and 42
benchmark records in the process. The floor arithmetic in
`candidate_version_code.py` was correct; the workflow's pinned floor
(`217442870`) predated releases 1.1.39–1.1.41 and the installed build. Both floor
inputs now start at `217442875`, producing `217442876`, and
`WorkflowFloorDefaultsTest` fails if any floor ever drops below the installed
build again.

**The instrumentation test never ran.** From the B1 commit onward,
`TranslationEvalExportTest.exportRealEngineOutputForEveryCorpusCase` was declared
`= runBlocking { ... }` and ended in `Log.i(...)`, which returns `Int`. JUnit4 only
runs `void` methods and rejects the whole class with
`initializationError: Failed to instantiate test runner class
AndroidJUnit4ClassRunner`. So that suite ran zero tests while `ci.yml` stayed green
— CI only *compiles* the androidTest source set and never executes it. It is now a
block body, and `JUnitVoidTestMethodsTest` inspects every `= runBlocking` test body
at brace depth zero so the shape cannot return.

Related, for the record: the quality workflow's failure that appeared to be caused
by these changes was one of these two, plus a separate pre-existing
timing-sensitive test. `PrecisionBubbleTest` asserts a bubble mask exists, and
`overlay/BubbleMask` rejects with `budget_14ms_component` when a wall-clock budget
is exceeded. On a loaded emulator that reject is intermittent; the same test
passed on the rerun of an unchanged commit. Nothing in `overlay/` or
`PrecisionBubbleTest` was touched by this phase, and the two commits that fixed
the real defects did not alter that code path.

A note on the release-candidate workflow that is *not* fixed here: its
"newest published release" lookup runs `gh release list --exclude-drafts` without
`--exclude-pre-releases`, so a later candidate run reads its own earlier
prerelease tag as the newest release. It does not affect the versionCode floor
(the floor takes the max of three inputs), but it makes the reported
`last_release` misleading. It belongs in a release-tooling change, not in an
evaluation phase, so it is recorded rather than changed.
