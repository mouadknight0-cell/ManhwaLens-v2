# v1.1.19 — Arabic typography, RTL and grey-panel segmentation

## Evidence and diagnosis

Reviewed all four supplied Nekoyomi screenshots (2026-11-20 10-41-29-942 original, 10-41-28-636 replacement, 10-41-10-483 loading narration, 10-40-59-999 Guardian panel), plus private TXT `ManhwaLens-full-report-1795167809975.txt` (812,021 bytes). Do not commit the full private report.

- Thin white strips containing `…`: the fallback worked as coded but was unusable for ordinary dialogue. OCR sometimes emitted the three lines as separate regions; strict white-gap evidence rejected a light-grey textured panel. Each line's 21–27px height could not fit minimum-size Arabic with font padding. This is primarily segmentation and finite-area fallback, not disconnected Arabic glyphs.
- Report records separate `EAR D25, TOMBS` and `DVER EARTH.` and, in another capture, joined `YEAR 2D2S, TOMBS START TO APPEAR ALL DVER EARTH.`. The varying OCR output proves the grouping/recognition problem. Do not silently change 2D2S to 2025 based on a sentence table.
- `NELL BEGIN NITH THE LOADING NOWV.` -> `نبدأ nell nith التحميل newv.` is recorded in the TXT. RTL/font changes cannot repair that OCR corruption or guarantee correct meaning.
- `RELICS AND TOMBS MANAGEMENT BUREAU <GUARDIAN>` -> `مكتب إدارة القابر والمقابر <Guardian>` has semantic errors independently of mixed-script layout. The Latin name and brackets need an isolated LTR run within an RTL Arabic paragraph.
- `loading كاملة` is not acceptable natural translation; changing font/direction is not claimed to translate the remaining Latin word.
- Solid white rectangles differ visibly from the grey texture. The prior perimeter sampled too close to outlined glyphs and demanded 28/32 near-identical samples, often falling back to white. Exact texture reconstruction remains out of scope.

No supplied font file/name identifies the exact reference typeface. The selected font is a deliberate consistent Arabic solution, not a claim of exact font identification. Existing screenshots do not establish that every original Arabic glyph was disconnected.

## Changes

### Typography and bidi

Bundle unmodified **Noto Sans Arabic** (variable font, SIL OFL 1.1), about 825 KiB; license packaged in APK and documented in third_party/fonts. Native Typeface loads once with platform sans-serif fallback on load failure; no runtime download. Compose theme/body/history typography, translation result sheet, inline masks and detail reader share the font. Compose Arabic tracking is zero rather than Latin-oriented default letter spacing.

Display-only ArabicDisplay isolates Latin word/name runs (including `<Guardian>`) and European numeric runs with Unicode LRI/PDI. Native Android/Compose bidi and shaping handle the glyphs. No string reversal; stored OCR, translation, exports and copied text remain unchanged. Arabic inline translations use an explicit RTL base even if their first visible word is Latin. Short dialogue is centered; longer dialogue uses natural paragraph alignment. The multi-paragraph source+translation reader retains per-paragraph first-strong behavior so English source remains readable.

Font fitting measures the actual bundled font with StaticLayout, native shaping, font padding, line spacing and font scale. Same bounded binary size search, 12–30sp. Long text that fits at least a readable line now remains visible and scrollable inside the original opaque mask, with tap for full details and a scrollbar, instead of replacing the entire paragraph by an ellipsis. Extremely small regions or pathological text still need an explicit details marker; arbitrary-length text cannot be displayed simultaneously in a fixed finite rectangle without shrinking/overflow.

### Grey-panel grouping and mask color

Retain original geometry constraints. After white-gap evidence fails, a bounded tonal-gap check supports light-grey/tinted backgrounds. It requires predominantly consistent light tone, at least one clear gap row and rejects solid dark separators. A maximum four-pixel lower glyph-margin allowance handles white outlines around the next line; a solid boundary still vetoes at the margin. Maximum 4,096 probes per candidate, no new OCR/model pass. This remains a heuristic, not general scene/bubble segmentation.

Background sampling moves a few pixels outside the raw glyph perimeter (without enlarging the mask) and accepts modest texture variation when 24/32 samples agree within a conservative color tolerance. Opaque median-color replacement reduces white patches on textured grey panels but does **not** reconstruct grain, transparency, polygon contours or underlying artwork. Uncertain backgrounds still use white; existing source-aligned mask and conservative oval selection remain.

## Validation scope

- JVM: mixed Latin/name/number isolation with exact round-trip removal of display controls; no reversal; short/long policy; textured grey gaps; dark boundary veto; probe cap.
- Android 14: bundled font Arabic/Latin/numeric glyph availability, native RTL paragraph direction and LTR order of Guardian; 1.0/1.4 font scale layout; actual supplied grey screenshot crop through bundled ML Kit and grouping; whole narration region with TOMBS+EARTH rather than three thin strips; reference Arabic visible rather than ellipsis-only. The reference Arabic in rendering evidence is human-authored, NOT an improved ML Kit output.
- Existing opacity/circle/report/name/translation regressions retained. Tests now accept a readable scrollable overflow path rather than requiring ellipsis-only behavior.
- No physical Xiaomi gesture/font-size/layout A/B or latency/battery measurement here. Font adds APK size, and tonal evidence adds bounded work; unchanged speed is not yet measured. No cloud, larger translation model, extra inference pass, invented pronunciation or screenshot-specific phrase repair.

OPEN: semantic errors in the supplied examples; stylized character recognition; exact textured/polygon bubble reconstruction; all overlapping layouts; device-level touch/scroll regression and performance parity. This release is not a declaration that all requested display/translation defects are solved. Upcoming screenshots can expose additional cases.
