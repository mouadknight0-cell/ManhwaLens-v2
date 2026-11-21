# Changelog

## 1.1.31
- Local EN→AR phrase packs and Room corrections (global/per-series), import/export and correction from history. Matching precedes on-device MT; reading never triggers model installation.
- Icon-only adjacent dock, local contrast OCR, bounded frame processing, scroll correlation and source validation. Device acceptance pending.

## 1.1.30
- Conditional colour-crop OCR recovery, ranked candidate passes and local text repair. Murim readings, interjection handling, bounded translation timeout and Close control. Physical Redmi metrics pending.

## 1.1.29 — Xiaomi reader update
- Three-action RTL dock; hiding the icon no longer ends projection. Restore via notification or app.
- Bounded sampled bubble contours, source-centred fallback, stricter MT output checks and OCR repair.
- Bound capture settling, add connected-clause and reviewed dialogue handling.
- Confirmed diagnostic reset and recent-record UTF-8 report budgets. Device acceptance not yet measured.


All notable changes to ManhwaLens are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.1.28] - 2026-11-20

- Live-only translation, incremental bubble commits, matched page-state reuse, motion confirmation and post-cycle backpressure.
- Measured fit fallbacks, bounded full-surface OCR retries, 18k offline lexical scoring, and script-share validation.
- Comic-surface filtering, indexed reviewed dialogue and editable post-translation corrections.
- Added session outcome/latency scorecards to the full report. No new sensitive permission or model.

## [1.1.27] - 2026-11-20

- Resize undersized OCR retry crops for ML Kit, retain per-block failures, process surface evidence beyond region 24, and add opt-in geometry/logcat diagnostics.
- Keep the capture menu open through page completion. Add pause/resume, automatic/manual capture, translate-now, settings and outside dismissal. Hide capture windows while acquiring a fresh frame.
- Editable Arabic readings for character names/terms, punctuation-preserving reviewed whole utterances and short dialogue. Preserve GYU as SFX.
- Bounded flat-ring growth with original-box fallback and larger native auto-fit range. No new translator or on-page reader.

## [1.1.23] - 2026-11-19

- Delete secondary translation launcher, manual reader, controls, thresholds and overflow branches; keep main capture control.
- Render full translation immediately, expanding into measured rectangles excluding the main control, existing translations and failed source regions.
- Fix false text-fit rejection caused by wrapped trailing whitespace; maintain 12sp minimum and preserve originals at genuine capacity limits.
- Add native direct-render, edge/font-scale and explicit physical-capacity tests. Android emulator and CI passed; Xiaomi/latency/upgrade checks remain unexecuted.

## [1.1.22] - 2026-11-19

- Add bounded unique digit-confusion dictionary repair and contraction cleanup behind confirmed-name shielding; flag unresolved noise/low confidence rather than transliterating corrupt input. Avoid no-op repair work and precompile patterns.
- Add reviewed natural sound/inner-voice whole-utterance phrases, normalize display ellipses, and test short/name-only validator cases. No general semantic or speaker-context claim.
- Retry native execution failure once after 120ms; do not retry validation, timeout or cancellation. Include confidence state in per-frame work identity.
- Isolate per-item preflight failures and show explicit warning states rather than failure strings as dialogue. Keep successful non-overlapping replacements.
- Replace manual circular reader with measured, capped translation-only reader and internal source/close controls. Hide floating icon at 18% screen area and restore on close; maximum reader area 30%.
- Golden/native reader tests and cleanup microbenchmark added; full-device end-to-end latency parity remains unverified. See PROJECT_STATE.md and docs/QUALITY-READER-1.1.22.md.

## [1.1.21] - 2026-11-19

