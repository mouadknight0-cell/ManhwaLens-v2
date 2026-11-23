# Translation-engine task: baseline audit and feasibility gate

Date: 2026-11-20. Author: agent session on `arena/f6ce5fe4-manhwalens-v2`.

This document records what was verified before any code was written for the
"offline semantic translation engine" task, and why implementation was **not**
started. It supersedes no prior document; it is new.

---

## 1. Baseline verification (Step 1) — discrepancies found

The task fixes the baseline as RC4 `760707d3dda4fd528be68620c591f9af9be31497`. Three
discrepancies had to be resolved before anything could be built on it.

### 1.1 RC4 was not present in the working clone

The clone is **shallow** (`git rev-list --all --count` = 2). RC4 was absent:

```
fatal: git cat-file: could not get object info
```

All six tags (`rc-1.1.42-rc1..rc6`) had also been re-pointed to a single commit
`360a2b1`, so no tag identified RC4 either. The commit **did** still exist in the
GitHub repository, and was fetched successfully:

```
git fetch --depth=1 origin 760707d3dda4fd528be68620c591f9af9be31497
git cat-file -t 760707d...  ->  commit
tree = a6a4a9fc63d0f837f3f4ff0346d9c957513046b1
```

RC4 is therefore verified and usable, but **only because it was fetched on demand**.

### 1.2 The session branch is 134 commits *behind* RC4, not ahead of it

The task states the branch is "22 commits ahead of RC4". Measured:

