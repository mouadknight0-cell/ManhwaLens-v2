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
│              ML Kit Translate (on-device, per-script source hint)    │
│                            │                                         │
│         HistoryStore.add()  └──► CaptureManager.state = Done(result) │
└──────────────────────────────────────────────────────────────────────┘
```

## Components

### `capture/`
- **ScreenCaptureService** — foreground service (type `mediaProjection`). Obtains the projection *after* `startForeground()` is called, per Android 14 requirements. Each capture creates a short-lived `VirtualDisplay` + `ImageReader`, waits ≤ 4s for the first frame, then tears the display down — the session token is reused for subsequent captures.
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
**TranslationClient** wraps ML Kit Translate. Models are downloaded on first use (`downloadModelIfNeeded`), pairs are cached, and every translation is memoized in the LRU cache. On failure the original text is passed through — the UI never blanks out.

### `overlay/`
**BubbleOverlayService** — foreground service (`specialUse`, subtype `manhwalens_translation_overlay`). Draws the draggable bubble (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`) and a bottom result panel (focusable, for buttons + scrolling). Bubble tap routes to an immediate capture when a session is held, else to the consent trampoline.

### `history/`, `settings/`
- **HistoryStore** — `index.json` + scaled JPEG thumbnails in `filesDir/history/`, capped at 100 entries. `org.json` format, decoded tolerantly.
- **SettingsRepository** — `StateFlow`s over SharedPreferences (source/target language, bubble toggle, onboarding flag).

## Threading
- ML Kit OCR: ML Kit's own executor via `await()`; `coroutineScope` for parallel recognizers.
- Translation: suspending `await()` calls on `Dispatchers.Main` service scope with IO-bound ML Kit work behind the scenes.
- Frame conversion: on the main-thread Handler (required by ImageReader listeners); processing is async.

## Failure modes
| Failure | Behaviour |
|---|---|
| Consent denied | Snackbar with reason; nothing captured |
| Capture timeout (4s) | `Error` state; service keeps session |
| Process death / stale token | `getMediaProjection` guarded; `START_NOT_STICKY`; capture falls back to a fresh consent |
| OCR/translation crash for a region | Original text passes through |
| Overlay permission revoked | Bubble service stops itself; in-app capture still works |

## Privacy
Frames live in memory only. No network calls except one-time ML Kit model downloads. History is app-private and user-clearable.
