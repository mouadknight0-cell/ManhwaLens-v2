# Changelog

All notable changes to ManhwaLens are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
