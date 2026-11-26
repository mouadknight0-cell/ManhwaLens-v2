# Translation evaluation infrastructure (Phase A)

Status: implemented and tested. **No translation-quality improvement was
attempted or claimed in this phase.**

This document describes a deterministic, offline evaluation system. It exists so
that any future change to the translator — or any decision to replace it — can be
judged against measured, reproducible evidence rather than opinion.

---

## 1. Why this exists

The prior audit (`docs/TRANSLATION-ENGINE-BASELINE-AUDIT.md`) established that no
bilingual evaluation of the shipped ML Kit engine is obtainable inside the
development environment: ML Kit runs only on a device, the sandbox has no JDK,
Android SDK or emulator, and CI reports pass/fail rather than translations.

Rather than assert unverifiable quality claims, this phase builds the missing
prerequisite: a corpus schema, a validation contract, a deterministic comparison
harness, and reproducible reports. Translation outputs are produced externally on
a device and imported here.

---

## 2. Corpus

Source of truth: `evaluation/corpus/rc4-corpus.json`, generated from the untouched
RC4 dataset at `docs/benchmark-phase3/dataset.tsv` by:

```
python3 -m scripts.translation_eval import-rc4
```

### 2.1 Fidelity guarantees

- All **40** records are imported. `source_text` and `reference_translation` are
  copied **byte-for-byte**; a test re-reads the TSV and asserts equality for
  every record.
- The original TSV is **not modified**. That file remains the historical record.
- Every imported case is `pending_human_review`. This is not a default that was
  overlooked — `docs/benchmark-phase3/REPORT.md` states *"no such review has yet
  occurred"*, `observations.csv` records zero executed runs, and the audit
  concluded the references are an "independent editorial reference" only.
- No reviewer or timestamp is assigned by the importer.

### 2.2 What was deliberately not classified

The source `category` column mixes semantic challenges (`idiom`, `sarcasm`,
`pronoun`, `condition`) with neutral scene labels (`everyday`, `military`,
`magic`). Only the explicitly semantic labels are mapped onto
`semantic_category`; the rest stay `unclassified`. `text_length` is left `None`
for every case because the source never states one, and deriving it would be a
guess presented as fact.

The original `category` and `scene` strings are preserved in `difficulty_tags`
and `notes`, so no information is lost by not interpreting them.

---

## 3. Schema (version `1.0.0`)

Defined in `scripts/translation_eval/schema.py`. Enums, not free strings, for
every enumerated field. Unknown values **raise** rather than defaulting.

| Group | Fields |
| --- | --- |
| Identity | `case_id`, `upstream_id` |
| Content | `source_text`, `reference_translation` |
| Review | `reference_status`, `review.reviewer`, `review.timestamp`, `review.notes` |
| Languages | `source_language`, `target_language` |
| Provenance | `source_provenance`, `dataset_version` |
| Source | `source_type` (`clean_typed` / `ocr_transcribed`) |
| OCR | `ocr_quality` (one stratum), `ocr_corruption_types` (many labels) |
| Length | `text_length` |
| Context | `dialogue_context`, `context_group_id`, `context_order`, `context_dependency`, `context_available_to_engine`, `context_for_reviewer_only` |
| Semantics | `semantic_category`, `difficulty_tags`, `expected_behavior` |
| Errors | `error_category`, `error_reason_codes`, `notes` |

### 3.1 Three context fields, kept separate on purpose

- `context_for_reviewer_only` — what a human is told.
- `context_dependency` — what the meaning *needs*.
- `context_available_to_engine` — what the engine *actually received*.

The shipped engine receives one bubble and nothing else, so the imported corpus
sets `context_available_to_engine=false` everywhere. Conflating these three would
overstate what the engine was given.

### 3.2 Review lifecycle

`pending_human_review` → `reviewed` | `rejected` | `disputed`.

Only `reviewed` **with a named reviewer and a timestamp** counts for scored
evaluation (`validates_reference`). Enforced by validation:

| Contradiction | Result |
| --- | --- |
| `reviewed` without reviewer | error |
| `reviewed` without timestamp | error |
| `reviewed` without reference text | error |
| `pending` **with** a reviewer | error |
| `rejected`/`disputed` without notes | error |
| `rejected`/`disputed` with reference blanked | error — a dispute must retain it |

There is no code path that writes `reviewed` automatically.

### 3.3 Fingerprints

`Dataset.compute_fingerprint()` is SHA-256 over canonical JSON of all cases,
sorted by `case_id`. Two corpora with the same fingerprint are identical in
content, which is what makes a comparison reproducible.

---

## 4. Harness

`scripts/translation_eval/harness.py`. Requires no network, no Android, no JDK,
no emulator and no ML Kit. Standard library only.

### 4.1 Inputs

```
python3 -m scripts.translation_eval evaluate \
  --baseline  baseline-outputs.json \
  --candidate candidate-outputs.json \
  --corpus    evaluation/corpus/rc4-corpus.json \
  --out       evaluation/reports
```

An output file is:

```json
{
  "engine_id": "mlkit-en-ar",
  "revision": "rc4-760707d",
  "config": {"glossary_revision": "..."},
  "outputs": [
    {"case_id": "rc4-01", "text": "لا عليك.", "status": "ok"}
  ]
}
```

`status` is one of `ok`, `empty`, `failed`, `missing`, `malformed`. Omitting it
derives `ok`/`empty` from the text, so a minimal file still works.