- Fail closed before masking: require final/current request, structurally plausible output, acceptable OCR and reliable surface evidence, complete native layout and successful offscreen raster preparation.
- Remove opaque ellipsis and scrolling-slice replacements. If anything cannot safely replace source, preserve original and offer a manually opened reader outside detected text.
- Draw complete prepared pixels as one unit, cap bitmap staging, roll back failed window attachment and invalidate cancelled/stale request identities. Failure no longer automatically opens a large error panel.
- Reject long-dialogue single-character/severely incomplete outputs, placeholders, malformed structures and unprotected Latin remnants; version the model cache validation namespace.
- Add pixel-preservation/fault-injection/font-scale regressions and JVM response/state/rollback tests. General ML Kit semantics, organic reconstruction and physical-device latency remain unverified; complete-frame gating can increase first-overlay latency. See docs/SAFE-REPLACEMENT-1.1.21.md.

## [1.1.20] - 2026-11-19

- Snapshot own floating-window occlusions with each captured image and reject intersecting OCR lines before grouping (scaled coordinates), instead of translating the app's own status controls.
- Preserve adaptive mask color at clipped screen edges; skip fully offscreen regions; reject collisions between expanded oval surfaces without moving source masks.
- Reject native emergency intra-word wrapping in source overlays; use explicit full-details fallback when no readable intact word fits. Recalculate correctly after resize.
- Protect confirmed glossary names before OCR repair. Validate malformed responses and name-token integrity while accepting legitimate name-only dialogue with punctuation.
- Skip model readiness when all work is numeric preservation, preserved SFX or OCR input errors; no new inference/cloud/heavier model.
- Add 10 JVM and 2 Android regression methods. See docs/INDEPENDENT-QUALITY-AUDIT.md for root causes, evidence and unresolved semantic/physical-device limits.

## [1.1.19] - 2026-11-19

- Bundle licensed Noto Sans Arabic across native overlays/readers and Compose typography, with cached native loading and safe font fallback.
- Use explicit Arabic RTL base and display-only Latin/name/number isolation; no string reversal or changes to stored/copied translations. Long dialogue uses natural alignment, short text stays centered.
- Recognize consistent light-grey inter-line corridors after white-only evidence fails, retaining geometry/separator/probe-budget guards. Exercise the supplied Nekoyomi crop through actual OCR and grouping.
- Keep readable overflowing paragraphs visible with inline scrolling/tap details rather than replacing all content with an ellipsis; tiny regions still need details. Improve sampling outside outlined glyphs for grey backgrounds.
- Include Arabic/Latin glyph, bidi order, font-scale, source preservation and grey-panel regressions. Semantic mistranslations in the screenshots are not claimed repaired by typography; see docs/ARABIC-RTL-1.1.19.md.

## [1.1.18] - 2026-11-19

- Replace default displaced circles with opaque source-aligned text masks; no fading, decorative shadow or collision-driven relocation.
- Use an oval only when bounded pixel probes support a closed light elliptical interior and complete source coverage; otherwise use an exact padded rectangle with uniform perimeter color or neutral white.
- Fit Arabic using native bidi/shaping and bounded 12–30sp search; if minimum readable text cannot fit, show an explicit details marker rather than truncated dialogue. Preserve font padding and dark-background contrast.
- Preserve numeric/currency/time expressions without misclassifying them as uncertain alphabetic OCR.
- Export rendering geometry hints and surface-preparation duration. Add real historical bubble-crop/opaque-mask tests and CI rendering evidence; reference Arabic is human-authored, not model output.
- Same offline ML Kit and concurrency. Performance parity and general semantic quality are not established; see docs/OVERLAY-QUALITY-1.1.18.md.

## [1.1.17] - 2026-11-19

- Keep the local ML Kit fast path, native concurrency and cache. No remote text upload, heavy model or automatic extra OCR/model pass.
- Precompute frame work and safely deduplicate only equal text/validation/context/SFX-mode inputs; preserve individual coordinates and order. Replace repeated region scans.
- Preserve single-letter hesitations, standalone interruption dashes and numeric dashes during line reconstruction.
- Log processing method/status rather than claiming every preserved/error block is an ML translation.
- Add equivalence/negative regressions; mock work-count reductions are not physical latency results. See docs/FAST-PATH-1.1.17.md.

## [1.1.16] - 2026-11-19

