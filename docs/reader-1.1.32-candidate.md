# 1.1.32 candidate — do not release

Base: signed v1.1.31 (`1a2b821`). Last paired Android test: `2cbdd5f`. No new APK has been published. Crop-first is **opt-in** (`-PcropFirst=true`) because of the red quality gate. Without that flag the existing OCR discovery path remains in use. Strict placement defaults on; `-PstrictPlacement=false` restores the previous placement policy. Both flags are build properties, not user-facing features.

## What the diagnostics actually establish

The supplied **summary** from Xiaomi Android 14 reports OCR 1100–1325 ms, processing median 1000–1400 ms, 4 CAP-2014 timeouts, 20–67% cancellations and 0–33% eligible rendered in three sessions. The attached JSON is the replacement seed, not the raw 1.1.31 journey export. The older available exports cannot be substituted for it. `passes` events include diagnostic messages; 182 such events do not mean 182 native tasks.

In code the old path performs full-frame OCR and can then run five variants per weak candidate, sometimes upscaled. A recognizer was closed after every native task. The experimental path uses contrast proposals, padded glyph bounds, a warm recognizer leased until native completion, native-scale first pass, bounded retries and per-region reuse. The independent post-translation crop retry is disabled on this path. It does not send the whole page to OCR as a fallback. One native task at a time. ML Kit does not expose native cancellation; the await budget and permission semaphore do not guarantee termination of the underlying task at the deadline.

### Same fixture, same Android 14 emulator job

[Raw diagnostic notices and test failure](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44851659378). Five fresh trackers, warm recognizer, identical generated `THIS SCENT` page and reviewed text. Both arms use the current dictionary. Processing covers bitmap input → detection/OCR/grouping → surface → translation → prepared raster, **not** MediaProjection waiting or compositor visibility. p90 is nearest rank (max for n=5).

|Measure|Legacy discovery|Bounded crop-first|
|---|---:|---:|
|Frames producing the expected Arabic and a prepared raster|5/5|0/5|
|Successful OCR median / p90|3351 / 3595 ms|not defined|
|Successful fixture processing median / p90|3355 / 3600 ms|not defined|
|Unsuccessful attempt median / p90|not applicable|213 / 265 ms|
|Whole-source keys for the eight newly listed English regression utterances (asset comparison)|1/8|8/8|
|Phrasebook rows|1768|1789 (1788 distinct normalized keys)|

**Decision:** cutting the wait is not a speedup if the bubble stays unread. The 400 ms OCR / 700 ms processing gate requires actual output on every measured frame; it fails before reporting a successful median. The release workflow now depends on this Android suite. Do not bypass the check or interpret passing unit tests as a release sign-off.

### Real-device comparison still required

|Measure|Xiaomi before (user summary)|Xiaomi after|
|---|---|---|
|Capture, preprocessing, grouping, model init, MT, render, first/final visible: median / p90|Raw stage records needed|Not measured|
|OCR / processing|1100–1325 ms OCR; processing median 1.0–1.4 s|Not measured|
|Watchdog / cancellations|4; 20–67% cancelled|Not measured|
|Eligible → rendered|0–33% in three sessions|Not measured|
|TR-UNTRANSLATED / OCR-UNCERTAIN / phrase hits / dictionary discards|Examples but no comparable denominators|Not measured|

## Other changes and risks

- Imported the supplied 491-row seed, including exact Arabic for the assassins, sect, witnesses and proper-name examples. Added five reviewed gerund utterances. Generic templates removed, fixed words ≥3 and slots ≤6. Common single-word terms are no longer substituted inside a sentence. Prefer validated plain MT; cache namespaces revised. Unknown gerunds and unseen romanizations are not guaranteed idiomatic.
- Added sentence-case/name-alias/segment recovery and structural checks. Aliases must survive once before transliteration is restored. Structural validity is not proof of semantic fidelity. Missing local Arabic model still means reviewed dictionary or untouched source, not a download.
- Preserve protected names during OCR hygiene; expose winning OCR pass and block stage, page cache and session dictionary discards. The reports are diagnostic events, not yet a single per-block attribution for all retry paths.
- Strict placement disables nearby synthetic boxes and rejects unreliable surfaces. Changed visible glyphs or changed exposed margins invalidate the view; uniform or obscured margins cannot prove a match. Geometry and margin tests do not prove zero stale faces on a phone.
- Request capture from the resumed consent activity, reject obsolete generations, keep errors off the comic. Android can still deny a background activity launch; only the foreground app and system permission UI can complete consent. No bypass and no reuse of tokens.
- [Unit tests + debug build](https://github.com/mouadknight0-cell/ManhwaLens-v2/actions/runs/44851659369) passed on the measured revision. Not verified: OEM consent transitions, dark recovery under the 200 ms deadline, full 5-minute scrolling run, 85% rendering or <25% cancellation. Next measurement: native task completion time vs awaited time for the same crop on the Xiaomi, including first call separately, then re-export both sessions with `tools/audit_report.py`. No extrapolation from emulator to Xiaomi.
