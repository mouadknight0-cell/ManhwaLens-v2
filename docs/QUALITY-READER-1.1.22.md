# EN→AR quality and floating-reader audit — v1.1.22

## Evidence first

Supplied report `ManhwaLens-full-report-1795184359435.txt`: environment v1.1.21, Xiaomi 23106RN0DA, Android 14, 185 retained samples (potentially mixed versions). Clean `THERE IS NO NEED TO FORCE YOURSELF TO MAKE A SOUND.` repeatedly produces literal Arabic. That is a translation-model defect, not OCR or RTL. `MEARING`, `AEARNG`, `W/ALE`, `AGONN` and `HIN ... 2EMEMBER` are present in OCR itself. The latter has confidence ~0.416; some newer samples already have TR-UNTRANSLATED. A technical error flag is not semantic accuracy.

The manual reader concatenated every original + translated field, including failure messages, into one large circular TextView. It was not applying error-state presentation. Automatic masking already rejected errors, but one unsafe item caused **all** candidates to be deferred to that reader. The floating icon remained independently visible. These are distinct presentation/batch problems.

No full `Although it could have…` source was found in the inspected retained histories. Its missing continuation/name cannot be inferred safely from the quoted partial example.

## Implemented

### OCR and translation

- A small reviewed confusion dictionary (`OcrLexicon`) adds unique 2→R / 0→O / 1→I-or-L candidate correction; only confused digit positions change. Context-limited IM→I'M and DONT→DON'T cleanup. Existing names are masked **before** cleanup.
- Unresolved slash/digit corruption and a bounded known-noise set (including HIN, AGONN, WLE, MEARING, AEARNG) are flagged rather than translated phonetically. Low-confidence (<0.5) input is also flagged after cleanup. Original OCR remains preserved. This is not a calibrated confidence probability or a full English dictionary.
- `W/ALE` could be WHALE or WHILE; `HIN` could be an unknown name or corrupted conjunction. They are reader-only, not silently rewritten. Alphabetic tokens are not subjected to unrestricted edit-distance substitution. **Unknown names cannot be guaranteed protected by a glossary that does not contain them**; ambiguous inputs are held for review, not guessed.
- Two reviewed whole-utterance lexicon entries implement natural Arabic for the complete sound/inner-voice examples. Not substring rewriting or a general contextual engine. Unknown Although continuations remain untouched.
- Ellipsis runs become a single display ellipsis; question marks use the existing native bidi/Arabic path. No manual reversal. Stored/copied raw model text is not globally reordered.
- One 120ms-backoff retry **only for thrown native translation execution failure**. No retry for invalid Arabic, OCR, missing names, timeouts or cancellation. A second failure propagates normally. Healthy calls incur no retry delay or extra inference.
- Cache validation namespace v3; low-confidence state added to frame deduplication identity so a bad OCR instance cannot share a successful outcome merely because its text matches.
- Existing validator name-only and short-dialogue exceptions tested. No broad weakening of output validation to hide TR-UNTRANSLATED errors.

### Failure isolation

- Reader and bottom-sheet errors use an explicit warning state, not the failure string in the translation field. Failed source remains available through the source toggle; copying a failed item copies source, not an error masquerading as dialogue.
- Preflight failures skip that item, keeping valid non-overlapping masks eligible. A reader launcher can coexist with successful masks; it no longer clears them just because another item failed. Every candidate is checked against other source boxes, including failed items.
- Window attachment failures still roll back the attachment transaction for safety. Per-item translation failure isolation does not mean ignoring rendering/system failures.

### Reader and extra floating icon

Chosen solution: **manual rounded reader**, not another automatically painted speech balloon. Original-source-aligned replacement remains unchanged for safe full-fit text.

