# Diagnostics & Benchmarks (v1.1.0)

ManhwaLens ships with a complete observability layer: structured logs, benchmark
records per capture journey, an on-device export, and an honest reporting
contract. This document defines the checkpoints, the metric math, how to export
the data, and how to verify on a physical device.

**Golden rule: never claim a fix or an improvement without a recorded run.**

## 1. Structured logging (`diagnostics/`)

Every event is one JSON line with:

| field | meaning |
|---|---|
| `ts` | epoch millis |
| `level` | `I` / `W` / `E` / `F` (DEBUG exists in code) |
| `code` | stable error code — see `ErrorCodes.kt` (`APP-1xxx`, `CAP-2xxx`, `OCR-3xxx`, `TXT-4xxx`, `TRN-5xxx`, `UI-6xxx`, `STO-7xxx`) |
| `component` / `operation` | e.g. `capture.frame_wait` |
| `trace_id` | **unique per capture journey** — chains startup → capture → OCR → reconstruction → translation → render |
| `session_id` | app session id (best-effort grouping) |
| `stage` | optional pipeline stage (e.g., `T4`) |
| `duration_ms` | stage duration |
| `recovery` | recovery action taken, if any |
| `status` | `ok` / `recoverable` / `error` / `fatal` |
| `context` | bounded key/value metadata (sizes, counts, codes — **never screen text**) |
| `exception` / `stack` | exception type + stack for crashes |

Sinks: logcat (`ManhwaLens` tag), ring buffer (400 events), `filesDir/diagnostics/current.log`
(JSONL, rotates at 512 KB, one `previous.log` kept).

**Privacy:** no screen pixels, no OCR text, no translated text is ever logged.

## 2. Benchmark checkpoints (per journey)

| Mark | Meaning | Marked by |
|---|---|---|
| T0 | process start (Application.onCreate) | `BenchmarkTracker.markProcessStart()` |
| T1 | UI usable (first composition) | `MainActivity` (`ManhwaLensRoot`) |
| T2 | capture initialization begins | `BenchmarkTracker.beginSession()` |
| T3 | capture session active (projection + pipeline ready) | `ScreenCaptureService` |
| T4 | first frame acquired | `ScreenCaptureService` |
| T5 | image processing done (downscale for OCR) | `ScreenCaptureService` |
| T6 | text detection + OCR complete | `ScreenCaptureService` |
| T7 | grouping & sentence reconstruction done | `ScreenCaptureService` |
| T8 | translation begins | `ScreenCaptureService` |
| T9 | first complete Arabic sentence available | `ScreenCaptureService` |
| T10 | first complete Arabic sentence visible on screen | `ScreenCaptureService` (first block emitted) |
| T11 | full-screen content translated | `ScreenCaptureService` |
| T12 | all content rendered | UI (`ResultBottomSheet` / overlay panel attach); auto-finishes the journey |

**Headline metrics:** `T10 − T0` (first visible sentence, cold-to-visible) and
`T12 − T0` (full screen). Pipeline-only latency: `T9 − T4`.

**Fallback honesty:** if the UI never marks T12 (app backgrounded with no overlay),
T12 is approximated from T11 and the record is stored with `t12_inferred=true`.
Cold vs warm journeys are flagged (`coldStart`).

## 3. Statistics

`BenchmarkStats`: avg / min / max / P50 / P90 / P95 / P99 (P99 only when
n ≥ 100), plus outcome accounting (`ok` / `error` / `timeout`) and FPS
(successful journeys per second). Records are persisted (max 200) in
`filesDir/benchmarks/records.json`.

## 4. Export bundle (on-device)

Settings → **Diagnostics & benchmarks → Export**.
The bundle is a plain-text file with: app/device info, benchmark records
(JSON), in-memory ring snapshot (JSON), and the rotated disk log (JSONL).
Share it from the system share sheet. **Never attach it to public issues
without reviewing it first** — it contains no content by design, only metadata.

## 5. Required before claiming "fixed & fast"

Run on a physical device (exact protocol):

1. Install the debug APK from CI artifact or release.
2. `adb logcat | grep ManhwaLens` in one terminal.
3. Fresh install → cold launch → wait for home screen (T1 set).
4. Grant capture consent; open a manhwa page with dialogue.
5. Tap the bubble; wait for the panel; dismissing and repeating 30× for
   warm numbers, plus 5 force-stopped launches for cold numbers.
6. Capture Settings → Export; attach to the issue/PR. Confirm `status=ok`,
   `t12_inferred=false` for visible runs.
7. Acceptance: capture starts within ~2s of consent; first frame ≤1.5s;
   no lost frames across repeated start/stop; stable memory
   (`adb shell dumpsys meminfo com.manhwalens.app`); no FGSS crashes.

Physical-device runs are **mandatory for any latency or reliability claim**;
CI and JVM unit tests cannot validate MediaProjection behaviour.

## 6. Failure injection checklist (must be reproduced manually on-device)

| Inject | Expected behaviour & code |
|---|---|
| Deny consent dialog | Error state `CAP-2002`-class; no crash; settings remain usable |
| Revoke projection mid-read (system UI) | `CAP-2012`; panel/notification stop; retry asks consent again |
| Airplane mode, model already downloaded | Translation works offline |
| Airplane mode, first launch | `TRN-5002` error; no translate attempt with stale model |
| Blank/undersampled manhwa page | `OCR-3003` warning; empty result state, not a crash |
| Very long chapter page | OCR completes or journey ends with `OCR-3002`, either way logged |
| Rotate during capture | Pipeline rebuilt once (`CAP-2010` class events absent); journey completes |
| Start/stop 20× quickly | No leaked VirtualDisplays/ImageReaders (check `adb shell dumpsys SurfaceFlinger` for stale `ManhwaLensCapture`) |
| Kill app mid-journey | Next start passes the 30s watchdog (`benchmarks.watchdog` warning) |

## 7. Known limitations (as of v1.1.0)

1. **Capture fix is not yet device-verified.** The v1.0 defect ("session
   does not work") was traced to per-capture ImageReader/VirtualDisplay
   creation racing the GPU (fresh-display frame delivery is not guaranteed
   for static screens) plus a bogus "session expired" on user stop. Both are
   fixed by construction; verification protocol above is required before a
   release claim.
2. EN→AR only. Korean/Japanese/Chinese sources are out of scope until the
   EN→AR pipeline is device-proven.
3. ML Kit translation quality is bounded by the on-device model; "natural
   Arabic" improves with complete sentences (hence reconstruction) but is not
   a large-language-model output.
4. T12 measured by UI composition is a proxy for "painted"; true paint
   callbacks require Compose frame-timing hooks (future: `FrameAnalytics`).
5. `t12_inferred` records must be excluded (or reported separately) in
   benchmark summaries.
6. CJK → Arabic name consistency for non-English sources is not covered by
   this release; the CN/KR name shield only protects Latin-romanized names.
