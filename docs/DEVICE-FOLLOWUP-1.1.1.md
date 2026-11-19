# 1.1.1 candidate: device-report follow-up

## Evidence available
User supplied three screenshots and summary metrics (Xiaomi Android 14, 720×1600): 6 regions/blocks, OCR 444 ms, initialization 3.4 ms, frame 184.4 ms, image 11.2 ms, grouping 12.4 ms, first sentence 2558 ms, all visible 59062 ms, completion 60093 ms, total 60227 ms, usable 2279.8 ms. No exported JSONL or raw marks were attached. Earlier 19/14-region run timings are absent. These are user-reported measurements, not measurements executed here.

## Verified code causes
- END gravity with positive raw-X deltas reversed horizontal dragging. Now absolute LEFT gravity, platform touch slop, bounded movement, nearest-edge release and saved position.
- Default Android 14 projection consent permitted app-only capture. The screenshot showing our home UI over a browser is consistent with app-only capture (not proof of a stale frame). Now request default-display capture explicitly. A new consent session is required.
- Rotation previously created a second VirtualDisplay using the same consent token. Now resize the existing display and replace its ImageReader surface.
- Sequential ML Kit translation waited up to 15 seconds for each block; this is a possible contributor, not a proven explanation for 60 seconds. There is no remote translation API. Two bounded workers now deduplicate identical utterances in a capture and emit ready results serially, preserving spatial coordinates and final reading order. ML Kit may internally serialize inference; speedup must be measured.
- T0 was process start, so T0-to-result included user idle time. Copy Benchmark now shows T2-to-result separately, retaining historical T0 values. T10 moves from service emission to UI callback. UI callbacks remain render proxies, not physical frame presentation measurements.
- Broad catches swallowed coroutine cancellation. OCR/model/translation now propagate cancellation. New requests cancel and join obsolete journeys; Stop cancels the tracked job and tears down projection. Native ML Kit work may finish internally because its Task APIs do not expose cancellation. Cancelled work cannot deliver new translation results.
- Release fallback used a new ephemeral debug certificate per runner. Production releases now fail closed without a stable key. Application ID remains unchanged. Version codes derive from commit time since 2020, shared across CI/release rather than unrelated workflow counters.

## Implemented UI
- Tap bubble: Stop Translation, Start Translation, Open App, Close Menu.
- Independent non-touchable text windows at OCR coordinates instead of one result panel; sound effects excluded by default. Start clears previous translations before capture. This is not image inpainting.
- Settings: Copy Benchmark, view historical benchmark reports, category/optional description, save issue locally, view/copy/share TXT. No automatic uploading. Exception stack exports omit exception messages to avoid embedding captured text or credentials.

## Limitations / mandatory device tests
- No connected device or emulator; no measured after-results, touch validation, APK upgrade or data-preservation test.
- Independent windows still need fitting/overlap validation. Long Arabic text can clip; there is no contour-based dialogue balloon detection, continuous screen-change tracker, automatic unchanged-image OCR suppression, or configurable SFX mode yet. Scroll can leave old translations at their prior screen coordinates: stop or start again to clear; continuous reading is NOT complete.
- Two workers improve scheduling only; they do not prove lower inference latency. Native inference may continue after cancellation/timeout. Model readiness and inference timings still share the translation-stage total.
- UI and browser chrome are not yet reliably filtered. OCR grouping remains heuristic. A screenshot of a result alone does not prove which app/version ran.
- Original installed signing key is required to retain data on upgrade. A newly generated stable key cannot match a previously lost ephemeral key. GitHub secrets access returned HTTP 403 in this session. Never uninstall/clear data as a workaround. Debug CI artifacts are diagnostic builds, NOT guaranteed compatible upgrades; do not install them over daily-use data without certificate matching.
- No raw prior 19/14-region reports: comparison cannot be computed honestly. Copy Benchmark exports the on-device history to supply this evidence.

## Device acceptance protocol
1. Record installed version and signing SHA-256 (adb package inspection / apksigner on the installed APK); compare new APK signer before attempting update. Original private key must be restored if missing.
2. Start a NEW full-screen consent session. Open browser, start from bubble menu. Check OCR reads dialogue, not ManhwaLens.
3. Drag in four directions, slowly across touch slop, to all edges; release, rotate, restart service; check bounds and tap/drag separation.
4. Start then Stop during frame acquisition, OCR, model download and translation. No late text may reappear. Check notification/projection termination. Repeat Start/Stop rapidly.
5. Measure 5 cold and 30 warm captures of identical pages; export reports. Compare T2→T10, T2→T11, T2→T12 and per-block logs, not process lifetime totals.
6. Check spatial overlays, browser scroll-through, long Arabic clipping, and clearing; these require further work if acceptance fails.
7. Save an issue, reopen app, view/copy/share TXT, and inspect for sensitive content.
