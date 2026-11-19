# Architecture

ManhwaLens is a **100% on-device** translation lens for manhwa. No backend, no analytics, no data egress.

```
┌─────────────────────────── UI (Compose) ───────────────────────────┐
│ MainActivity ── Home / History / Settings / Onboarding              │
│      │                                  ▲                           │
│      ▼                                  │ StateFlow                 │
│ CaptureManager.state ────────────────done/working/error──► UI + Bubble│
└─────────────────────────────────────────────────────────────────────┘
        │ Intent                ▲                        ▲
        ▼                       │                        │
┌─ CaptureConsentActivity ─┐   │ reads                  │ collects
│ MediaProjection consent  │   │ CaptureManager         │ CaptureManager
└───────────┬──────────────┘   │                        │
            │ token             │                ┌───────┴──────────────┐
            ▼                   │                │ BubbleOverlayService │
┌─ ScreenCaptureService (FGS: mediaProjection) ───────────────────────┐
│  holds MediaProjection session                                      │
│   ┌──────────────┐   ┌─────────────┐   ┌─────────────────────────┐ │
│   │ VirtualDisplay│→ │ ImageReader │→ │ frame → bitmap (padded)  │ │
│   └──────────────┘   └─────────────┘   └───────────┬─────────────┘ │
│                            ML Kit OCR (per script, parallel)        │
│            ko / ja / zh / latin recognizers → TextRegion list       │
│                            │                                         │
│              RegionFilter (noise) → RegionMerger (bubbles)          │
│                            │ sorted reading order                    │
│              ML Kit Translate (on-device, EN → AR only in v1.1)      │
│         sentence reconstruction · CN/KR name shield · glossary        │
│                            │                                         │
│         HistoryStore.add()  └──► CaptureManager.state = Done(result) │
└──────────────────────────────────────────────────────────────────────┘
```

## Components

### `capture/`
- **ScreenCaptureService** — foreground service (type `mediaProjection`). Obtains the projection *after* `startForeground()` is called, per Android 14 requirements. A **persistent** `ImageReader` + `VirtualDisplay` are created once when the session starts (callback registered first) and held for the session's lifetime — each capture waits ≤ 1.5s for a fresh mirrored frame (falling back to the newest available frame) instead of racing a per-capture display. The pipeline is rebuilt once per capture if display metrics changed (rotation), and released in the order VirtualDisplay → ImageReader → MediaProjection. A journey = one capture → Arabic on screen (T0–T12, see `benchmarks/`).
- **CaptureConsentActivity** — transparent trampoline that owns the consent dialog. Both the in-app button and the bubble route through it, because projection tokens can only be minted from an Activity result.
- **CaptureManager** — process-wide `StateFlow<CaptureState>` (`Idle / Working(status) / Done / Error`) shared by UI and overlay.

### `ocr/`
**TextRecognitionClient** runs the script recognizers relevant to the configured source language in parallel (e.g. Korean + Latin for `ko`, or all four for `auto`), converts each line's bounding box to `TextRegion`, then applies `RegionFilter` + `RegionMerger`.

### `pipeline/` (pure Kotlin, unit-tested)
- **RegionFilter** — drops empty/symbol-only lines, tiny glyphs, and exact duplicates.
- **RegionMerger** — clusters stacked lines into speech bubbles (vertical gap + horizontal overlap/distance rules) and joins them in reading order.
- **TranslationCache** — normalized LRU cache shared by all translations.
- **LanguageMapper** — source setting → recognizers; script hint → BCP-47 code for auto mode; supported target list.
- **HistoryJson** — version-tolerant JSON (de)serialization for history entries.

### `translate/`
**TranslationClient** wraps ML Kit Translate, locked to **English → Arabic** in v1.1. Before every translation, character names detected in the region (CN/KR romanization heuristics + the persistent learned glossary) are masked with numeric sentinels and restored afterwards — names are never translated or transliterated. Sentence reconstruction (`SentenceReconstructor`) runs upstream so the translator sees complete utterances; the LRU cache keeps terminology consistent. Translation failures keep the original text; model download failure aborts the journey with error code `TRN-5002`.

### `diagnostics/`
**Diagnostics** — process-wide structured logging. Every event carries a timestamp, level (INFO/WARN/ERROR/FATAL), consistent error code (`CAP-2xxx`, `OCR-3xxx`, `TRN-5xxx`, …), component, operation, a per-journey `trace_id` that chains startup → capture → OCR → reconstruction → translation → render, optional stage, duration, recovery action and status. Events are echoed to logcat, kept in a bounded in-memory ring, and rotated to `filesDir/diagnostics/current.log` (JSONL, 512 KB, one previous file kept). Screen text and translations are never logged — metadata only. **ExportManager** bundles logs + benchmark records into one shareable file (Settings → Diagnostics).

### `benchmarks/`
**BenchmarkTracker** records the T0–T12 checkpoints of each capture journey, **BenchmarkStore** persists up to 200 records (app-private JSON), **BenchmarkStats** computes avg/min/max/P50/P90/P95/P99 (P99 only with ≥100 samples), success/failure/timeout rates and FPS. Cold vs warm journeys are tracked separately. T12 is marked by the UI when the final result is rendered; if the UI never confirms, the record falls back to T11 and is flagged `t12_inferred=true`. See docs/DIAGNOSTICS.md.

### `names/`
**NameDetector** (Korean/Chinese surname + given-token patterns, glossary-aware, deduped by span), **NameShield** (mask/restore round-trip, Arabic-Indic digit normalization), **NameGlossaryStore** (persistent learned names, auto-promoted from surname-pattern detections).

### `overlay/`
**BubbleOverlayService** — foreground service (`specialUse`, subtype `manhwalens_translation_overlay`). Draws the draggable bubble (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`) and a bottom result panel (focusable, for buttons + scrolling). Bubble tap routes to an immediate capture when a session is held, else to the consent trampoline.

### `history/`, `settings/`
- **HistoryStore** — `index.json` + scaled JPEG thumbnails in `filesDir/history/`, capped at 100 entries. `org.json` format, decoded tolerantly.
- **SettingsRepository** — `StateFlow`s over SharedPreferences (source/target language, bubble toggle, onboarding flag).

## Threading
- Pipeline (OCR → reconstruct → translate → history): service coroutine scope on `Dispatchers.Default`.
- ImageReader listener: main-thread Handler (required); handoff to pipeline via `CompletableDeferred`.
- Compose UI: state via `CaptureManager` StateFlow; overlay panel mutated from the service's main dispatcher.

## Failure modes
| Failure | Behaviour |
|---|---|
| Consent denied | `CAP-2002`-class error state; nothing captured |
| No frame within 1.5s | `CAP-2010`; journey aborted, session kept, retry possible |
| Projection revoked by system | `CAP-2012`; pipeline torn down; user must re-grant |
| OCR exception / empty page | `OCR-3002` / `OCR-3003`; journey aborted or empty result, logged |
| Model download fails / offline | `TRN-5002`; journey aborted before translating |
| Translation timeout (15s) | `TRN-5005`; original text kept for that block |
| Invalid translation (empty) | `TRN-5004` via empty pass-through; original kept |
| Render/attach failure (overlay) | `UI-6003`; error logged with code |
| Process death / stale token | `START_NOT_STICKY`; token never replayed; fresh consent required |
| Overlay permission revoked | Bubble stops itself; in-app capture still works |

## Privacy
Frames live in memory only. No network calls except one-time ML Kit model downloads. History is app-private and user-clearable.
