# ManhwaLens engineering state

## 1.1.36 — translation coverage, layout scoring, collision (unreleased)

Branch `arena/f6ce5fe4-manhwalens-v2`, PR10. Root causes and fixes: [translation coverage](docs/TRANSLATION-COVERAGE-1.1.36.md).
Text eligibility no longer requires a closed comic surface; layout candidates are scored across
columns narrower and wider than the source; only renderable blocks reserve overlay space; a single
Latin OCR leftover gets one bounded repair+retranslation. CI (compile, unit tests, debug APK) and
the API 34 emulator suite are green on commit `1f70ea2`. No physical-device run, so bubble geometry,
plate readability and end-to-end latency remain unverified.

## 1.1.29 — Android 14 checks passed; Xiaomi acceptance pending

See [stage evidence and device protocol](docs/XIAOMI-1.1.29.md).

## Current implementation — 1.1.28

Live-only, incremental page-state processing, enhanced OCR and per-session reporting. See `docs/LIVE-1.1.28.md` for the eight stages, evidence, baseline and physical acceptance protocol. No matched post-change Xiaomi measurements.

## Current implementation — 1.1.27

The supplied Redmi 1.1.26 report contains sub-32px retry boxes (HERE 18×13, JEONG-4H. 45×11), per-block exceptions, occlusions and independent fit failures. Not every failure has a retained stack trace. See `docs/REDMI-1.1.27.md`. Test-input translations and JVM/Android checks are not a physical reader-session test.

## Prior implementation — 1.1.26

Both 1.1.24 and 1.1.25 were published with the stable signer; older unreleased notes below describe historical checkpoints.

See `docs/PAGE-FUNNEL-1.1.26.md` for report evidence, page-local retries and source-aligned rendering. This supersedes the older displaced-rectangle rendering design. No new physical-device or end-to-end latency verification.

## Current branch: bubble unit implementation (unreleased)

Added bounded dark-tone bridge checks including both text-box interiors, glossary-first model casing, native RTL punctuation regression and source coverage for displaced translations. Neighboring dialogue is reserved during placement. Physical Xiaomi coverage, semantic output and end-to-end latency remain unverified. Separate balloons are not co-translated based on proximity. The investigation below records the prior checkpoint.

## Current investigation: bubble coherence and Xiaomi capture (v1.1.24 not released)

Analyzed the newly supplied v1.1.23 phone report. Findings and evidence: `docs/BUBBLE-CAPTURE-1.1.24-INVESTIGATION.md`. Grouping is inconsistent across OCR blocks, and name corruption is present before inference; even a correctly grouped example has poor semantic output. Active + ERROR is directly explained by composing independent projection and translation statuses. Consent-entry timeout is recorded, but Xiaomi AppOps denial is not established by the report. Dark-background cross-block grouping is an identified code limitation needing pixel-level confirmation for this frame.

This stage changes documentation only. Implementation, new tests, fresh GitHub build/release and physical-device verification remain outstanding. No screenshot image was supplied with this request; phone-private thumbnail paths are not accessible images. Do not claim the fixes or end-to-end latency parity are verified. Do not replace the engine, resurrect secondary UI, merge PR9, or reset the signing vault.

## Published baseline: v1.1.23 direct rendering

Branch `arena/ae360a1c-manhwalens-v2`; PR9 open, not merged. Current public release v1.1.23. Native Android, offline ML Kit EN→AR retained. Reuse existing signing vault/certificate; never reset data.

User reversed the secondary translation reader design. Its source files, threshold controller, launcher branches, controls and dependent tests have been deleted, not hidden. The main capture button remains. Successful text now tries source geometry then expands to a measured/clamped on-page rectangle, excluding the main control and other displayed translations. No extra interaction is required to reveal it.

Report: `docs/DIRECT-RENDERING-1.1.23.md`. Earlier audit reports describe historical designs and are superseded where they concern overflow presentation.

## Remaining limitations

Finite display area cannot hold arbitrary text at a readable minimum; exhaustion or rendering failure preserves source without any secondary UI. Expanded rectangles can obscure artwork. Actual Xiaomi 23106RN0DA / Android14 overlay z-order, drag timing, touch, rotation, latency and upgrade still require device checks.

General ML Kit semantic quality, unknown-name handling, full English spell correction and speaker-aware cross-bubble context remain limited as previously documented. No engine swap, cloud translation or extra OCR pass.

## Verification

Current tracked-tree removed-component/label search: zero matches at implementation checkpoint. CI 44767603972 and Android 14 regression 44767603849 passed at ffeb67d. Published v1.1.23: release build 44768373099 and existing-vault signing 44769052724 passed. Public APK: 120,189,345 bytes. Same pinned signer; no vault initialization.
