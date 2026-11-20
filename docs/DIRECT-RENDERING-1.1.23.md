# Direct page rendering — v1.1.23

## Component identification and deletion

The main capture control is `bubbleView` in `BubbleOverlayService`. Its capture/start/stop menu, position persistence and visual design remain. The removed secondary UI was a separate translation launcher and manually opened text reader. No ambiguity between the two controls was found.

Deleted five source files implementing the secondary rectangular reader, its content formatter, icon visibility threshold controller, older circular text reader and its geometry helper. The exact deleted paths are available with `git diff --diff-filter=D --name-only 22ab058 HEAD`. Also removed their tests, all launcher/open-reader branches, source-toggle/close handlers, scroll gestures/state in the replacement view, reader-only block eligibility policy, obsolete bottom-panel construction helpers, unused panel error constant and unused imports. No feature flag or dormant implementation remains.

Shared app result-sheet close/copy strings are retained because they are still used by the in-app result sheet, not by the deleted overlay feature. The browser-content OCR filter is unrelated and remains.

## New rendering path

1. Wait for the completed current request; genuine model/validation failures retain original dialogue. Existing one-retry execution policy, short-dialogue validation and glossary-name exception remain.
2. For each successful translation, try the original region using native Arabic layout and 12–30sp fitting.
3. If it does not fit, measure full text in wider rectangular candidates, starting at 16sp and then 12sp. Expand height to the actual native layout height plus padding. Choose a clamped position near the original region.
4. Exclude the main capture button (with an 8dp margin) and already placed translations from candidate rectangles. No controls are moved to make room. Candidate search is bounded to eight free rectangles.
5. Draw complete text and background offscreen, then attach the prepared pixels directly. No tap, launch control, warning panel or scrolling text view is involved.
6. During capture-button dragging, overlapping translations are hidden; on release, the current completed result is re-laid out around the new button position. Device compositor timing is not established by pure geometry tests.

The old surface-confidence and per-block reader eligibility gates no longer divert otherwise successful output. The in-app sheet shows source text for failed items. The former OCR review-state code was replaced by the existing uncertain-input error; it cannot open a special UI.

## Capacity / safety limits

Expansion can cover more artwork than the former source-sized mask; this is the requested direct rectangular behaviour, not image reconstruction. Free screen area is finite. If all screen space is occupied, text is physically longer than the available area at the readable minimum, or bitmap allocation/drawing fails, original text is retained with diagnostics only. There is no hidden secondary UI in that path. It is not mathematically possible to guarantee simultaneous complete display of arbitrary-length text on a finite screen without scrolling, truncation or an unreadable font.

Native preparation is limited to 20,000 input characters and 8,000,000 pixels per view; these are safety bounds, not triggers for another UI. No OCR/model extra pass or new translation engine.

## Automated verification

- Pure geometry: free rectangles exclude capture control and existing dialogue; oversized/offscreen requested dimensions clamp inside each available rectangle.
- Android: short, long and very long Arabic at central, bottom-edge and capture-button-overlapping source positions; portrait 720×1600, landscape 1600×720, compact 360×640; font scales 1.0/1.4. Tests require complete prepared text, drawn ink on first draw, no click requirement, no scrollbar, font >=12sp, screen containment and no button intersection.
- Existing failed-raster transparency, native shaping/bidi, source preservation, model output validation, short/name-only dialogue and retry regressions retained.
- Deleted-feature tests removed rather than retaining source dependencies solely for testing.
- Repository search covers all current tracked files, including docs/tests, for the removed class names, launcher methods, review-state identifier and the two removed Arabic labels. Git history and previous published versions are not rewritten.

## Manual Xiaomi checklist (NOT executed here)

Device: Xiaomi 23106RN0DA, Android 14.

- Short dialogue: capture once; full Arabic visible on page immediately.
- Long dialogue: region expands/reflows to rectangle; no additional tap and no secondary UI.
- Very long dialogue: full tested-length paragraph visible; no hidden lines/scrolling popup. Record any actual screen-capacity case rather than claiming arbitrary lengths fit.
- Top/bottom/left/right edges: size/position clamped and main button unobstructed.
- Drag main button across translation, release: button remains usable, translation reflows without a secondary UI.
- Failed/uncertain input: original remains; no warning reader. Next valid item still renders.
- Rotation, font-scale changes, several simultaneous bubbles, capture lifecycle, physical first-frame timing and signed in-place upgrade need verification.

## Execution and publication

Pending GitHub results below. No physical-device or end-to-end speed parity claim.
