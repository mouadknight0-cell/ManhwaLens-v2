# v1.1.13: translation input integrity and circular presentation

## Evidence and scope

User report: Xiaomi 23106RN0DA Android 14, v1.1.12 (217279890), nine screenshots plus partial timing/sample/error exports. Captured failures are handled `TranslationFailure` events, not evidence of an application crash. Exports contain repeated records and truncation: do not aggregate them as independent benchmarks. Capture INACTIVE at export does not describe the capture-time state (bug reports say active).

## Changes, with limits

1. **Raw/repaired source integrity.** `progressiveTranslation` overwrote the normalized `original` with raw OCR on delivery. Preserve the translated input while retaining each instance's raw region/coordinates. History now persists raw OCR and method with backward-compatible defaults. Sample method distinguishes lexicon, ML Kit and input validation; semantic quality stays unverified.
2. **Input quality instead of mislabelled model failures.** `oo` and single-letter fragments now retain their source with OCR-UNCERTAIN. Incomplete text reaching the screen content bottom is OCR-CLIPPED and asks for a complete frame instead of translating corrupted fragments. These remain error-coded, visible and recorded, not counted as successful translations. A completed sentence at the boundary is retained. This is not a general detector of noise or browser chrome; EI!, Aalal and other unknown strings may still fail model validation. Browser toolbars can clip content above system insets and require further viewport detection.
3. **Conservative OCR repair.** Digit zero becomes O only in a small common-word allowlist, addressing GO0D without rewriting numbers or arbitrary names. No guessed THOIIGUT/AROUT corrections. Input repair changes are auditable.
4. **Translation quality.** Add a transparent, reviewed, exact-whole-utterance EN→AR lexicon for common idioms/interjections (not substring substitution). Examples: THAT'S A RELIEF → هذا مطمئن; WATCH OUT → احترس; WHERE... → أين... . Preserve punctuation. AM I...? gets أنا...؟ only following WHERE in the same prepared frame. Name/glossary hits bypass this lexicon. This avoids some known literal translations and inference for matching phrases, but is a bounded rule-based enhancement, NOT a new context-aware model. Unmatched text still uses ML Kit; tense, character intent, broad slang, gender and cross-page context remain unresolved. No network service, API key, paid provider or silent data upload was added.
5. **Line boundaries.** Sample gap interiors rather than including bounding-box rows that can contain ink. Dark gutters still veto joins. This can reduce false splits but does not prove all split balloons are fixed.
6. **Circular UI.** Equal width/height, oval background and outline clipping. A 0.68-diameter inner square is fully inscribed. Measure 18→12sp respecting system font scale, then scroll long content without ellipsis. Source coverage checks actual circle corner distances, not just rectangular bounds. Error circles are bounded independently of OCR area. Collision fallback is a bounded opaque circular reader instead of the half-screen translucent rectangle. Long-press dismisses; drag within the circle scrolls; new cards fade in over 120ms. Preserve reader scroll position across progressive refreshes. This may still obscure artwork and needs device review, especially dense panels/large fonts. The floating action control is unchanged.

## Verification design

- Regression cases for raw/repaired deduplication and history round-trip.
- Input classifications: clipped text vs complete question, uncertain oo/I vs valid NO!.
- Exact idiom and punctuation tests; negative controls prevent substring substitutions.
- Circle coverage/inscribed-content math at multiple pixel diameters and constrained screen sizes.
- Real phone crop `app/src/test/resources/phone-report/conference-text.png`, derived from user screenshot 20-46-24-941, crop (360,260)-(645,445), grayscale. Test real light-gap/ink sampling on GitHub JVM. This tests pixels, NOT Android ML Kit inference or full rendering.
- Existing unit suite and signing recovery tests run on GitHub before publication. No local Android build.

## Before/after and honest outstanding work

The report's repeated GO0D source previously went unchanged to ML Kit; it is now GOOD, with raw source retained. Exact lexicon fixtures specify natural Arabic instead of the report's literal outputs. Circular geometry replaces rectangular cards structurally. Runtime quality/latency improvements are **not measured**: no same-image Android ML Kit A/B benchmark, physical-device touch/animation/accessibility test, or real-image end-to-end OCR translation test has been executed by the agent. JVM tests cannot establish those results.

A genuinely stronger contextual engine remains necessary for the broader requested accuracy. The existing on-device ML Kit API does not accept structured conversation context. Do not claim all reported issues resolved or fabricate a quality score. Next device checks: screenshot scenes with errors, GO0D source/output, SINCE and I SENT MY split lines, short and long circular content, font scaling, rotation, live scrolling and safe update preserving history/settings.

Signing: reuse the v1.1.10/v1.1.12 vault and certificate; no initialization, deletion or data migration.

## Executed GitHub evidence

- CI compile, JVM unit tests, signing recovery and debug assembly: PASS, run [44605008627](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44605008627), application source at 234bac7.
- Android 14 emulator instrumentation: PASS, run [44605008577](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44605008577). Bundled OCR recognized POISON and SECT in the real supplied crop. Circular view tests drew to a bitmap at diameters 240/420/600, checked transparent corners/opaque center, minimum font size, overflowing Arabic content height and long-click handler.
- The emulator test checks a laid-out view, not an attached MediaProjection overlay. Animations were disabled in the emulator workflow. Actual touch scrolling, overlay placement, animation smoothness, Xiaomi behavior and full EN→AR model A/B quality/latency benchmarks are NOT verified.
- Initial test attempts exposed a test-source compatibility issue with desktop image APIs and an overflow fixture too short to guarantee overflow at every size. Replaced desktop decoding with a threshold raster derived from the same crop and made the long-text fixture unambiguously long; production circle/translation code was not loosened to pass those tests.
