# RC10 Phase G — starting-point audit (step 1)

Written **before** any code change, from the repository itself, not from the RC9 report.
Everything below is a claim I can point at a file and line for.

---

## 1. Repository state at start

| Item | Expected (per your reference data) | Found | Verdict |
|---|---|---|---|
| Branch | `arena/f6ce5fe4-manhwalens-v2` | same | ✅ |
| RC9 `versionCode` | `217442880` | `217442880` (release body) | ✅ |
| RC9 revision | `b084876e9f8c8bf643166ec9478cf0a827de7c26` | resolvable after fetch | ✅ |
| Local `HEAD` | RC9 tip | `ca967b5` + 184 dirty entries | ❌ **diverged** |
| Stashes | — | 0 | ✅ |
| RC9 commits present | 7 | 7 (`19d2184`…`a9b6b60`) | — |

### The divergence, and what I did about it

The sandbox clone had reset to `ca967b5` (the RC8-era commit) while the remote
was at `a9b6b60`. Before touching anything I established that **no content was
at risk**, by hashing rather than by assuming:

```
git add -A && git write-tree            -> 58f69f8a5a38239d67ad7779d2625d08080deeea
git rev-parse FETCH_HEAD^{tree}         -> 58f69f8a5a38239d67ad7779d2625d08080deeea
```

Identical. The working tree *was* RC9 — the divergence was only the branch
pointer and index state, not the files. I then moved the pointer with
`git reset --mixed a9b6b60`, which touches the index and `HEAD` and nothing else.
No `--hard`, no `clean`, no `rebase`, no force. Recovery recipe preserved for the
next reset: `git fetch origin <branch>` then `reset --mixed FETCH_HEAD`.

`docs/RC9-RELEASE-REPORT.md` and the six RC9 commits are intact and unmodified.

---

## 2. The text path, traced (not assumed)

```
ScreenCaptureService
  ColorRecovery.recognize  -> ocr/TextRecognitionClient.recognize
                                 RegionFilter -> DialogueAssembler.assemble(lines)
                                 (bridges two boxes only if LightCorridor/TonalCorridor)
  -> scale to frame coordinates
  -> BubbleReconstructor.collapseDuplicates      [RC9]
  -> BubbleReconstructor.reconstruct             [RC9]
  -> prepareRegions (filter + RegionClassifier)
  -> FrameTranslationPlan.build(Input(region, kind), contentBottom)
  -> ConnectedDialogue.linked(...) chain, max 4  [Level-3, exists already]
       translateBlock(joined) -> ConnectedDialogue.split(arabic, sources)
  -> translateBlock(region, learnedNames, previousSource)
       TranslationInput.prepare -> OcrLexicon/EnglishOcrRepair/ReadingEnglish
                                -> ContractionRepair + RepairLedger  [RC9]
       DialogueLexicon.interjection / match(normalized, previous, terms)
       ReadingGlossary.modelMask -> NameShield.mask (7000-sentinels)
       ML Kit translate -> TranslationOutput.issue -> MeaningValidator  [RC9]
  -> onReady: SessionScorecard stages, BenchmarkTracker, HistoryStore, TranslationSamples
```

### What already exists that I must not rebuild

| Capability | Where | Note |
|---|---|---|
| Region kind (dialogue/narration/SFX) | `RegionClassifier`, `RegionKind` | reused, not replaced |
| Sequential-bubble joining | `ConnectedDialogue.linked/split` | **on the real path**, max 4, gated on `surface` mask |
| Previous-utterance context | `FrameTranslationPlan.Key.previous` → `DialogueLexicon.match` | 1 utterance, one hard-coded rule (`am I` after `where`) |
| Glossary / names | `ReadingGlossary`, `NameShield`, `NameGlossaryStore` | per-app, **not per work** |
| Semantic review | `MeaningValidator` | RC9, information-preservation only |
| Repair audit | `RepairLedger` | RC9 |

### What genuinely does not exist

1. **No work/chapter identity anywhere.** `grep workId|chapterId|comicId|seriesId`
   over `app/src/main` returns nothing. `HistoryStore` is capped at 100 entries
   with no work key; `TranslationSamples` is a flat 200-row list. So the
   "isolate one work from another" requirement has **no foundation to build on
   yet** — it has to be created, not adapted.
