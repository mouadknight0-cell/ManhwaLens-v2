# Phase B2.1 — Evaluation reliability, review-data integrity, APK stability

Phase B2 moved the evaluation loop onto the phone. B2.1 is a correction phase: it
audits that work against the code that actually shipped, and fixes what the audit
found. **It changes no translation, OCR, context, bubble or rendering behaviour.**

Everything below was confirmed by reading the code at `921de866` first. Where a
claim came from the B2 report, the report was treated as a hypothesis, not proof.

## The `GIT_REVISION` hole (the most serious defect)

`app/build.gradle.kts` has recorded a `GIT_REVISION` `buildConfigField` since
Phase B2, and every evaluation run writes that value as its `engine_revision`.
The field was never populated: **no workflow set `GIT_REVISION`**, so every run
any APK ever produced was stamped `unknown`.

That is worse than a missing field, because `unknown` is also the value the store
gives a genuinely unreproducible run. A run that cannot be tied to a commit and a
run from a pre-release developer branch became indistinguishable, and
`reproducible` silently read `false` for all of them.

Now:

* `release-candidate.yml` resolves `git rev-parse HEAD`, **asserts** it matches
  `^[0-9a-f]{40}$`, and exports it as `GIT_REVISION`. It fails closed rather than
  exporting an empty or abbreviated string.
* `ci.yml` and `quality.yml` record the same full SHA after checkout, so a
  build from those workflows is traceable too.
* `app/build.gradle.kts` **rejects a malformed `GIT_REVISION`** with a
  `require(...)` naming the offending value, instead of recording whatever it was
  given. Absent still means `unknown` — that distinction is the point.
* `verify_release_apk.py` gained `--git-revision` / `--require-git-revision`,
  validates the value with `re.fullmatch(r'[0-9a-f]{40}', ...)`, and writes
  `git_revision=` into the identity report.
* A new workflow step fails the candidate when the APK's recorded
  `git_revision` differs from the commit under test, before any publication.

**Never accept a non-empty string as a SHA.** `main`, `HEAD` and a 7-character
abbreviation all used to be acceptable by shape; they are not.

## The versionCode clamp

`effectiveVersionCode` ended in `coerceAtMost(appVersionCode)`. The comment above
it described this as a safety measure, but it clamps *down*: when the computed
candidate code exceeded the commit's per-commit code, the candidate shipped the
smaller number. A build below the installed `217442875` is rejected by Android as
a downgrade, and the only recovery is an uninstall — which destroys the owner's
history, samples and benchmark records.

The clamp is removed. The impossible case now throws, naming both numbers and the
reason, so a future inconsistency fails the build instead of shipping an APK the
phone refuses. This is the only `app/build.gradle.kts` change beyond the
identity constant, and the safety test permits it only in that exact shape (see
*Exceptions* below).

## Run scope versus corpus coverage

A run recorded only its results, so a run asked to execute one case was
indistinguishable from a 40-case run that had executed one case. Three different
numbers were collapsed into one, and the single number reported the most
flattering of the three.

Runs now persist `requested_case_ids` (and the count) at creation. Therefore:

* a one-case run displays as **1/1 complete, 1/40 covered**;
* resume is confined to the original scope — a one-case run stays a one-case run;
* widening the scope is a **new run**, never an append to the old one;
* a resume whose configuration no longer matches (different model identity,
  dataset fingerprint, engine revision, or model availability) is refused with a
  stated reason, and the old run is left untouched;
* records written before scope tracking existed keep `requested_case_ids = null`.
  `null` means *unknown*, and is never reconstructed from the results that happen
  to exist. Scope-unknown runs report `scope_known: false`, show a warning, and
  their resume control is disabled rather than quietly guessing a scope.

`attempted_count` counts attempts only: `failed` and `empty` are attempts,
`not_run` and `missing` are not. `completed = existing.size` was wrong because it
counted rows in the file, including rows for cases that were never asked.

## Assessment identity is `(caseId, runId)`

