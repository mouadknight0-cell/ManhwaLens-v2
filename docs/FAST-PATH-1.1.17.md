# v1.1.17 — offline fast-path quality guards

Constraints: no remote translation, no screen-text upload, no heavy model, no automatic OCR retry or additional model passes. Keep ML Kit, native concurrency limits and its model-output cache.

## Changes

- v1.1.16 correctly separated region-specific OCR validation/context but gave every region distinct work. Two identical model inputs could miss the cache concurrently and both invoke ML Kit. Build an immutable frame plan once; reuse work only for equal raw text, OCR issue, preceding valid non-SFX source, and sound-effect preservation mode. Glossary/model are constant within this frame. Copy each result back to its original region and preserve source ordering.
- Precompute issues and preceding-source inputs once. Replace repeated `indexOfFirst` scans and repeated previous-region validation inside workers with a region lookup. This is a structural work reduction, not a measured device speed claim.
- Keep clipped/complete text, different preceding dialogue and preserved SFX distinct. Never share across frames, models or glossaries. Existing two-worker bounds and cancellation semantics remain.
- Reconstruct only alphabetic split words. Preserve single-letter hesitation (`I- / I`, `W- / WHAT`), standalone dash interruption (`WAIT - / NO!`), and numeric dashes. Existing `trans- / lation` and `TER- / RIBLE` behavior is retained. Multi-letter interrupted speech vs true split words remains ambiguous; this does not solve all dehyphenation.
- Correct diagnostic wording: preserved SFX and input failures are not logged as completed ML translation. Include processing method and technical status, not a semantic-success assertion.

## Validation and release limits

JVM regressions exercise work reuse, original coordinates, clipping/context/SFX separation, invalid previous OCR, interrupted speech and split words. In a deterministic 20-region equivalent-input fixture, the callback is expected twice (first line has no prior context, the remaining 19 share one context), rather than 20 times. This is a mock work-count assertion, NOT an OCR/model latency benchmark.

Existing Android 14 real-image OCR, circular view, complete-report and glossary tests also run on GitHub. No new OCR preprocessing, inference retry, remote request or larger model is introduced.

Physical Xiaomi before/after performance is **not measured** here. Adoption gate on device: same pages and settings, separate cold/warm/cache paths, compare T2→first UI and T2→final UI p50/p95 with cancellation counted separately. T10/T12 remain UI-post proxies, not exact rendered-frame times. Do not claim unchanged speed or improved Arabic semantic accuracy solely from green tests. General context, idioms and model pronoun/tense errors remain open.

Update using the existing signer; do not uninstall/clear data. PR9 stays open.

Executed at `1b817b8`: GitHub CI (including JVM regressions) passed https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44701309960 ; Android 14 instrumentation passed https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44701309958 . No phone latency measurement was performed.