2. **No story memory.** Nothing accumulates names, gender, relations, events or
   terminology across frames.
3. **No context retrieval of any kind.** `previous` is one string from the
   previous *region index*; `DialogueLexicon` uses it in exactly one rule.
4. **No confidence separation.** One OCR confidence; no reading-order, parse,
   sense, or context confidence.
5. **Level-3 joining is gated on pixels.** `ConnectedDialogue.linked` returns
   false unless `surface.reliable` on both sides and mask kind/ink/oval agree —
   i.e. the same light-corridor dependency that RC9's Phase B had to work around
   for split *lines*. Sequential *bubbles* in a decorated panel will therefore
   not join.

---

## 3. Decision on the small local model (your §10/هـ)

**Not adopted for RC10, on evidence, not preference.**

| Criterion | Finding |
|---|---|
| Current stack | ML Kit EN→AR is a *remote-downloaded* model; the language file is fetched once and then runs on-device. It is already ~120 MB of APK+model. |
| APK size | The RC9 APK is already **120 534 106 bytes**. A bundled ONNX/NLLB-class model adds hundreds of MB — an order-of-magnitude regression on a release that must install over RC9. |
| RAM / CPU | Unknown on the target phone; I have no device to measure on, and I will not claim a number I did not measure. |
| Provenance | I cannot verify licence + size + quality without downloading model weights, and network access to model hosts is blocked from this environment. |
| Verdict | **Deferred, explicitly.** The architecture below is model-agnostic: the semantic layer consumes an *analysis*, so a local model can be substituted later without touching the rest. Building it unproven would be exactly the "adopt a model because it exists" failure you warned against. |

What replaces it: a **rule- and lexicon-based analysis layer** plus **retrieval**,
which is what the requirement actually describes (understand meaning, not
memorise translations).

---

## 4. What RC10 must add (scoped to what proves itself)

Ordered so that each step is independently testable:

- **G1 Observation + reading structure** — a `TextObservation` type carrying the
  *captured* facts separately from *inferred* ones, and a reading-order/panel
  grouping that does not rely on geometry alone.
- **G2 Semantic representation** — subject/verb/object, tense, negation, mood,
  entities, pronoun referents, each tagged with an evidence **kind** and a
  confidence, per your §7 six-way distinction.
- **G3 Context builder with escalation** — use the narrowest level that resolves
  the ambiguity; only widen when the sentence is actually ambiguous (§14).
- **G4 Story memory + retrieval** — local, per-work, source-attributed,
  confidence-bearing, correctable, with confirmed/probable kept apart.
- **G5 Arabic surface improvements** driven by the analysis, with a **fallback
  policy**: if the new path is less supported than the baseline, keep the
  baseline (§11 — "do not ruin a good ML Kit result").
- **G6 Verification with separated confidences** and a `REVIEW_REQUIRED` outcome.

I will not touch `LiveFrameGate`, `SceneEpoch`, `PageState`, stale cancellation,
or the frame-stability gate — those are RC9 constraints that still stand.

---

## 5. How I will test (§15), given the constraints

Local JVM harness rebuilt this session; **163 RC9 tests pass** before any change.

Two harness limitations, stated now rather than discovered later:

- The local JUnit shim is a Kotlin `object`, so `import org.junit.Assert.*` is
  illegal for it. Suites using the star form compile in CI (real JUnit) and are
  **not** run locally. New RC10 tests use the single-member import form.
- Android-dependent production files (`HistoryJson`, `ProgressiveTranslation`,
  `TranslationCache`, `NameGlossaryStore`, `LanguageModels`) cannot compile on a
  plain JVM. Anything RC10 adds that memory needs must stay **pure Kotlin** so it
  is testable here; persistence adapters stay thin and Android-side.

The five-level context comparison in §15 will be run as an ablation over one
case set: no context → region/utterance → panel/page → dialogue history → story
memory. Every case records which level actually changed the decision, so a level
that adds nothing is reported as adding nothing.

**No human-quality claim will be made** without reference translations. The
report will separate JVM-verified, Android-verified, build-verified, and
unverified exactly as RC9 did.