- Translation-only default, source/translation toggle and close button inside the reader.
- Native measured text height, capped at 30% of screen area and 320dp height; width at most 360dp and screen minus margins. This avoids circle corner waste and duplicated source/translation height.
- Keep 16sp readable content and scrolling for long text, rather than shrink arbitrary text to unreadable sizes. No automatic scroll-slice masking of source.
- Hide extra floating icon when reader area reaches 18% of screen; restore on close/hide/new capture. Close the expanded action dock when opening reader. Hidden icons excluded from published occlusion bounds.
- Cap/toggle chosen over pretending an irregular original balloon has been detected. This is an explicit reader users open, not a reconstruction of artwork.

## Context: intentionally not misrepresented

ML Kit's local translator has no prompt/context parameter. The existing previous-region metadata only affects reviewed lexicon logic; passing a neighbour there does not condition ML Kit. The app has no reliable speaker or scene labels. Automatically concatenating separate speakers then redistributing Arabic by character count would invent attribution and can omit/reorder content.

Therefore no cross-bubble translate-and-redistribute implementation is claimed. Existing geometry-supported **within-region line assembly** remains. Scene/speaker-aware context and reliable cross-bubble alignment remain open, along with general pronoun/gender/title/idiom quality. No engine replacement or cloud service added.

## Regression and measurement plan

New JVM golden tests cover dictionary candidates, confirmed-name preservation, unresolved examples, natural reviewed phrases, short/name-only validator cases, error-state isolation, 18% threshold, bounded retry/cancellation and confidence-aware identity.

New Android instrumentation exercises measured capped reader, translation-only default, source toggle, close callback and hide/restore using actual View visibility. An isolated warm cleanup microbenchmark compares the previous normalization+shield+repair sequence against current cleanup on 1,000 utterances in the same emulator. This is **not** full capture→OCR→ML Kit→overlay latency.

Historical phone baseline from the supplied report, not a post-fix benchmark: 109 retained records (52 cancelled, 31 ok, 23 partial, 3 empty). T2-relative median / nearest-rank p95:

| milestone | n | median ms | p95 ms |
|---|---:|---:|---:|
| OCR complete T6 | 66 | 291.2 | 687.7 |
| first translated T9 | 54 | 417.7 | 1531.8 |
| first UI T10 | 29 | 549.1 | 1848.5 |
| non-inferred final UI T12 | 27 | 625.4 | 2330.2 |

Different milestones have different cohorts; record versions are not all established. UI marks are scheduling proxies, not proof of every line being visible. No end-to-end latency parity can be established from these numbers versus the emulator microbenchmark.

## Physical-device / unverified gates

Xiaomi Android 14: real floating WindowManager visibility/z-order/touch and icon restoration after rotation, long font-scale reader, projection lifecycle, actual upgrade/data preservation and controlled same-page latency A/B remain untested. Microbenchmark timings are not a battery or thermal result. Small dictionary coverage, unknown names, cross-bubble speaker context, natural Arabic beyond reviewed phrases and organic bubble reconstruction remain limited.

Same durable signing vault, no data clearing. PR9 stays open. Exact execution and release links follow after checks finish.

## Executed checks and measured result

- CI [44757237108](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44757237108): JVM suite and debug build passed on app source `642d55c`.
- Android14 [44757237032](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44757237032): instrumentation passed, including reader controls/actual View visibility and previous safe replacement/OCR/bidi tests.
- Earlier unit runs failed on legacy expectations `أين...` and `أنا...؟`; updated goldens to the requested Unicode ellipsis. No validator assertion was removed to hide a translation failure.
- First cleanup microbenchmark (44756309324) exposed overhead: 1,000 utterances, prior 4,466,222µs vs new 8,113,302µs. Added no-op repair fast path and precompiled patterns.
- Final same-run comparison (44757237032): prior **2,378,443µs total**, current **1,370,046µs total**, ~2.378ms vs ~1.370ms per utterance on this emulator. Shared-runner timing varies; do not compare absolute numbers across different runs or infer end-to-end speedup. Microbenchmark excludes the full confidence/non-word gate, OCR, model, rendering and retries. Xiaomi parity remains unverified.
- Final release additionally runs the explicit `THEY SEVERED THEM.. PIECE BY PIECE.` valid-Arabic validator golden. No production app changes after the tested source except release metadata.
