# Independent translation / rendering audit (post v1.1.19)

## Scope and evidence

Audited capture/lifetime/downscaling, ML Kit OCR/line filtering/grouping, frame-local context/deduplication, offline model readiness/translation/name shielding/cache/validation, native Arabic typography, mask detection/placement and native layout/scrolling. No new screenshots requested. Used repository tests, the previous real-image test assets, synthetic edge cases and the supplied report `ManhwaLens-full-report-1795169701258.txt` (not checked into Git because it contains captured text).

Report: environment v1.1.19, Xiaomi 23106RN0DA / Android 14; 100 retained histories / 341 blocks (not necessarily all same version), 26 samples explicitly labelled 1.1.19 (23 no technical error, 3 TR-INVALID-OUTPUT), 22 benchmark records. No-error is NOT semantic success.

## Reproduced defects and changes

| Defect / root cause | Change and scope | Regression |
|---|---|---|
| Own floating status entered translation: report contains `Capture: äbas ... READING`. Overlay rectangles masked motion samples only, not OCR. | Capture retains an immutable copy of own-window bounds with each image; rejects intersecting OCR **lines before grouping**, accounting for OCR downscale. Does not delete guessed English phrases or rerun OCR. | Pure scaled intersection/edge cases; actual OCR fixture with reject-all callback proves exclusion is wired before assembly. Window composition timing on phone remains to test. |
| Clipped dark/grey surface switched to white near screen insets; wholly offscreen text was clamped into an invented edge strip. | `SurfacePlacement.visible` intersects source-aligned bounds, preserves sampled color, rejects empty intersections. Cropped oval falls back to a source rectangle rather than a smaller invented ellipse. | Dark cropped oval, empty screen, fully offscreen region. |
| Two expanded ovals could overlap without either intersecting the other source box. | Second placement pass compares candidate surfaces, reverts colliding ovals to original-source masks; no displacement that exposes source lettering. | Disjoint source boxes with overlapping expanded ovals; single oval preserved. Original overlapping OCR source rectangles remain unresolved. |
| StaticLayout emergency wrapping can split an Arabic word despite disabled hyphenation. Height/width alone reported a successful fit. | Check native line boundaries; fit search rejects intra-word boundaries, and vertical-scroll fallback cannot pretend to solve a word wider than the viewport. Opens full details in that case. | Arabic letters/diacritics, whitespace/hyphen boundaries; native very-long-word layout plus tiny→large resize restoration. |
| Even numeric-only, preserved SFX-only or OCR-rejected frames required model availability and could abort before showing otherwise valid content/errors. | Build work plan first; only request model readiness when some work actually needs translation. | Mixed numeric/SFX/uncertain, empty and actual-dialogue plans. |
| OCR repair preceded confirmed-name protection; glossary name `B00K` was changed to `BOOK`, preventing shielding. | Protect confirmed glossary entries before OCR repair; collision-safe restoration; raw OCR retained. Shield failure is explicit. | Confirmed name vs ordinary OCR, existing source sentinel number. |
| `Jin!` / multiple protected names were rejected for lacking Arabic, because exception allowed only an exact single name string. | Pure output validator permits protected-name-only utterances with punctuation, verifies both masked source and output, keeps lost/duplicated sentinel checks. | Name-only punctuation, multiple names, missing/duplicate sentinels, extra text/numbers, untranslated ordinary dialogue. |
| A response containing Arabic plus replacement glyph U+FFFD or NUL passed Arabic-letter validation. | Reject malformed replacement/control output (ordinary newline/tab retained) before caching. | Empty, whitespace, Latin-only, malformed, valid multiline and deliberate semantically wrong Arabic to demonstrate the limit. |

## Pipeline review: retained architecture and remaining weaknesses

### Capture and preprocessing
- One retained Image, superseding capture job cancellation/join and finally-recycled frame remain. New wrapper closes the same owned Image; no extra bitmap copy introduced.
- Existing max-dimension 1600 downscale and 10px minimum OCR line height can lose tiny glyphs. No blind sharpening, extra OCR inference or rotation retry introduced under the speed constraint.
- Window bounds are snapshotted at frame delivery, not synchronised with SurfaceFlinger's exact composition timestamp. Floating-window move/remove timing and MediaProjection app-only selection need phone verification. Covered text cannot be recovered from that frame; it is excluded rather than guessed.

### OCR, segmentation and context
- Actual ML Kit line confidence is retained; not treated as a calibrated probability. No arbitrary confidence cutoff that discards readable low-confidence dialogue was added.
- Nearby aligned lines use same-block ownership / white or tonal corridor evidence. Same-block ownership can still merge separate speakers; grey/organic segmentation is heuristic. Top→left order is not panel-aware; vertical/rotated text not supported reliably.
- FrameTranslationPlan keys include previous eligible text, validation state and SFX preservation, preventing unsafe text-only deduplication. Translation cache keys include source and glossary.
- `previous` only affects the narrow reviewed lexicon; it is **not** context conditioning of ML Kit. No multi-page speaker/gender/relationship memory exists.

