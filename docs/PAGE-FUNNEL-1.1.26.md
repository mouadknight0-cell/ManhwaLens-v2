# Page funnel — 1.1.26

Input: local 1.1.25 report, Xiaomi 23106RN0DA / Android 14; private report not committed. Retained samples are not a record of compositor success. Before evidence (distinct trace IDs, not counts of repeated log entries):

- `t-mwpafcmq-g`: only `1 CANNOT` reached translation and passed; not evidence that the full balloon was ever read. `t-mwpafebv-h`: `SEE!` and `1 CANNOT SEE!` both failed OCR input validation.
- `t-mwpafmou-p`: one success and one `TR-UNTRANSLATED` for a corrupted source. `t-mwpafhif-o`: both regions returned successfully. This is not a universal first-item limit.
- `t-mwpagh2t-13`: `IM STARTING TO 2EMEMBER.` reached block processing but ended `OCR-UNCERTAIN`. MediaProjection did deliver pixels. A spelling fix does not necessarily overcome the confidence gate.
- No joined batch parser or top-one selector in production. Identical work fans out to indexed source regions. Unexpected worker exceptions previously could cancel siblings; not demonstrated as the cause of these particular samples. A scene revision cancels stale work by design. No evidence of a deadlocked worker. Previously render used one rollback transaction for all windows, not erase-after-each-block. Model validation can reject residual Latin words; distinguish that from an OCR failure. Do not disable name shielding or accept untranslated prose.

New tracing: frame acquisition and OCR-stage counts (blocks and lines are different units), occlusion/filter/group decisions, translation dispatch, block result code and per-region window attachment or preservation. Window attachment is not proof of physical visibility. Local OCR retry uses the affected source crop only; no full-page second pass. Translation workers isolate exceptions and retry eligible failed work once. Stale jobs must still cancel. In-place geometry never relocates to a free rectangle. Insufficient space, obstruction and unreliable reconstruction keep the source.

Scope: real-image OCR replay is not the full reader, compositor or downloaded ML Kit translator. No new physical captures, OEM permission grant, revoked-token recovery or matched end-to-end timing available. Do not infer OEM denial from timeout. Cross-balloon semantic redistribution is not implemented. Textured reconstruction is a bounded local gradient approximation, not restoration of original art.

### File manifest (relative to v1.1.25)

- `M	.github/scripts/android-quality.sh`
- `M	PROJECT_STATE.md`
- `A	app/src/androidTest/assets/report25-question.png`
- `A	app/src/androidTest/assets/report25-voice.png`
- `A	app/src/androidTest/assets/report25-watching.png`
- `M	app/src/androidTest/java/com/manhwalens/app/QualityInstrumentedTest.kt`
- `M	app/src/main/java/com/manhwalens/app/capture/CaptureManager.kt`
- `M	app/src/main/java/com/manhwalens/app/capture/ScreenCaptureService.kt`
- `M	app/src/main/java/com/manhwalens/app/ocr/TextRecognitionClient.kt`
- `M	app/src/main/java/com/manhwalens/app/overlay/BubbleOverlayService.kt`
- `D	app/src/main/java/com/manhwalens/app/overlay/DirectPlacement.kt`
- `M	app/src/main/java/com/manhwalens/app/overlay/DirectTranslationLayout.kt`
- `M	app/src/main/java/com/manhwalens/app/overlay/ReplacementSurface.kt`
- `M	app/src/main/java/com/manhwalens/app/overlay/ReplacementTranslationView.kt`
- `A	app/src/main/java/com/manhwalens/app/overlay/StrokePatch.kt`
- `M	app/src/main/java/com/manhwalens/app/pipeline/OcrLexicon.kt`
- `M	app/src/main/java/com/manhwalens/app/pipeline/ProgressiveTranslation.kt`
- `M	app/src/main/java/com/manhwalens/app/pipeline/RegionFilter.kt`
- `M	app/src/main/java/com/manhwalens/app/translate/DialogueLexicon.kt`
- `M	app/src/main/java/com/manhwalens/app/translate/TranslationClient.kt`
- `M	app/src/main/java/com/manhwalens/app/ui/SettingsScreen.kt`
- `A	app/src/main/java/com/manhwalens/app/ui/XiaomiSetup.kt`
- `D	app/src/test/java/com/manhwalens/app/pipeline/DirectPlacementTest.kt`
- `M	app/src/test/java/com/manhwalens/app/pipeline/ProgressiveTranslationTest.kt`
- `M	app/src/test/java/com/manhwalens/app/pipeline/Xiaomi124Test.kt`
- `A	docs/PAGE-FUNNEL-1.1.26.md`
