# Offline reader audit — baseline 7db19ca, tested revision bf45d35

**Verdict:** the dark-bubble proposer is faster for identical input, and the installed app can no longer use the Internet. There is not yet evidence that total reading latency or battery drain on the Xiaomi meets target.

## Audit and changes

|Problem|Evidence|Cause|Change|Gain / limit|
|---|---|---|---|---|
|Expensive dark-page discovery|A/B on `xiaomi-two-dark-dialogues.png`: 118,580 Bitmap reads per invocation|Separate JNI read for every tile corner and nine contrast samples|Read three scanlines per tile row; reuse them for both masks|462 reads (−99.6%); median 18.507 → 3.725 ms (−79.9%). Same corner colors and every mask bit asserted in all nine trials; two warmups. Emulator microbenchmark, not total OCR.|
|Redundant main-looper work on still content|Monitor tick every 80 ms; a retained Image could be sampled again every tick|Timer resampled the immutable image to advance the stability gate|Memoize sample on its owning frame; keep timer for debounce and motion confirmation|For a retained frame over ten ticks: ten sampler calls → at most one (code bound, not measured frames/s). New frames still checked.|
|Work while display off|Old monitor only checked whether the settings activity was visible|Foreground projection outlives screen interactivity|Discard arriving frames, pause/cancel live work and slow polling while off or in app UI|No frame sampling while paused; energy impact not measured. Reader foreground with ManhwaLens activity in background deliberately remains supported.|
|Model settings could access network|Explicit `downloadModelIfNeeded` plus merged-manifest Internet permission|Setup workflow bypassed offline expectation|Remove permission including library declarations; local existence/probe only, cache missing status until explicit recheck; bilingual status resources|Installed-APK instrumentation confirms INTERNET absent. New installs without model have dictionary **but not arbitrary MT**.|
|Recovery can try all five variants despite a correct read|Loop had no success exit|Best-score selection kept searching after corroboration|Stop on high-scoring exact phrase also seen by first pass at aligned coordinates|Saves remaining passes only in corroborated case; earlier exit on fragments risks losing lines. No measured corpus-wide latency gain.|
|Hard to correlate translation with region|Page cache returned before dispatch logging|Fast path lacked per-block event|Add trace ID, page, bounds, stage, elapsed microseconds, page-cache indicator; ordinary completion includes raw-vs-normalized change|Local diagnostics, not hardware presentation timestamps. Recovery winner remains a separate crop event; not a complete block-level flame graph.|

## Before/after evidence

`python3 tools/audit_report.py <export.txt>` reads the human-readable benchmark section. Do **not** concatenate exports: history overlaps. Below: Xiaomi 23106RN0DA Android 14, export 1795207616121, ok/partial journeys only. Median / nearest-rank p90 in ms. `—` means **no paired physical-device run**, not zero.

|Stage|n|Xiaomi before|Xiaomi after|
|---|---:|---:|---:|
|Capture|118|76.0 / 100.8|—|
|Image processing|118|15.0 / 21.9|—|
|OCR|118|279.9 / 384.5|—|
|Grouping|118|15.8 / 25.0|—|
|Model init/verification|118|5.2 / 11.3|—|
|Translation processing|118|263.5 / 614.4|—|
|Full processing|118|702.5 / 1140.4|—|
|Render dispatch|66|134.0 / 263.6|—|
|First visible proxy|108|776.6 / 1276.6|—|
|Final visible proxy|66|809.6 / 1117.3|—|

OCR has the highest stage median, but **translation has the longer p90**. Medians aren't additive. Earlier export 1795200837805 shows 997.4 / 1762.8 ms full processing, with translation 602.8 / 868.6 and OCR 401.7 / 668.3. This supports investigating both, not attributing the entire tail to capture or cold model startup. No tagged session with a 1.8 s **median** in the supplied scorecards: the 1762.8 ms figure is a p90. Need the corresponding export/trace to assign causality.

|Session-level measure|Before (1795207062898)|After, same pages/device|
|---|---:|---:|
|Cancelled|15/59 (25.4%)|—|
|Eligible rendered|58/72 (80.6%)|—|
|OCR-UNCERTAIN / TR-UNTRANSLATED|7/72 (9.7%) / 1/72 (1.4%)|—|
|Lexicon hits|2/70 (2.9%)|—|
|Processing median / p90 (37 completed samples)|639.2 / 796.9 ms|—|
|Peak memory / battery per 10 min / thermal throttling|Not recorded|—|

The adjacent short session had 5/11 cancelled, only 3/8 eligible rendered, and 0/8 hits. Don't use raw error-string counts as rates: the export repeats diagnostic material. `OCR-3003` is no translatable region, not proof of no dialogue; `OCR-UNCERTAIN` is a readability veto; `TR-UNTRANSLATED` is an output gate; `OCR-CLIPPED` needs a complete crop. `APP-1000` appears on `status=empty` summaries too and does **not** establish an app crash. `reader_chrome_or_insets`, `no_comic_surface` and `symbol_noise` are separate rejection stages; indiscriminately disabling them would let reader UI/SFX cover artwork.

## Verification and outstanding work

- [CI: unit tests + debug APK](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44840311521); [Android 14 regression suite + A/B evidence](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44840311519). Phrase startup 271 ms; extra index load allocated 2,105,344 Java bytes (not peak RSS). Source pack has 1,768 entries. Existing tests exercise seed matches, narration, correction invalidation, dark controls, and layout failures. These are not nine end-to-end chapter captures on hardware.
- For paired testing: install each revision on the **same phone**, retain the same model/dictionary and reader settings, fix brightness/refresh/zoom, cool to the same starting temperature, replay the same timed page/scroll sequence. Keep cold and warm cohorts separate. Export app diagnostics each time. From a host with adb, run `tools/profile-reader.sh baseline 600` and `tools/profile-reader.sh candidate 600` during the identical sequence. Avoid USB charging for drain; prefer battery charge counter over integer %. Script collects memory samples, battery/thermal snapshots, gfxinfo and a bounded 60 s Perfetto scheduling/GC/FrameTimeline trace. Sampling PSS isn't an instantaneous peak. Inspect CPU by thread, frame deadlines, and frequency changes; run 1800 s and inspect retained objects for leak testing.
- **Open:** no attached phone, profiler capture, battery/thermal measurement, 30-minute leak/stress evidence or paired quality rates. Need repeated physical tests of screen-off/resume, fast scrolling, rotation/split-screen, close/start and real colored/SFX pages. No claim of zero jank, zero duplicate MT, ≤450 ms median, or ≤600 ms first visible.
- **Still in code:** full-frame conversion (additional crop for row padding), private OCR bitmap copy protecting asynchronous native cancellation, recognizer client per pass, overlay windows per region, and main-thread frame listener. Do not recycle OCR inputs before native completion or remove capture copies without a tested ownership replacement. Static-page reuse is not general partial-frame OCR. Full localization of legacy UI, lazy per-genre loading and per-block stage timing across merging remain open.