- Audit capture/OCR/reconstruction/context/translation/names/cache/display/reporting; document unresolved model-level and device-validation gates in docs/TRANSLATION-QUALITY-AUDIT.md.
- Stop sharing position-dependent clipping/context results across equal text at different locations; preserve exact model-cache reuse.
- Allocate collision-free name sentinels and validate occurrence counts; prevent glossary matching across punctuation and support hyphen variants.
- Retain auto-learned names as suggestions, requiring explicit Settings confirmation before shielding. No glossary deletion or data migration.
- Preserve decimal/time/thousands punctuation and emphatic runs; distinguish real Arabic letters from digits/punctuation in output validation.
- Capture actual OCR confidence (unknown stays unknown), use per-axis coordinate scaling, retain SFX as explicitly untranslated records, and refresh open readers on progressive results.
- Add regression tests and a private local version-isolated report review tool. General scene understanding, idioms and native-level Arabic remain unresolved; no semantic accuracy claim.

## [1.1.15] - 2026-11-19

- Prepare complete TXT before opening the document picker; preserve its path across recreation and verify saved byte count/hash before success. Physical provider behavior remains unverified.
- Repair allowlisted D0CTOR / TO0 OCR confusions without changing numeric tokens or guessing YCu.
- Reviewed first-person help utterances preserve speaker identity; negative regression tests prevent substring/negation rewrites. Not a general semantic-model upgrade.
- See docs/REPORT-1.1.15.md for phone evidence and limitations.

## [1.1.14] - 2026-11-19

- Add explicit opt-in unified TXT creation: save through Android document picker or share one UTF-8 attachment containing retained translations/history, benchmarks, errors, logs and bug reports. Automatic diagnostic bundles remain content-free.
- Stop automatic giant circular reader fallback. Use smaller per-region circles, compact error buttons, and an explicit reader launcher on crowding; tap for details, long-press to dismiss.
- Avoid labelling standalone numbers as OCR failures. Detect evidenced dark browser navigation bands only with a visible address; skip multi-marker loading pages and record exclusions.
- Flag near-bottom truncated text using glyph-height tolerance rather than only three pixels. Preserve error/history evidence.
- Semantic ML Kit limitations and physical-device performance remain unresolved; see docs/REPORT-1.1.14.md.

## [1.1.13] - 2026-11-19

- True circular translation/error surfaces and bounded circular reader; inscribed text area, 12–18sp fitting, internal scrolling, long-press dismissal, and short entrance animation.
- Correct raw/repaired source corruption in progressive deduplication; preserve OCR source and translation method in history and samples.
- Restricted digit-confusion repair (GO0D → GOOD), explicit clipped/uncertain OCR states, no inference on known incomplete inputs, no stopping live solely for these input states.
- Reviewed whole-utterance dialogue lexicon with punctuation preservation and narrow same-frame WHERE / AM I context. This is not a replacement for ML Kit or general cross-panel context understanding.
- Inspect the interior of inter-line gaps rather than glyph edge rows. New tests include a real cropped phone-image pixel fixture, geometry, history, input quality and dialogue cases.
- General semantic quality, arbitrary OCR corruption, all browser chrome detection and on-device rendering/performance remain unverified. See docs/QUALITY-1.1.13.md.

## [1.1.12] - 2026-11-19

- Publish the phone-report fixes below after correcting a synthetic pixel fixture: the original every-tenth-pixel pattern aliased with the sampler and exceeded the intended noise allowance. v1.1.11 failed this regression test and was not published as an APK.

## [1.1.11] - 2026-11-19 (unpublished candidate)

- Allow small OCR line-box overlaps while preserving geometry and visual separation checks; add phone-case regressions and pixel-corridor tests.
- Bound failure-card size independently of noisy OCR bounds; use reading fallback when collision placement exposes the source text.
- Recognize the additional own-dashboard marker combination; report empty content honestly without unnecessary model initialization.
- Apply narrow English OCR input repairs and retain raw source / unverified semantic-quality metadata in optional samples.
- Reuse the durable v1.1.10 signer. No settings/history migration or deletion.
- Arabic semantic quality and physical balloon fitting are **not** claimed solved. See docs/PHONE-REPORT-1.1.11.md for evidence, limitations and device checks.

