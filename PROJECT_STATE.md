# ManhwaLens engineering state

## Current work: v1.1.22 candidate

Branch: `arena/ae360a1c-manhwalens-v2`; PR9 open, not merged. Last public release at start of task: v1.1.21. Native Android / offline-first / ML Kit EN→AR unchanged. Existing durable signer reused; never regenerate it or clear user data.

Implemented this task: bounded confusion dictionary and unresolved-input gate with confirmed-name shielding; two reviewed natural whole-utterance translations; display ellipsis normalization; single transient-execution retry; confidence-aware work identity; per-item preflight isolation; explicit reader failure states; capped measured translation-only reader with internal source toggle/close; 18%-area floating-icon hide/restore.

Evidence, affected components, limitations and test details: `docs/QUALITY-READER-1.1.22.md`. Latest attachment: report 1795184359435 (185 retained samples). Prior safety design: `docs/SAFE-REPLACEMENT-1.1.21.md`.

## Non-negotiable safety

No opaque mask before complete native layout and offscreen text+background preparation. Failed/unfit/unreliable items leave source visible. Current request identity required; stale requests invalidated. No cloud translation, heavier engine, automatic OCR retry or unbounded model retry. Retry added here is one transient SDK execution retry only, per user's explicit request.

## Open issues / no unsupported claims

- Not a full English spellchecker. Ambiguous W/ALE, AGONN, HIN and unknown names are flagged rather than guessed; dictionary coverage bounded.
- No scene/speaker context for ML Kit, no reliable cross-bubble translation redistribution. Previous-region metadata is not model context.
- General fluent Arabic, pronoun/terminology consistency and semantic completeness remain unverified beyond reviewed examples.
- End-to-end latency parity on Xiaomi 23106RN0DA Android 14 is not established. Historical report baseline and emulator cleanup-only benchmark must not be conflated.
- Physical overlay/icon lifecycle, rotation/touch, upgrade/data preservation and compositor behaviour still require device testing.

## Verification

App source `642d55c`: CI 44757237108 passed unit tests/build; Android14 44757237032 passed native reader/icon tests and existing regressions. Warm cleanup microbenchmark (1,000 utterances, same run): prior 2,378,443µs total, current 1,370,046µs total. This is not end-to-end latency or Xiaomi parity. An additional severed-dialogue validator golden is covered by the release unit-test gate. Signed publication pending below.