Assessments used to be keyed by `caseId` alone and looked up with
`associateBy { it.caseId }`. A rating given for one run's translation therefore
followed the case id onto a later run's translation that the reviewer had never
seen.

* An assessment is now stored and matched on `(caseId, runId)`.
* There is no rebinding, and legacy records with `runId = null` are preserved as
  **unbound**: counted, reported, and excluded from every run's review and export
  until a human binds them explicitly.
* The review screen selects a run, shows which run it is reviewing, and reloads
  that run's assessments when the selection changes.
* Nothing can be assessed unless the run actually contains an `ok` result with a
  non-blank translation. `missing`, `not_run`, `failed` and `empty` are refused in
  the UI *and* invisible to the export.

## Rating vocabulary

The B2 doc mentioned `not_applicable`; the code only had a comment about it.

There are now three distinct states, and none of them is `null` or `0`:

| State | Meaning |
|---|---|
| `not_reviewed` | the reviewer did not judge this criterion |
| `not_applicable` | the reviewer judged the criterion inapplicable |
| `"0"`–`"3"` | an actual rating |

A reason is required for `0` or `1`. A reviewer name is required to save at all.
A form where every criterion is still `not_reviewed` cannot be saved as
`reviewed`. There is **no aggregate score** and no automatic verdict: similarity
and fluency are inputs to a human judgement, never a substitute for it.

## Export

`exportRun` now takes the full corpus rather than the ids it happened to have,
because the old signature forced `sourceText = ""` for any case without a result
— preserving the case's existence while destroying the only thing that made it
identifiable.

Every export contains all 40 cases in stable id order, each with
`{case_id, source_text, result, reference_review?, candidate_assessment?}`.
A case with no result is exported as `missing` **carrying its real English
source**. Assessments appear only when `runId` matches. Scope, attempts, corpus
size, pending and out-of-scope counts are separate fields. The TXT export carries
per-case detail, including every criterion (so `not_applicable` is never confused
with `not_reviewed`).

Exports carry no dictionary contents, and state their own limits explicitly:

```
native_model_context: false
translation_quality_claimed: false
live_sample_annotations_included: false
assessment_binding: "case_id+run_id"
unbound_legacy_assessments_included: false
```

## Saved-run browser

The `runs` list was loaded and never rendered; review was pinned to `latestRun()`
with no way to see or export anything older. The screen now lists every saved
run with its own status, scope, attempts, revision and app version, and each can
be viewed, exported as JSON, or exported as TXT. Selecting a run is a visible,
reversible choice.

## Engine invocation parity

`EvaluationRunner` called `translateBlock(region)`; production calls
`translateBlock(region, learnedNames)`. The benchmark was therefore measuring a
different call than the app makes, with confirmed names never supplied.

The invocation is now built in one place and carries the confirmed names exactly
as production does, config is snapshotted once per run, and the fingerprint
reflects what actually reached the engine. `TranslationClient.kt` is untouched;
the seam is a function the tests can inspect, and a test asserts the arguments
arrive.

## Corpus integrity

Unchanged, and asserted rather than assumed: 40 unique non-empty cases, expected
fingerprint `5fb61a31…`, `native_model_context = false`, engine input limited to
exactly `{case_id, source_text}`, and `context_for_reviewer_only` never passed to
the engine. `EvaluationCorpus.validate()` now checks all of this before a run
starts and throws rather than running against a corpus it cannot vouch for.

## Release tooling

The B2 report recorded — but did not fix — that the release-floor lookup used
`gh release list --exclude-drafts` without `--exclude-pre-releases`, so a later
candidate run could read its own earlier prerelease tag as the newest official
release. Both flags are now present, and a test asserts the exclusion. (The
versionCode floor itself was never wrong: it takes the max of three inputs.)

A new candidate has to be published under a **new, unique** name; the previous
RC's assets are neither replaced nor mixed.

## Exceptions, stated openly

Two pre-existing safety tests were edited, and both edits strengthen rather than
weaken them:

