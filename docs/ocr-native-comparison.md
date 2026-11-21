# Offline OCR comparison — tested revision b21d700

Starting point: 2eb08fa, v1.1.32 candidate, crop-first off, strict placement on. Compared with 488c404 (1.1.30): the 1600px discovery limit, 180ms settle gate, weak-score recovery and native-task lifetime concerns already existed. The candidate adds CLAHE, frame tracking/verification, a shorter journey watchdog and optional crop deadlines. Counting `passes` messages as native runs confuses filtering/grouping events with recognizer submissions. No engine or model downloads were added.

## Paired results

[Android test evidence](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44881507173), Android 14 x86_64 emulator, same generated 640×320 page. Each arm uses a fresh session client and a separately logged first invocation, then 20 warm samples, fresh tracker and translation client each sample. This is not a cold OS launch or a Xiaomi benchmark. One valid exact Arabic output prepared on the detected reliable surface required per sample. Full = bitmap input through surface detection, translation and prepared overlay raster, NOT first compositor-visible frame. Time in ms, median / p90:

|Arm|Successful|OCR|Full|
|---|---:|---:|---:|
|Single native full-frame run|20/20|3268 / 3462|3272 / 3466|
|Candidate baseline, normal recovery eligibility|20/20|3309 / 3368|3312 / 3371|
|Prior crop-first / 200ms|0/20|no successful latency|no successful latency; misses 203 / 204|
|Flat-page verified glyph crop, full-frame fallback|20/20|2655 / 2723|2658 / 2727|

In one warm baseline sample: permit wait **10 µs**, init **4 µs**, bitmap copy **232 µs**, native submission through completion **3,608,793 µs**, then Kotlin resumed ~354 µs later. Exactly one native call, one line at confidence .799 and zero weak candidates. The major cost here is native execution, not copying or five recovery passes. This does not establish the root cause of every slow phone page. In the earlier instrumented run 44878056728 a crop waiter timed out while the native task was still running and the next invocation spent **199,235 µs queued**, never starting inference. Leases and bitmap ownership must follow native completion, not coroutine cancellation.

Separate unchanged-content test: a 40px scroll moved the verified identical glyph raster. First OCR **2655 ms**, repeated crop **9 ms**; same text, updated y position; replacing it with a blank page yielded no region. This is not in the warm fresh-input gate. A geometry-only cache would be unsafe. Cache stores local text coordinates, keyed by full crop bytes, size and language; translation and surface/occlusion checks still run for the current frame. On non-flat art the shortcut declines. Mixed first-pass pages prioritize strong siblings for progressive translation rather than synchronously retrying every weak line; connected-dialogue planning and grouping remain shared costs.

[Unit/build CI](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44881507132) succeeded. 30/31 instrumented tests passed, including colored text, romanized name, two bubbles, Korean noise, Room correction invalidation and moved-crop reuse. Only `oneBubbleProcessingBudget` failed: neither the 400ms OCR threshold nor the 700ms full threshold was met. Thresholds and release dependency retained. No official release. First actual canvas draw is separately logged as `render/first_draw` on live sessions from the request timestamp; it is closer to visibility than dispatch but not a display-panel presentation timestamp.

## Phone replay (no uninstall of the release)

[Diagnostic APK pair](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44881507143/artifacts/14698197831) — temporary debug artifacts. Install `fastFrame-false.apk`, then install `fastFrame-true.apk` over that test app. Same isolated `.profile` package, same debug signer, same dictionary across the two installs. Keep the official bubble stopped. **Do not uninstall your official app or clear either app's storage.** The isolated app cannot use a model private to the official app: use reviewed phrasebook pages for this offline OCR comparison, and record model availability rather than claiming general MT performance.

1. Use identical zoom, orientation and pages. Include white/purple/red, a name, two bubbles, art with no text and one static page. Start from the same scroll position; keep power mode constant and let the phone cool between runs.
2. Run each arm for five minutes, repeating the same pauses and scrolls. Note the first page separately. Check Arabic and placement visually; count blank, missing or stale results, not just displayed ones.
3. Stop normally and export the complete local diagnostic report after each run. Send **both `ManhwaLens-full-report-*.txt`**, labelled false/true, with page order. Match `ocr/native_task` identifiers, `first_gate`, `winner`, `frame_deadline`, `render/first_draw` and session scorecards. Reports may contain OCR text; review before sharing. Phone stage percentiles, cancellation, rendering rate and zero-watchdog target remain unverified.

Rollback: `-PfastFrame=false -PcropFirst=false` (default). Experimental compact-crop/recognizer reuse: `-PfastFrame=true -PcropFirst=false`. Old 200ms arm is comparison only; don't enable it for reading. `-PprofileInstall=true` applies debug-only isolation. No changes to release signing.
