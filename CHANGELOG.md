# Changelog

All notable changes to ManhwaLens are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
