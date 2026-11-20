# ManhwaLens engineering state

## Current work: v1.1.23 direct rendering

Branch `arena/ae360a1c-manhwalens-v2`; PR9 open, not merged. Previous public release v1.1.22. Native Android, offline ML Kit EN→AR retained. Reuse existing signing vault/certificate; never reset data.

User reversed the secondary translation reader design. Its source files, threshold controller, launcher branches, controls and dependent tests have been deleted, not hidden. The main capture button remains. Successful text now tries source geometry then expands to a measured/clamped on-page rectangle, excluding the main control and other displayed translations. No extra interaction is required to reveal it.

Report: `docs/DIRECT-RENDERING-1.1.23.md`. Earlier audit reports describe historical designs and are superseded where they concern overflow presentation.

## Remaining limitations

Finite display area cannot hold arbitrary text at a readable minimum; exhaustion or rendering failure preserves source without any secondary UI. Expanded rectangles can obscure artwork. Actual Xiaomi 23106RN0DA / Android14 overlay z-order, drag timing, touch, rotation, latency and upgrade still require device checks.

General ML Kit semantic quality, unknown-name handling, full English spell correction and speaker-aware cross-bubble context remain limited as previously documented. No engine swap, cloud translation or extra OCR pass.

## Verification

Current tracked-tree removed-component/label search: zero matches at implementation checkpoint. CI 44767603972 and Android 14 regression 44767603849 passed at ffeb67d. Release build/signing pending.