## [1.1.0] - 2026-11-19

### Fixed
- **Capture session rebuilt.** Root cause of "capture session does not work":
  every capture created a fresh `ImageReader` + `VirtualDisplay` and raced the
  GPU for a single frame — on static screens the frame often never arrived, so
  every capture ended in a timeout. The pipeline now lives for the whole
  session, the projection callback is registered before `createVirtualDisplay`
  (Android 14 requirement), frames are acquired with a 1.5s fresh-frame wait
  and a newest-frame fallback, and teardown releases VD → ImageReader →
  projection in the correct order. Rotation rebuilds the pipeline once.
- User-initiated stop no longer surfaces a bogus "session expired" error.
- `startForeground` failure is caught and reported (`CAP-2005`).

### Added
- English → Arabic pipeline, locked scope: on-device ML Kit translate with
  sentence reconstruction, dialogue/narration/SFX classification, name shield
  (CN/KR romanized names are never translated), persistent auto-learned name
  glossary (visible/removable in Settings).
- Structured diagnostics (JSONL, rotated; per-journey trace id chaining all
  stages; consistent error codes; levels incl. FATAL; content-free by design).
- T0–T12 benchmark system with persisted records (200 cap), avg/P50/P90/P95/
  P99 (≥100 samples), success/timeout rates, cold/warm split, optional T12
  auto-completion; exportable from Settings → Diagnostics.
- Progressive per-block translation reveal (`Partial` state) in the result
  sheet and the floating panel; Arabic RTL rendering in both.
- Diagnostics export via FileProvider (logs + benchmarks).
- JVM unit tests: sentence reconstructor, region classifier, name detector,
  name shield, benchmark math, log-event round-trip. CI annotates unit-test
  failures (previously invisible).
- docs/DIAGNOSTICS.md — benchmark methodology, export, on-device verification
  protocol and failure-injection checklist.

### Changed
- Language selection in Settings replaced by a locked EN → العربية pair
  (one-time migration of stored ko/zh/ja preferences).
- Settings gained learned-names management and diagnostics/benchmark export.

## [1.0.0] - 2026-11-19

### Added
- Native Android app (Kotlin + Jetpack Compose), package id `com.manhwalens.app`.
- Screen capture via MediaProjection with a held capture session (foreground service, mediaProjection type).
- Floating draggable lens bubble rendered over other apps (SYSTEM_ALERT_WINDOW, specialUse foreground service) with a bottom translation panel.
- On-device OCR with ML Kit for Korean, Japanese, Chinese and Latin scripts; lines merged into speech-bubble regions.
- On-device translation to 16 targets (Arabic default) via ML Kit Translate; models download once, then offline.
- Tap bubble → auto-capture → OCR → translate pipeline with live working-state.
- Translation history stored on-device (JSON index + thumbnails), with delete/clear-all.
- Privacy-first design: no server round-trips; captures never leave the phone.
- Arabic and English UI with RTL support.
- Unit tests for the recognition/translation pipeline (JVM).
- GitHub Actions: CI (test + debug APK) and tag-triggered release workflow publishing signed APKs.

## [1.1.1] - 2026-11-19

- Start stable release signing continuity with a pinned certificate; no ephemeral debug-signed public releases.
- GitHub builds/tests release APKs; offline signing publishes only verified signed outputs.
- Capture full-display consent on Android 14, persistent-display resizing, cancellation fixes.
- Bubble direction fix, edge snapping and Start/Stop controls.
- Bounded progressive region translation; Copy Benchmark and local issue reports.
- Device validation and old-certificate migration remain unverified; see docs/DEVICE-FOLLOWUP-1.1.1.md.

## [1.1.2] - 2026-11-19

- Connected floating Start/Stop/Disable action dock; stale result and consent-generation guards.
- Separate model verification timings, request-based latency and journey-bound render marks.
- Verified language model download UI, automatic bounded bug reports and local translation samples.
- Preserve OCR block boundaries and translate short uppercase dialogue rather than discarding it as SFX.
- Physical-device verification remains pending; see docs/DEVICE-FOLLOWUP-1.1.2.md.

