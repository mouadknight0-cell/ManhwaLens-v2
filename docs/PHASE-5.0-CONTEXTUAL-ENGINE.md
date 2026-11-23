# ManhwaLens — Local Contextual Translation Engine (Qwen3 0.6B)

**Phase:** contextual EN→AR translation · **Branch:** `arena/f6ce5fe4-manhwalens-v2`
**Commit under test:** `99a9479`

---

## 0. Summary, stated before the details

The engine architecture, batching, prompting, output validation, context isolation, model download
and integrity, settings UI and 87 unit tests are **built, committed, and CI-green**.

The engine **cannot yet translate on a device**, because no llama.cpp native runtime is compiled
into this build. That was your explicit decision (`runtime: defer`), and the code is written so that
this is a *designed, visible state* rather than a silent failure: selecting Qwen Local shows
"engine unavailable in this version", the reason is recorded, and translation falls back to ML Kit.
Nothing is fabricated, and no bubble is silently left blank.

So: **the contextual pipeline exists; the inference backend is a seam awaiting a runtime.**

---

## 1. RC4 baseline

| Item | Value |
|---|---|
| Commit | `760707d3dda4fd528be68620c591f9af9be31497` |
| Subject | `fix(release): verify the published APK by digest, not only by size` |
| Verified | `git cat-file -t` → `commit`; tree checked out before any edit |
| Working tree at start | **dirty**, 151 uncommitted changes on a *different* base (`ca967b5`) |

**Two discrepancies found and reported rather than worked around:**

1. **The requested branch could not be created.** This session is pinned to
   `arena/f6ce5fe4-manhwalens-v2`. Work is on that branch, based on RC4 content.
