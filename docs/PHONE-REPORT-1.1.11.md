# v1.1.10 phone report → v1.1.12 bounded fixes

v1.1.11 was an unpublished candidate: 109/110 JVM tests passed; the synthetic sparse-pixel fixture aliased with the sample step. v1.1.12 corrects the fixture to a defined 10%-width dark strip, without weakening the production threshold.

Device: Xiaomi 23106RN0DA, Android 14, v1.1.10 (217277234).
Evidence: user-supplied screenshots, translation samples and timing excerpts. The pasted export has gaps (including run 20→52), duplicated text/JSON records and truncated marks. No aggregate success percentage, cancellation rate or performance improvement is inferred from it. Capture INACTIVE / Live OFF describes export time only.

## Findings and changes

- Reported line boxes overlap by one pixel: WHEN DID THEY GET / BEHIND ME and ITS NOT SOMETHING… / THE UN NAM REGION. `canFollow` previously rejected every negative gap. Permit at most 20% of the shorter line height, require meaningful vertical advancement and retain alignment/script/visual checks. Same-row boxes still cannot join.
- Cross-block sampling previously rejected touching boxes and demanded 94% light on every sampled row. Sample every row of the boundary, tolerate sparse glyph edges (80% light), and still veto a dark outline/gutter row. Pure pixel predicate tests cover touching, overlap, noise, borders and bounds. This is a heuristic, not balloon segmentation; phone validation is still necessary.
- Error windows previously inherited `region.height` even when OCR recognized `(e` over an illustration. Failure cards now use a bounded width and measured message height, retaining the error and source in history/samples. No diagnostic failures are hidden. Noise recognition itself is not claimed solved.
- Collision placement that no longer covers the original source now explicitly switches to the existing reading panel rather than silently leaving English exposed. This is a fallback, not perfect balloon-shaped text replacement.
- Additional own-dashboard signature requires all of setup-needed, capture label, settings and see-all. Generic Ready/Settings alone never triggers it. This retains the existing source-invalidation policy; it does not prove the report captured the app rather than a thumbnail.
- Empty OCR/prepared content returns `empty` before model verification instead of a zero-block `ok` journey that warms the model with no translation.
- Conservative English OCR input repair only: pipe before AM/WAS/HAVE/WILL/WOULD and IAM before an -ing word. Raw region text is retained and optional samples include `ocr_source` plus `semantic_quality=unverified`. No phrase-specific translation substitutions; no guessed Y→YOU, ITS apostrophe, or UN NAM/NOM renaming.

## Semantic quality remains unresolved

The supplied complete source “WE BETTER END THIS SOONER THAN LATER.” already demonstrates that successful inference/Arabic script validation is not semantic correctness. Literal idioms, tense/pronouns, and proper-place-name ambiguity remain limitations of the current on-device ML Kit engine. Geometry changes cannot fix these. A stronger translation backend/model requires separate integration and explicit privacy/offline/performance decisions; no remote service or key was silently introduced.

## Measurement interpretation

T2 is the capture request; process-to-result includes idle and is not latency. T10/T12 remain UI-post proxies, not hardware presentation. Missing T12 remains unconfirmed; cancellations are not automatically OCR/model failures. Model verification is not proof of downloading. No real-device speed or quality gain is claimed.

## Validation

GitHub Actions runs unit tests (including new phone-case regression tests and durable signing recovery) before release compilation. See release/PR run links for actual outcome; this document is not a test-pass assertion. No local Android builds or assistant physical-device tests.

Follow-up device checks: one card for overlapping-line sentences, no giant error rectangle for `(e`, no displaced overlay presented as covering its English source, genuine dashboard capture rejection, short dialogue preserved, update from v1.1.10 without uninstall and with history/settings intact. v1.1.11 reuses the v1.1.10 encrypted signer; do not initialize a key or remove app data.