* `test_translation_eval_safety.py` — `app/build.gradle.kts` may now diff against
  the protected baseline, but only by adding `GIT_REVISION` lines **and** by
  removing exactly the `coerceAtMost` clamp lines, and the replacement must
  contain `throw GradleException(`. The forbidden-token list still rejects
  `applicationId`, `signingConfig`, `minifyEnabled`, `implementation(`,
  `classpath`, `minSdk`, `targetSdk`, `compileSdk` and `proguard`. The path
  allowlist was widened for the workflow, script and test files this phase
  necessarily touches.
* `test_export_includes_absent_cases` — now asserts `corpusCases` / `CorpusCase`,
  which is what the narrowed export signature requires.

No assertion was deleted, and no invariant was dropped.

## Verification

`python3 -m unittest discover -s .github/tests` — **304 tests**, and the only
errors are the two module-level `cryptography` import failures in the pre-existing
signing tests (`test_decode_release_keystore`, `test_signing_vault`); that module
is installed in CI and absent in this sandbox.

`.github/tests/test_translation_eval_phase_b2_1.py` (69 tests) covers each of the
twelve defects: identity plumbing and validation, the removed clamp, scope
storage and resume confinement, attempt counting, assessment binding, the rating
vocabulary, export completeness and ordering, run browsing, and corpus
validation.

The Kotlin is **not** compiled here: this environment has no JDK, no Android SDK
and no emulator, so CI was the first compiler. Reading the source and balancing
braces is not the same as a compile, and it did not catch what the compiler
caught: three source errors in this phase's own code survived a careful local
review and only surfaced in CI.

| Error | Cause |
|---|---|
| `No value passed for parameter 'text'` | a `Text()` left behind with its content removed during the sample-detail rewrite |
| `Argument type mismatch: Int, String!` | `JSONObject.names()` returns a `JSONArray`, not name strings |
| `exposes its private-in-class parameter type 'Config'` | `invocationFor` became `internal` while `Config` stayed private |
| `unresolved reference` (earlier) | a heredoc-escaped `${'$'}{...}` artifact in a `Corrupt` message |

That is worth stating plainly rather than hiding: the local checks were useful
(brace balance caught a prematurely closed data class) but insufficient.

## Published candidate

`rc-1.1.42-rc4`, built from `756c3ee`, all checks green:

```
versionName 1.1.42-rc4     versionCode 217442876     package com.manhwalens.app
apk_bytes 120499664        signed_release true
apk_sha256 98eabc3049f97e2b18a475f1764277dbfcfa3ff28f3ab5811c0dc6043203146c
signer cert 93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806  (full value, matched)
unit tests 67 suites / 350 tests / 0 failures
```

Every gate passed for that build: compilation, 350 release unit tests,
instrumented test compilation, signature and certificate, the identity check
(APK `git_revision` equal to the commit under test), the debug-signing check, and
the download check that re-fetches the published file.

`rc-1.1.42-rc3` also built green from `f5b33ab` and keeps its own four assets;
rc4 exists because removing the temporary diagnostic step necessarily touched a
workflow path and rebuilt, and republishing that under the rc3 tag would have
replaced verified assets with a build of a different commit. `rc-1.1.42-rc2` is
untouched. `app/` is byte-identical between the rc3 and rc4 builds; only the
workflow and one test differ.

Why there were two green candidates: the diagnostic step was added to read the
compiler's errors (job logs are not retrievable in the environment this phase was
written in), and it had to be removed once it had done its job. That removal is
the only difference.

`Android quality regression` passed on the same application code at `f5b33ab`,
including the Android 14 emulator OCR and circular-view tests.

## RC5 — the tag did not name its own APK

rc4 built green, passed every identity check, and was still wrong in a way none of
those checks could see: **its tag did not point at the commit the APK was built
from.**

| | |
|---|---|
| `rc-1.1.42-rc4` tag resolves to | `360a2b1638eb36da07aad3d1bb4f1bcbdc5ccdf1` |
| that release's APK records | `756c3ee08b7f3c39b6841ecf694dce90f0c69e3f` |

