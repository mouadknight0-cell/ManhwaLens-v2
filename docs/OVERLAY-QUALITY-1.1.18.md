# v1.1.18 — source-aligned rendering, evidence and remaining quality gates

## Evidence reviewed

- New private TXT: `ManhwaLens-full-report-1795164853677.txt`, 973,583 bytes, environment v1.1.17 (217326097), Xiaomi 23106RN0DA / Android 14 / 720×1600. Includes 100 retained history captures, 157 samples (all sample version fields 1.1.17), 74 unique benchmark traces and 14 logged errors. Private full report is not committed.
- Samples: 128 without a technical error, 11 OCR-UNCERTAIN, 11 TR-INVALID-OUTPUT, 7 OCR-CLIPPED. No claim that the 128 are semantically correct. Examples include SMAUL, COULO, COULONT/GOLO, clipped phrases and browser/search text. Model literal/temporal/idiom errors persist even when OCR is correct.
- Historical screenshots directly inspected: 2026-11-19 21-51-15-922, 21-51-00-141 and 20-46-24-941. They show an oversized combined reader, linked/tailed bubbles, rectangular multi-line source text and artwork that should not be covered. These are NOT new v1.1.17 screenshots. A cropped original conference bubble (without phone UI/personal identifiers) is retained as an Android test asset with source crop coordinates (330,170)-(670,530).
- Report Capture=INACTIVE/Live=OFF is export-time state, not evidence that capture failed during the session.

### Retained timing baseline (not before/after evidence)

74 records: 35 cancelled, 24 ok, 12 partial, 2 empty, 1 skipped. For the 36 ok/partial records with relevant marks (nearest-rank p95):

| Since T2 | n | median ms | p95 ms |
|---|---:|---:|---:|
| OCR end T6 | 36 | 354.5 | 538.3 |
| First translation T9 | 36 | 457.1 | 684.6 |
| First UI-post T10 | 36 | 563.0 | 886.2 |
| Processing end T11 | 36 | 633.7 | 1567.5 |
| Final UI-post T12, excluding inferred | 28 | 719.2 | 1830.9 |

Benchmark records have no explicit app-version field; these are this report's retained records, not independent proof of version attribution. No new-build phone measurements exist. Cancelled records are not counted as model crashes. UI-post proxies are not actual rendered-frame timestamps. Warm/cold/cache differences prevent a casual aggregate speed comparison.

## Root causes and implemented changes

1. **Mask geometry vs circle:** bounding text rectangles are not circles. A diameter cap, circle transparent corners, collision-driven displacement and alpha entrance exposed English or covered unrelated art. Default translations now use opaque source-aligned replacement surfaces, never collision-shifted, with no fade-in or decorative shadow/stroke.
2. **Evidence-based selection:** bounded RGB probes run on the capture worker, not UI. An axis-aligned oval requires uniform light surroundings, four boundaries, near-elliptical boundary evidence in at least 15/16 directions, adequate source coverage and no overlap with another detected region. Otherwise use the padded OCR rectangle. Tails/organic/rotated/linked contours are NOT reconstructed; they normally remain visible around a rectangular interior mask.
3. **Background:** sample the padded source perimeter. A sufficiently uniform perimeter supplies an opaque median color; uncertain/complex surroundings use opaque white. This is NOT inpainting. Background texture under text cannot be reconstructed with this method. Only the detected source rectangle (+2px) is guaranteed covered; missing OCR words outside it are not magically masked.
4. **Adaptive fitting:** native StaticLayout handles Arabic shaping, bidi, line wrapping and centered layout. Binary search 12–30sp in at most five trials plus final layout, using the actual rectangular or inscribed oval content area and scaledDensity. Font padding is included for glyph/diacritic safety. No ellipsizing or partial translated string is used as if complete.
5. **Finite-area fallback:** if minimum readable font cannot fit, use an explicit ellipsis details marker at the original position, opening the full source/translation on tap. This is Mode C, not a claim that arbitrary-length dialogue can fit without scrolling or expansion. The original source area stays opaque. Reader interactions remain available; the existing detail reader scrolls.
6. **Numeric OCR regression:** `$123` was incorrectly treated as uncertain because it was neither all digits nor sufficiently alphabetic. Preserve numeric/currency/time/percentage expressions without model inference, while retaining clipped-edge validation and rejecting mixed letter-digit words from this numeric rule. Method is recorded as numeric preservation, not ML translation.
7. **Diagnostics:** preserve surface hint/bounds/color in samples and history; log surface preparation duration and oval counts. They are geometric evidence, not semantic confidence. Existing raw OCR/method/error evidence remains.

## Performance constraints

Same local ML Kit, one native OCR task and two translation permits. No cloud upload, larger model, automatic OCR retry or extra translation calls. Surface analysis is capped at 1,800 probes per region, at most 24 regions per frame (further regions use source rectangles); no full-image copy is needed for it. Text-fit work is bounded; pathological text over 4,000 characters opens details directly. Detection is new bounded work, so **unchanged latency cannot be promised without a phone A/B**. Reject or revise if measured first/final render p50/p95 materially regress.

## Validation and limits

Pure regressions: synthetic closed oval, unbounded white page (must not invent bubble), dark uniform background, complex background, pixel-probe ceiling, complete source containment and numeric negative controls. Existing OCR/translation/name/context regressions remain.

Android 14 tests: full alpha coverage at every rectangular mask pixel; short text grows beyond old 18sp ceiling; long text explicitly offers details; bounded layout passes and click access. Real historical OCR crop still runs through ML Kit. New real bubble crop test verifies alpha coverage of every source-box pixel and writes source/replacement PNGs to the CI artifact. Its Arabic reference is **human-authored to isolate rendering**, not improved model output and not a physical-device screenshot. Original-circle transparent-corner control remains in the suite.

OPEN: physical WindowManager positioning/touch and overlays interacting with dock controls, pathological overlapping source boxes, unseen/missed OCR text, general bubble segmentation/orientation, arbitrary background reconstruction, source-language/browser-content discrimination, low-confidence image recovery, scene/speaker understanding, native-level Arabic, and a held-out bilingual semantic evaluation. The earlier comprehensive audit remains applicable. No blanket no-crash/no-leak/perfect-mask or speed-improvement claim is made.

This is an engineering checkpoint, **not completion of the requested comprehensive semantic overhaul**. No new phrase table or purported prompt optimization has been added. ML Kit has no generative scene prompt; offline and current-speed constraints remain in force. Upgrade with the same durable signer; do not uninstall/clear data. PR9 remains open.

## Executed verification

At `0cdf62a`, JVM/compile/debug CI passed: https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44707942911 . Android 14 instrumentation (including real crop and per-pixel opacity assertions) passed: https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44707942918 . Rendering evidence is in that run's `android-quality-results` artifact. Downloading the artifact into the agent sandbox failed at the external artifact-storage host, so generated PNGs were not manually visually approved here; source crop was inspected and assertions executed. Test output is not a physical phone A/B or translation-model quality comparison.