| Comparison | Result |
| --- | --- |
| `RC4 ... ca967b5` (session branch base) | `ahead_by=0 behind_by=134` |
| RC4 commit date | 2026-11-22 |
| `ca967b5` commit date | 2026-11-20 |
| `RC4 ... 999f480` (this branch's pushed head) | `ahead_by=22 behind_by=0` |

`ca967b5` is a **descendant of an older line**, 134 commits behind RC4, and two days
older. That is why the translation layer at `ca967b5` is missing 976 lines relative to
RC4: `ContextualNameDetector.kt` does not exist there, and `DialogueLexicon`,
`TranslationInput`, `TranslationOutput`, `ReadingGlossary` and `NameShield` are all
substantially smaller. Working forward from `ca967b5` would have silently discarded RC4's
name-masking and validation work.

The 22 commits the task refers to are **this branch's own carried work**
(`RC4 -> 999f480`), and they are exactly the multi-line OCR/display work: 7 source files
plus tests and a doc. **None of them touch `translate/` or the translation lifecycle.**

Consequence: the translation layer at this branch's head is **identical to RC4**. So the
required RC4 baseline is reachable *without* violating the branch constraint, by starting
from RC4's tree and adding only translation-layer files.

### 1.3 Uncommitted work was found and preserved

The working tree had 149 uncommitted changes (56 modified, 91 untracked, 2 deleted),
none of which were mine. They were preserved with `git stash push -u` before anything
else, and can be restored with `git stash pop` (stash message begins `PRESERVED:`).
Nothing was discarded.

---

## 2. Translation pipeline audit (Step 2)

### 2.1 Actual engine

ML Kit **on-device** EN→AR, against a model downloaded once on first use
(`LanguageModels.ensureReady` → `downloadModelIfNeeded`), after which inference is local.

This is important for the mission's constraints:

- There is **no cloud inference and no text transmission**; text goes to a local model.
- But the **first use requires a network download**. Any claim of "fully offline" must
  state that accurately, as the task itself requires.

### 2.2 The traced lifecycle

```
raw OCR text (TextRegion.text, retained unmodified)
  -> TranslationInput.prepare          name masking + OCR repair + normalisation
  -> ReadingGlossary.modelMask         sentinel allocation for detected names
  -> TranslationInput.nativeInput      SOURCE REWRITE (see 2.3)
  -> model                            ML Kit EN->AR, local after download
  -> NameShield.restore               sentinels -> original names
  -> TranslationOutput.issue/finish   script validation, leftover repair
  -> TranslatedBlock                  translated text, or explicit failure code
```

Failure behaviour is safe by design: every failure path returns the **original text**
with an error code (`TR-UNTRANSLATED`, `TR-TIMEOUT`, `TR-NAME-SHIELD`,
`TR-DOWNLOAD-FAILED`, `OCR-UNCERTAIN`) rather than an invented translation.

### 2.3 The main audit finding: RC4 rewrites the English source before translation

`TranslationInput.nativeInput` applies **ten hardcoded regex rewrites** to the English
before the model ever sees it:

| Regex | Rewrites |
| --- | --- |
| `blindEye` | "turn a blind eye to" → "deliberately ignore" |
| `heavy` | "too/rather/overly heavy-handed" → "harsh" |
| `obligation` | "had to/have to/must deal with" → "handle" |
| `timeObligation` | "would have had to handle it sooner or later" → "would eventually have had to" |
| `initiative` | "taken the initiative and cleared the way for" → "made it possible for ... to proceed" |
| `exclusiveQuestion` | "who else could do it but you" → "Who besides you could do it" |
| `chivalrous` | "chivalrous hero" → "honorable hero" |
| `travel` | "head to" → "go to" (only before a shielded token) |
| `certain` | "you're certain 7000 will..." → "you are sure that" |
| `cityConfusion` | "in/to/from/of HEFEl" (an OCR corruption of a place name) |

This is precisely the pattern the task warns about: *"a narrow incorrect rewrite can be
worse than a literal translation."* Each is a fixed English phrasing matched to a fixed
replacement, independent of the sentence's actual meaning and of what is inside the
bubble. They are also, by the task's own standard, a small bag of `if/else` rules standing
in for understanding — not an engine.

Note that RC4's own quality audit already reaches the same conclusion, and rates
"TranslationClient / model: ML Kit produces literal idioms, tense/person errors … OPEN
model-level blocker. No prompt to optimize." The task's own supplied RC6 evidence agrees:
`Dialogue lexicon hits: 0/48`.

**This is the honest root cause of the reported quality complaints, and it cannot be
fixed by adding more rules.** Adding a semantic layer on top of a translator that
receives a pre-rewritten English sentence cannot recover meaning the rewrite destroyed.

### 2.4 What is genuinely well-built and must be preserved

Non-trivial, tested, and worth keeping:

- **Name masking**: collision-free sentinels allocated from source digits including
  Arabic-Indic, with `TR-NAME-LOST` / `TR-NAME-SHIELD` instead of corrupted success.
- **Script validation**: `ArabicOutput.hasLetters` requires real Arabic letters, not
  merely Arabic-range punctuation or digits.
- **Source retention**: raw OCR stays in `TextRegion`; normalised text is what gets
  translated; failures return the source.
- **Cache identity**: SHA-256 over the exact normalised model input plus sorted glossary.

---

## 3. The constraint question that blocks implementation

The task requires, simultaneously:

1. No external LLM, cloud service, or remote inference (✓ satisfied — ML Kit is local).
2. "Demonstrable improvement in semantic accuracy and Arabic naturalness."
3. "Do not claim improved semantic quality without comparative bilingual evaluation."
4. "Do not use screenshots or personal captured text in public datasets."
5. No new dependency or large model before a measured trade-off.

Requirements 2–4 cannot all be met from this environment:

- **The translator is a Google ML Kit model that only runs on a device.** It is not a
  network API, so no CI job and no sandbox can produce a translation.
- The sandbox has no JDK, no Android SDK, and no emulator (`java: command not found`),
  so no measurement of any kind can be produced locally.
- CI produces **test pass/fail**, not translations.
- Therefore **no bilingual evaluation of ML Kit output is obtainable here**, and without
  it requirement 2 cannot be honestly asserted.

The only artifact that could substitute is a retained phone report — and the existing
reports are explicitly *private* and must not be committed. The RC6 metrics quoted in the
task are a session summary, not a per-case bilingual reference.

### What this means

Any translation "improvement" produced in this environment would be either:

- unverifiable — a code change whose claimed benefit no available test can confirm, or
- fabricated — exactly the "no fabricated improved output" failure the existing
  translation audit forbids.

So a new engine cannot be built *and validated* here. What **can** be built correctly is
the missing prerequisite the task itself demands in Step 7 and that RC4 largely lacks: a
deterministic, version-controlled, bilingual evaluation corpus and a harness that scores
candidates against it mechanically — so that the comparison the mission requires becomes
possible, reproducible, and independent of the translator's location.

---

## 4. What already exists (so it is not duplicated)

`docs/benchmark-phase3/dataset.tsv` at RC4 already holds 40 isolated bubble texts with an
independent Arabic reference and reviewer-only context. It is a solid start and should be
extended rather than replaced. It is a single TSV, however, so it carries no:
per-case human-review score, reason code for 0/1, review status (`pending_human_review`),
raw-OCR vs clean-typing separation, or engine/glossary revision identity.

---

## 5. Recommended next step (needs one decision, not more code)

Two viable paths, and the choice is a product decision rather than an engineering one:

**A. Build the evaluation corpus and harness first (recommended).** Extend the 40 cases to
the full required matrix (idioms, sarcasm, negation, conditionals, tense/modality,
ambiguous words, literal-vs-idiomatic pairs, names, numbers, incomplete speech, separately
labelled corrupted OCR), with all fields separate and nothing fabricated, mark every
unreviewed reference `pending_human_review`, and add a deterministic scorer. This is
implementable and testable here, it is the gate for everything else, and it makes the RC4
comparison reproducible.

**B. Provide device evidence.** A bilingual review of ML Kit output on real pages, with
the private report retained outside Git, so that a candidate can be measured against it.

Without A, no candidate can be evaluated. Without B, no quality claim can be made even
with A complete. Both are in the task's own completion criteria.

---

## 6. Actions taken, and what was deliberately not done

Done:

- Verified RC4 by fetching it and confirming its tree.
- Established the true relationship between RC4, the session branch base, and this
  branch's 22 commits.
- Preserved 149 files of uncommitted work found on session start.
- Completed the required source audit of the translation lifecycle and the source
  rewriter.
- Established that the branch's translation layer equals RC4's, so the baseline is
  reachable without branching.

Not done, deliberately:

- No new engine, model, dependency, or rules were added. There is no evidence that would
  justify any of them, and the task forbids speculation.
- Nothing in the protected areas was modified: no OCR, capture, layout, mask, overlay,
  signing, or release file was touched.
- No quality claim of any kind is made.