`360a2b16` is a merge commit on `main` from Nov 19; `756c3ee` is the branch commit
the APK was assembled from. The tag was never about the build.

**It was not only rc4.** Every candidate published before this fix carries the same
tag:

```
rc-1.1.42-rc1 .. rc-1.1.42-rc6   ->  360a2b16  (all six)
rc-1.1.42-rc7                    ->  5160ef6d  (this build)
```

Three separate causes, each individually sufficient:

1. `gh release create` was called **without `--target`**, so GitHub created the tag
   on the repository's *default branch*. This is the direct cause.
2. The step began `gh release view "$TAG" || gh release create "$TAG"` — it reused
   any existing tag and then uploaded with `--clobber`, which can attach a new APK
   to an old tag.
3. Nothing ever read the tag back and compared it to the build commit. The release
   title and notes contain the commit SHA, which looks like proof and proves
   nothing: they are free text, not a ref.

### What the workflow does now

* **A taken tag is a hard stop.** Before publishing, the tag is looked up; if it
  exists the run fails and the operator picks the next name. A published ref is
  never moved and an APK is never re-uploaded onto one.
* **`--target "$GITHUB_SHA"` is explicit**, so the tag names the commit under test
  rather than whatever `main` happens to be.
* **The tag is resolved back through the Git API and compared to `$GITHUB_SHA`
  before a single asset is attached.** Upload no longer uses `--clobber`.
* The APK's own `git_revision` is checked against `$GITHUB_SHA` first, so a
  mismatch fails instead of publishing.

`.github/scripts/verify_release_tag.py` does the resolution. A lightweight tag's
ref points directly at a commit; an annotated tag's ref points at a tag object
that must be dereferenced, possibly through further tag objects. It handles both,
and treats anything it cannot prove (missing tag, chain too deep, target that is
not a commit, abbreviated expected SHA) as an error. Verified against live tags:
it dereferences the annotated `v1.1.0` correctly, and refuses rc4 against the rc4
build commit with both SHAs named.

### Floors

The dispatch defaults had been raised to `217442876` while the push-event
fallbacks still said `217442875`. A push-triggered run therefore built against an
older floor than the identical run dispatched by hand — the same class of defect
as the original stale floor. Both now read `217442876`, and
`test_push_fallbacks_equal_the_dispatch_defaults` asserts each fallback equals its
default so the two cannot drift apart again.

`rc5` and `rc6` were already published (both pointing at `360a2b16`), so the new
candidate is **`1.1.42-rc7`**, the next unused name.

### Published candidate — verified

Run `45234054144`, commit `5160ef6dc73a3f71bae5f779ac8bd423d81fcb0c`:

```
tag              rc-1.1.42-rc7  ->  5160ef6dc73a3f71bae5f779ac8bd423d81fcb0c
                 (lightweight, resolved through the Git API, == build commit)
GIT_REVISION     5160ef6dc73a3f71bae5f779ac8bd423d81fcb0c   (in APK-IDENTITY.txt)
versionName      1.1.42-rc7
versionCode      217442877            (>= 217442877, floor 217442876 + 1)
package          com.manhwalens.app
signer cert      93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806
pinned cert      93794a2fb7635cbe20503fefc300f90bf1f7f1e11d6d0db86c6f7531ce8e0806  (equal)
apk_sha256       3c776e7ea4cd0477957e53e7bc77218698457e3c6570ad8b177c9b4061972171
apk_bytes        120499664
signed_release   true
unit tests       67 suites / 350 tests / 0 failures
```

The published download was re-fetched and hashed by the workflow and matches
`3c776e7e…`. The identity report inside the release body carries
`git_revision=5160ef6d…`, identical to the tag target — which is the check that
would have caught rc4.

Earlier releases are untouched: rc2–rc6 keep their original `updated_at` and
their four assets each.

**No application code changed in RC5.** The diff is the workflow, one new script,
one new test module, and the safety allowlist entry for those two files.
