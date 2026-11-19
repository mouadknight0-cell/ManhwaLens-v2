# v1.1.2: screenshot-driven fixes, verification boundaries

## Input evidence
Two screenshots (16:16 on Xiaomi) are present. No diagnostic TXT/JSON export was attached to this request. The overlay contains translations of ManhwaLens's UI over the browser. No current timing distribution can be derived from these images. Prior user-reported timings (444 ms OCR, ~60 s full completion) are not new measurements; there are still no raw 19/14-region comparison runs.

## Root causes established in source
- Bubble service subscribed to a replaying StateFlow and immediately painted any previous Done/Partial, including captures initiated inside ManhwaLens. Render is now armed only by a new floating Start, constrained by request time, disabled in MainActivity foreground and invalidated on rotation.
- Progressive results rebuilt/hid the same panel that contained controls. The new compact horizontal connected action dock has independent ownership and native button click listeners; drag handling is only on the main bubble. Results cannot destroy its buttons. Native hit testing/MIUI behavior still requires device testing.
- Render callbacks marked a global active benchmark even for historical results. Result IDs now equal benchmark journey IDs; UI markers are rejected for a different/finished journey. A bounded fallback closes an unrendered final journey with t12Inferred=true.
- Stop could be followed by a pending consent result that started a new session. Consent requests now carry a generation; Stop invalidates outstanding consent.
- Short capitalized dialogue (YES!, NO!, STOP!) was classified as SFX and excluded. Only known SFX patterns are now excluded. OCR line grouping stays within ML Kit's recognized block boundaries rather than merging arbitrary neighboring blocks.

## Benchmark changes
M0→M1 measures model readiness/download/verification separately; T8→T11 measures the translation pipeline (including progressive delivery and sample persistence). T2 is propagated from the capture request, including the floating action's 250 ms compositor delay and consent time for a new session. T3→T4 remains frame wait; T5→T6 OCR; T6→T7 cleanup; T2→T9 useful text ready; T2→T10 UI callback; T2→T11 processing; T2→T12 final UI callback. T0 retains process lifetime for historical comparisons, NOT user latency. T10/T12 remain UI callback proxies, not hardware display timestamps.

Deduplicate stored IDs and displayed comparisons; negative/missing intervals are unavailable. Cold means first successful journey this process, not necessarily a downloaded model or a cold device. Warm captures check model presence and reuse process verification; explicit Verify repeats the inference probe. No measured on-device before/after speedup is claimed.

## Model verification
Download Language File / Retry checks ML Kit's installation registry, invokes idempotent downloadModelIfNeeded, verifies Arabic model presence and performs a real EN→AR probe before Ready. Failure, timeout and cancellation are distinct from success. Inference failure invalidates readiness. Percentage, byte size, raw checksums and explicit resume offsets are NOT exposed by ML Kit; UI says unavailable. Integrity/resumption is delegated to ML Kit, not independently certified. Network loss/storage exhaustion/corrupt-model device injection remain untested.

## Reports and samples
Errors/FATAL events automatically save content-free reports under diagnostics, retaining 50 automatic reports. Save Bugs remains manual contextual export. Copy Bugs supports newest 20 with explicit clipboard truncation; each report can be viewed/copied/shared in full. Exception messages are excluded from stacks to avoid accidental content leakage.

Automatic source/translation pair collection is initialized at app start, stored separately in private AtomicFile JSON, deduplicated per journey/coordinate, bounded to 200 pairs with 2000 chars per field. No auto-upload; UI discloses collection and provides Copy Translated Text, Export Samples, Clear. Reports do not automatically include samples. Basic credential-pattern redaction is NOT a universal PII detector; users must review samples before sharing. Sample persistence runs on IO but is awaited by progressive delivery, so overhead must be measured.

## Remaining device validation and limitations
- No device/emulator is attached. No claims of verified MIUI click/drag, model download, capture freshness, visual placement, install/update or data preservation.
- Test expand/collapse repeatedly, drag all directions, click Start/Stop/Disable, disable/re-enable through Settings, rotate, revoke permissions, stop during consent/OCR/inference and verify no late result appears.
- Native ML Kit Task work may continue internally after coroutine cancellation; cancelled results are not published.
- No continuous scroll tracking yet: clear/start again after moving the page. Fitting uses font autosizing but not speech-balloon contours; long text and overlap still need device validation.
- Context-aware chapter-level translation, a new translation model and independent Arabic quality evaluation were not implemented. ML Kit quality limitations remain.
- Service startup failures and OEM background restrictions still need physical-device failure injection.