### 4.2 What it refuses to do

| Rule | Behaviour |
| --- | --- |
| Hide a missing output | Every dataset case appears in the report; absence becomes `*_missing` |
| Back-fill missing text | Missing text is `""` in the report — never copied from source, reference, or the other arm |
| Silence a duplicate id | Duplicates are recorded in `duplicate_case_ids` and in the report |
| Ignore an unexpected id | Becomes an `evaluation_error` row, because it means the wrong corpus was used |
| Skip a malformed record | Raises `HarnessError`; the run exits with code 2 |
| Call a difference an improvement | The strongest automatic statement is `candidate_changed` / `*_review_required` |

### 4.3 Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Clean run, complete data |
| 1 | Comparison valid but data incomplete (missing outputs, pending references) |
| 2 | Input error — infrastructure failure, never a translation failure |
| 3 | Corpus has integrity errors; evaluation refused |

A corpus whose references are all pending **always** exits 1. That is deliberate:
the run is informatively incomplete, not clean.

### 4.4 Normalization

`scripts/translation_eval/normalization.py`. Applied **only** to compare; stored
output is never mutated. Equivalences: NFKC, invisible bidi/formatting removal,
Arabic diacritics and tatweel removal, case folding, whitespace collapse.

A character-level `similarity()` exists for sorting, and is documented as **not**
a semantic metric. It penalises legitimate Arabic word-order changes and cannot
distinguish a synonym from an error.

---

## 5. Reports

Written as both `.json` (stable key order, machine-readable) and `.md`
(human-readable), from one source of truth. No timestamps or random ids, so
identical inputs produce byte-identical files — asserted by test.

**Report A — corpus integrity:** totals, duplicates, review-status counts, OCR
and length distributions, semantic categories, context groups, matrix coverage
and **gaps**, provenance completeness, invalid values.

**Report B — evaluation run:** run identity, engine identities and output
fingerprints, completeness, missing/malformed counts, reviewed vs pending,
results grouped by OCR quality, length, semantic category and context group, plus
per-case rows.

**Report C — regression review:** changed cases grouped by risk — candidate
missing where baseline exists, baseline missing, both missing, reviewed vs
pending, OCR-related, truncated, multi-line, context-sensitive.

### 5.1 No aggregate quality score

`semantic_quality_score` is reported as `null` with an explicit reason: verified
semantic scoring needs reviewed references, and none exist yet. Emitting a number
here would be a fabricated measurement.

---

## 6. Coverage of the required matrix

Report A lists every cell of the required matrix as covered or a gap. For the RC4
corpus the gaps are substantial and are **reported rather than hidden**:

| Dimension | Status |
| --- | --- |
| Text length | all 40 unclassified (source states none) — gap |
| OCR quality | `clean_ocr` ×38, `unknown` ×2; **no degraded strata** — gap |
| Corruption types | none represented — gap |
| Context dependency | only one 2-case group (31/32) — gap |
| Semantic categories | 9 covered, 13 unclassified |

The clean-typed corpus cannot represent OCR-corrupted input. That needs
separately labelled corrupted cases, which do not exist yet and must not be
invented.

---

## 7. Tests

94 tests, all passing locally:

| File | Tests | Covers |
| --- | --- | --- |
| `.github/tests/test_translation_eval_corpus.py` | 40 | fidelity, review enforcement, enum rejection, context groups, matrix gaps |
| `.github/tests/test_translation_eval_harness.py` | 41 | determinism, missing/duplicate/unexpected, exit codes, normalization |
| `.github/tests/test_translation_eval_safety.py` | 13 | no network, engine untouched, no private data, stash untouched |

Run exactly as CI does:

```
python3 -m unittest discover -s .github/tests -v
```

### 7.1 Environment limitation, stated plainly

The repository-wide suite reports **2 errors** under `-s .github/tests`:
`test_decode_release_keystore` and `test_signing_vault`. Both are **pre-existing**
and unrelated — they fail here because the `cryptography` package is not installed
in this sandbox. CI runs `pip install cryptography` first, so they pass there.
Those two tests are **not** reported as passed by this phase.

The JVM unit tests and Android instrumentation tests were **not run**, because
this environment has no JDK, no Android SDK and no emulator. They are unchanged
by this phase, but "unchanged" is not "verified here".

---

## 8. What this phase did not do

- No change to the translation engine, model, dependencies, lexicons, regexes,
  preprocessing, prompts or runtime behaviour.
- No new dependency; standard library only.
- No fabricated or generated Arabic reference.
- No reference marked reviewed.
- No protected area touched — verified by a test that diffs them against `e6e1387`.
- No access to the 149 stashed files belonging to other work.

---

## 9. What a future phase must supply

1. **Reviewed references.** Some subset of the 40 must be genuinely reviewed by a
   bilingual reader, with reviewer and timestamp recorded, before any semantic
   score is meaningful.
2. **Saved outputs.** Baseline (RC4) and candidate outputs exported from a device.
   The harness cannot produce them.
3. **Corrupted-OCR cases.** With `ocr_quality` and corruption labels, to populate
   the strata that are currently gaps.
4. **Length and semantic classification** of the existing 40, as a recorded human
   decision rather than derived.

Until (1) and (2) exist, this infrastructure can prove that a comparison is
*complete and reproducible*, but it cannot prove that a translation is *good* —
and it does not claim to.