## [1.1.3] - 2026-11-19

- Service-owned live motion/stability gate, one retained latest frame, stale-work cancellation.
- Wrong-source capture invalidation and observable session/live status.
- Native OCR/translation concurrency guards; explicit block failures and stricter name/cache handling.
- Remove fabricated final-visible timestamps and process-lifetime error durations.
- Device verification and contextual model quality remain outstanding (docs/LIVE-1.1.3.md).

## [1.1.4] - 2026-11-19

- Explicit capture session phases and labeled floating Start/Stop controls.
- Collapsible settings with everyday controls separated from diagnostics/advanced tools.
- Independent confirmed Copy/Reset/View/Share for Benchmark, Translation, Errors and Bugs; lossless multipart clipboard support.
- Preserve OCR line boundaries for dehyphenation; correct partial/cancelled benchmark error classification; record translation error/timing samples.
- Device UI/permission/clipboard validation remains pending (docs/UX-1.1.4.md).

## [1.1.6] - 2026-11-19
- Keep floating icon visible during immediate consent launch; diagnose blocked trampoline entry.
- Exclude system bars and URL-shaped text from translation, retain short punctuated dialogue.
- Stop automatic uppercase/English-homograph name shielding; retain legacy glossary data.
- Incremental, measured opaque translation cards with tap-to-reveal and long-card scrolling.
- Worker-thread bitmap conversion; accurate partial-result completion markers.
- Device/OEM and before/after performance validation remains pending; see docs/READER-1.1.6.md.

## [1.1.7] - 2026-11-19
- Reconstruct nearby OCR lines across block boundaries with a light-corridor guard before translation.
- Prevent card collisions; fall back to one bounded scrollable reading panel when inline placement cannot fit.
- Skip recognized browser tab overview without revoking capture or claiming translation success.
- Include complete sample rectangles and non-content failure context.
- Added grouping/placement/chrome-filter regression tests; phone validation and contextual ML Kit limitations documented in docs/DIALOGUE-1.1.7.md.

## [1.1.8] - 2026-11-19
- Prepare an owner-authorized signing reset with durable AES-GCM encrypted recovery in Git and an independent GitHub Actions secret.
- One-time initialization refuses overwriting an existing vault, requires owner backup acknowledgement, and verifies recovery before committing encrypted material.
- Signing now requires the durable vault and pinned certificate; no temporary-key fallback.
- New-key generation/public APK remain blocked until the owner provisions and privately backs up SIGNING_BACKUP_PASSWORD. New signer will NOT update v1.1.6 in place; no data deletion/migration is performed.
- Add GitHub-run recovery, tamper, wrong-password and no-overwrite regression tests.

## [1.1.9] - 2026-11-19
- Surface safe, explicit initialization errors when the signing backup secret is missing/unavailable or too short.
- GitHub initialization attempt confirmed the required secret was unavailable; no key was generated or replaced. Publication remains blocked pending the repository Actions secret.

## [1.1.10] - 2026-11-19
- Owner-authorized new signing identity generated on GitHub, encrypted recovery round-trip verified, and encrypted key/public fingerprint persisted in Git.
- Durable recovery uses SIGNING_BACKUP_PASSWORD in repository Actions secrets plus its independent owner-held backup; subsequent releases reuse the same vault.
- This new signature cannot update v1.1.1–v1.1.6 in place. No uninstall, data clearing or automatic migration was performed.
- App behavior is unchanged from v1.1.7; this release establishes the new recoverable signing baseline once publication is verified.

### Offline reader audit (development)
- Enforce offline-only model checks, including dependencies in the merged manifest. Missing models no longer trigger downloads.
- Batch dark-proposer pixel sampling, reuse immutable frame samples and skip sampling when the screen is off. Add per-block dispatch diagnostics and hardware profiling tools.
- See `docs/offline-reader-audit.md` for the measured A/B and unverified device targets.