### Translation quality and response handling
- Engine is local EN→AR ML Kit, with no prompts, temperature or external translation API to optimise. No screen text sent to remote services, no engine swap, no added inference.
- Report demonstrates genuine semantic failures with clean OCR: `state` → «دولة», `Palace Master` → «قصر ماجستير», `SUN MOON DEMON CULT LEADER` → «صن مون شيطان» (missing concepts), and mistranslated responsibility/compromise. These are **not fixed by typography or structural checks**. No screenshot/dialogue-specific replacement table added.
- Idioms, sarcasm, gender, tense, omissions, extra meaning and general fluency are not verified. Arabic script presence is explicitly not proof of correct language/meaning; one new test intentionally accepts a semantically wrong Arabic word to enforce this distinction.
- Native task permit release on completion, timeout/cancellation propagation, original source retention, explicit errors and bounded LRU remain. No silent retry or truncation introduced. An end-to-end injected delayed-model test seam is still missing; existing coroutine orchestration tests do not establish native SDK timing behaviour.
- Glossary remains confirmed names, not a series-scoped Arabic terminology/context database. This patch prevents damaging confirmed names during repair but does not infer pronouns or translate titles reliably.

### Typography / layout / masking
- Retain bundled licensed Noto Sans Arabic, native shaping/StaticLayout, display-only LTR isolation, no string reversal; short centered / longer natural RTL, 12–30sp bounded fit and full-details reader.
- New boundary checks use actual native line starts, not estimated character counts. Ordinary wrapping is preferred to shrinking. Oversized single words cannot fit finite space at a readable minimum; details is explicit fallback, not a claim of simultaneous full visibility.
- Oval detection is conservative, axis-aligned, max 1800 pixel probes, up to 24 candidates per frame. No trained balloon segmentation, arbitrary polygon/rotation recovery or textured background reconstruction. Rectangular source masks can still obscure artwork on complex backgrounds.
- Surface collision handling is deliberately conservative (rectangle bounds, possibly rejecting safe ovals). It does not solve source boxes already overlapping; moving masks would expose original text.
- New fit boundary checks apply to replacement overlay. Full details uses native TextView scrolling and can still emergency-wrap extraordinary unbreakable tokens; no promise of every possible word fitting on a phone screen.

## Tests / measurement

Added `IndependentAuditTest` (6 methods), `TranslationOutputTest` (4 methods), and 2 Android instrumentation methods. Existing grey-panel and conference OCR, font/bidi/scale/scroll tests remain. GitHub is the build/test environment; exact results recorded below after completion. No local build or unexecuted test is claimed.

Historical phone baseline only, extracted from the supplied report. Median and nearest-rank p95 elapsed milliseconds from T2; excludes absent marks (and inferred T12). Different columns have different cohorts; not a controlled A/B benchmark:

| Milestone | n | median ms | p95 ms |
|---|---:|---:|---:|
| OCR complete T6 | 14 | 287.8 | 704.8 |
| First translation T9 | 12 | 417.8 | 1241.9 |
| First UI mark T10 | 11 | 574.0 | 1358.0 |
| Final UI mark T12, non-inferred | 10 | 627.9 | 1395.6 |

22 benchmark outcomes: 10 ok, 9 cancelled, 2 partial, 1 empty. T10/T12 are UI scheduling/frame proxies, not proof that every line was visible or every translation correct. Records lack per-record app version; do not label this a new-version speed benchmark. There is no measured speed-parity result for these changes, nor a battery/physical Xiaomi touch/upgrade test.

## Release gate

PR9 remains open; no merge is authorised by this audit request. Signing vault/certificate must remain unchanged. Publish only after GitHub build and instrumentation checks complete; report unresolved semantic and physical-device gaps explicitly.

## Executed checks (app/test source `6107f06`)

- GitHub CI [44720117374](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44720117374): compilation, JVM tests and debug APK **passed**.
- Android 14 [44720117448](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44720117448): instrumentation **passed**, including existing actual OCR/grey crop, Arabic bidi/font-scale, scroll tests and both new instrumentation methods.
- Attempted download of `android-quality-results` returned EOF at GitHub's Azure artifact host. Thus generated PNGs were **not manually visually inspected** in this audit; passing assertions are the evidence, not a claimed visual review of new rendering.
- `git diff --check` passed. Older superseded work-in-progress jobs were cancelled, not counted as tests passing. Release metadata changes after `6107f06` do not change app/test source.