2. **The rc5/rc6 release tags are wrong.** Both `rc-1.1.42-rc5` and `rc-1.1.42-rc6` point at the
   **same** commit `360a2b1` (a PR #8 merge dated 2026-11-19), and that commit does **not** contain
   the OCR fix rc6's release notes advertise (which claims commit `3ef67fb`). This is independent of
   the current task but you should know: the rc6 APK was not built from the code it describes.

Preserved work: the previous branch tip `984a079` (containing rc6 and its OCR reconciliation) remains
reachable on GitHub and locally by SHA. Nothing was deleted.

Because the branch history now diverges from the previous tip, the push was a force-push protected
by `--force-with-lease` against `984a079`, so a concurrent update would have failed rather than been
overwritten.

---

## 2. What the specification got wrong (verified, not assumed)

### The model file does not exist

Your instruction was to verify the artifact rather than trust a filename. Doing that found a real
problem:

```
GET /api/models/tensorblock/Qwen_Qwen3-0.6B-GGUF/tree/main
  → Qwen3-0.6B-Q2_K.gguf    347,288,672
  → Qwen3-0.6B-Q3_K_M.gguf  413,978,720
  → README.md
```

**There is no `Q4_K_M` file in that repository** — even though its README table lists one. Pinning
the specified path would have shipped a download that could never verify.

### The specified size is also wrong

`396,705,472` bytes is **378.3 MiB**, not the ~462 MiB in the brief. The real number is used
everywhere, because an incorrect expected size is indistinguishable from a corrupt download.

### Pinned artifact

| Field | Value |
|---|---|
| Repository | `unsloth/Qwen3-0.6B-GGUF` |
| File | `Qwen3-0.6B-Q4_K_M.gguf` |
| Size | `396,705,472` bytes (378.3 MiB) |
| SHA-256 | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` |
| License | Apache-2.0 |
| Architecture | `qwen3` (confirmed in GGUF metadata) |
| Chat template | present (`<|im_start|>` / `<|im_end|>`) |
| Download URL | `https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf` |

The weights are **not** in the APK.

---

## 3. Files

New package `app/src/main/java/com/manhwalens/app/engine/`:

| File | Purpose |
|---|---|
| `TranslationEngine.kt` | `EngineId`, `DialogueItem`, `TranslationBatch`, `TranslationOutcome`, `EngineFailure` codes, engine contract |
| `DialogueBatchBuilder.kt` | Reading order, stable IDs, batch capping, wrapped-sentence test |
| `QwenPrompt.kt` | System contract, numbered multi-bubble prompt, context rendering, sanitisation, token estimate |
| `QwenOutputParser.kt` | Balanced-JSON extraction, strict validation, per-ID rejection |
| `DialogueContext.kt` | Bounded rolling context with hard session isolation |
| `QwenEngine.kt` | Lazy load, mutex serialisation, unload, failure codes, timeout |
| `LlamaRuntime.kt` | Native seam + `UnavailableRuntime` (fails closed) |
| `ModelStore.kt` | `ModelStore` / `FileModelStore`, exact-size + marker verification |
| `ModelState.kt` | Install state machine + `ModelDownloadPolicy` |
| `ModelDownloader.kt` | Resumable Range download, space gate, GGUF check, SHA-256, promote |
| `TranslationEngineManager.kt` | Selection, observable state, fallback with reason |
| `MlKitEngine.kt` | Thin adapter over the existing `TranslationClient` |

Modified: `settings/SettingsRepository.kt` (engine selection by stable code),
`ManhwaLensApp.kt` (store + manager wiring), `ui/SettingsScreen.kt` (one compact engine group).

**Not modified:** OCR, capture, `MediaProjection`, `LiveFrameGate`, `SceneEpoch`, overlay,
`ReplacementTranslationView`, bubble geometry, `TranslationClient` internals, `TextRegion`.

---

## 4. Translation architecture

```
ScreenCaptureService (unchanged)
   │  prepared regions, reading order
   ▼
TranslationEngineManager.resolve()
   ├─ ML Kit selected / Qwen unusable → MlKitEngine → existing TranslationClient
   └─ Qwen selected and ready        → QwenEngine
                                          │ DialogueBatchBuilder → ids 1..n + regions
                                          │ DialogueContext     → ≤4 bounded turns
                                          │ QwenPrompt          → numbered dialogue
                                          │ LlamaRuntime        → (seam)
                                          │ QwenOutputParser    → validated map
                                          ▼
                                    TranslatedBlock per region (geometry intact)
```

**Identity.** `DialogueItem.id` is assigned once from reading order and then travels with the item.
Results are mapped **by ID**, never by index. A re-sort, a partial reply or a concurrent completion
cannot move a translation onto a neighbouring bubble.

**Batching.** Up to 8 items per batch (`MAX_ITEMS_PER_BATCH`), each call translating a whole screen's
dialogue in one model pass — the point of the feature. ML Kit remains one-at-a-time and is not
pretended to be contextual (`isContextual = false`).

**Qwen does not run after ML Kit on every bubble.** When Qwen is selected and ready it translates the
batch directly; ML Kit runs only as the fallback, with a recorded reason.

---

## 5. Context management

- Every session has a `Key(book, chapter)`. A change of **either** starts a new session and discards
  everything — the leak that produces fluent, confident, wrong pronouns.
- Bounded at **4 turns / 1200 characters**, oldest evicted first.
- Only **validated** translations are admitted. A Latin echo or a blank is refused, so an OCR error
  cannot become permanent context.
- Speaker identity is never modelled; turns are attributed pairs only.
- `reset()` on new book, on engine stop.

---

## 6. Validation (structured output)

A reply is used only when it honours the id contract. Rejected outright: duplicate ids, invented ids,
unparseable JSON. Reported per-id: missing id, blank text, surviving Latin (an untranslated echo is a
failure, not a result). Tolerated deliberately: markdown fences, prose around a balanced object,
string-typed ids, out-of-order entries.

**A failed region is never dropped silently** — it becomes a `TranslationOutcome.Failure` with a
distinct code (`MISSING_ID`, `MALFORMED_OUTPUT`, `TIMEOUT`, `OUT_OF_MEMORY`, …) so the caller can fall
back and record why.

---

## 7. Model install

App-private persistent storage (`filesDir/models`), never cache. Download is **resumable** via Range
requests; a server that ignores Range is detected and restarted rather than appended to. Gates before
use: free space + 64 MiB headroom → GGUF magic/version precheck → full streaming SHA-256 → atomic
promote. A `.verified` marker alone is not trusted: exact size **and** recorded hash must both agree.
Removal deletes model, partial and marker.

---

## 8. Tests

**87 new JVM tests** (no device, no bitmap), added to RC4's 311 → **398 total in 67 suites**.

| Suite | Tests | Covers |
|---|---|---|
| `QwenOutputParserTest` | 19 | fences, out-of-order, duplicates, invented ids, blanks, Latin echo, truncation, braces/quotes in strings, concatenated objects |
| `DialogueContextTest` | 12 | chapter/book isolation, reset, turn + character bounds, refusing Latin/blank |
| `DialogueBatchBuilderTest` | 14 | reading order, stable IDs across re-sort, duplicate text, batching, wrapped-sentence rules, geometry preservation |
| `ModelDownloadPolicyTest` | 22 | resume offsets, oversize restart, space gate, GGUF header, hash compare, store lifecycle, removal |
| `QwenEngineTest` | 20 | fails closed without runtime, lazy load, single load, release/reload, malformed/partial replies, OOM, fallback with reason |

Every fixture asserts **actual expected Arabic text and IDs**, not counts.

CI run `45140513790`: **success** — compiled app, test and instrumented-test sources,
`testDebugUnitTest` passed, debug APK assembled.

---

## 9. Translation quality: not claimed

**I have not compared Qwen output against ML Kit output, because the engine cannot run yet.** Any
accuracy, naturalness or dialogue-consistency claim would be invented. §11 of your brief requires
evidence for a quality claim; there is none, so none is made.

What does exist is the mechanism to obtain it: `QwenEngine.isContextual` allows a contextual-vs-
per-bubble A/B, and the parser reports per-ID completeness, so missing translations are countable
rather than invisible.

---

## 10. Latency and memory: not measured

Nothing has run. The design targets, stated as targets and not results:

- model unloaded until first use; explicit `release()` on stop;
- `contextTokens = 2048`, `maxTokens = 512` — bounded, not "as much as fits";
- threads = `cores/2` capped at 4 (~half the device), to protect UI responsiveness and thermals;
- 90 s inference timeout so an unbounded generation cannot freeze the app;
- batch cap 8 to bound prefill cost.

Expect roughly 6–10 tokens/s for a 0.6B Q4 model on a budget CPU, i.e. **15–25 s per screen**. That
is an estimate, not a measurement, and it is the single biggest open question about whether this
feature is usable in practice.

---

## 11. OCR / overlay changes

**None.** No file in the OCR, capture or overlay path was modified. Regions reach the engine with
their original screen coordinates and come back attached to those same region instances, so
translations cannot land on the wrong bubble or in the wrong place.

---

## 12. Build status and APK

| Field | Value |
|---|---|
| CI (`99a9479`) | run `45140513790` **success** (398 tests, 0 failures) |
| Debug/test APK | built by CI (`assemble debug APK`) |
| Model | **separate**, 378.3 MiB, not in the APK |
| APK size | see CI artifact — reported separately from the model download by design |

No release candidate was published for this commit. A signed release would currently ship a
functional settings section and a disabled contextual engine; publishing it as a *translation
improvement* would misrepresent it.

---

## 13. Known limitations and next steps

1. **No inference backend.** `UnavailableRuntime` is installed. This is the only thing standing
   between the current state and working contextual translation.
2. **No device run.** Not verified on hardware: download, resume, hashing, load, tokens/s, RAM,
   thermals, cancellation. Everything above is static or JVM-tested.
3. **Quality unmeasured** (§9), latency estimated (§10).
4. **Not wired into `ScreenCaptureService`.** Deliberate: the capture path is protected by your
   constraint, and wiring a batch call into it before the runtime exists would add an untestable
   branch to the most fragile file in the app. The manager and adapters are ready for it.
5. **Batch-per-screen, not per-bubble streaming** — with Qwen selected, the user waits for the whole
   screen.
6. **`tensorblock` repo README is misleading.** If you prefer that host, it has Q2_K and Q3_K_M only.

**Recommended next step, in order:** choose the runtime (`defer` is still open) → mobile CI build with
NDK/CMake, or supply a vetted AAR with its hash → implement `LlamaRuntime` against it → run the §11
corpus on hardware to measure quality, latency and peak RAM → only then wire the batch call into the
capture path and publish a candidate.
